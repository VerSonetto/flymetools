package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Display
import android.view.MotionEvent
import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 状态栏手势：双击锁屏 + 横向滑动调亮度。
 *
 * 挂载点：PhoneStatusBarView.dispatchTouchEvent —— 状态栏根 View，无论触摸落在哪个子
 * View（时钟/通知图标/系统图标/空白区），父 View 的 dispatchTouchEvent 均收到每个事件，
 * 覆盖整个状态栏。
 *
 * 两个功能共享一份触摸状态机，独立开关，避免多个 hook 同时 return true 覆盖 result 的竞态：
 * - 双击锁屏：干净 UP 暂扣，窗口内第二击吞掉并触发 PowerManager.goToSleep。
 * - 亮度滑动：FIRST_TRACKING 中首次超过 slop 的方向锁定，横向 → 拦截后续事件并实时
 *   setTemporaryBrightness，UP 时 setBrightness 持久化；纵向 → 放行（正常下拉）。
 *   进入亮度前发送 CANCEL 终止系统侧可能的下拉手势。
 *
 * 亮度值域与写入方式对齐 BrightnessController：float 0.0~1.0（当前亮度取
 * display.brightnessInfo.brightness，滑动量 = ΔX / 状态栏宽）。
 */
object StatusBarDoubleClickHook : FeatureHook {

    private const val TAG = "StatusBarGesture"
    /** 亮度滑动功能独立日志标识：过滤时与双击锁屏分开（adb logcat -s FlymeTool 内按 tag 区分） */
    private const val BRIGHTNESS_TAG = "StatusBarBrightness"
    private const val FEATURE_DBL_KEY = "status_bar_double_click_lock"
    private const val FEATURE_BRIGHTNESS_KEY = "status_bar_brightness_swipe"

    private const val VIEW_CLASS = "com.android.systemui.statusbar.phone.PhoneStatusBarView"

    /** 干净点按抬起后，等多久才确认是单击并放行 UP */
    private const val DOUBLE_TAP_WINDOW_MS = 200L

    /** 按下超过此时长再抬起，不当作双击候选，UP 立刻放行 */
    private const val TAP_MAX_HOLD_MS = 180L

    private const val MOVE_SLOP_DP = 12f

    private val hooked = AtomicBoolean(false)

    private var dblClickEnabled = false
    private var brightnessEnabled = false

    private var classLoader: ClassLoader? = null
    private var touchMethod: Method? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private enum class Phase {
        IDLE,
        /** 第一击/手势进行中，DOWN 已交给系统 */
        FIRST_TRACKING,
        /** 第一击干净 UP 被暂扣，等待第二击 */
        WAIT_SECOND,
        /** 双击第二击进行中，整段吞掉 */
        SECOND_TRACKING,
        /** 横向滑动调亮度进行中，整段吞掉 */
        BRIGHTNESS_ACTIVE,
    }

    @Volatile
    private var phase = Phase.IDLE

    private var targetView: View? = null
    private var pendingUp: MotionEvent? = null

    private var downElapsed = 0L
    private var firstUpElapsed = 0L
    private var downX = 0f
    private var downY = 0f
    private var movedOffTap = false
    private var moveSlopPx = 24f

    /** 首次超过 slop 的方向一旦锁定，中途不再切换（横滑调亮度 / 纵向下拉互斥） */
    private var directionDecided = false
    private var directionHorizontal = false

    private var displayId = Display.DEFAULT_DISPLAY
    private var startBrightness = 0f
    private var currentBrightness = 0f

    private val releaseFirstUpRunnable = Runnable {
        if (phase != Phase.WAIT_SECOND) return@Runnable
        Logger.d(TAG) { "单击确认，放行暂扣的 UP" }
        releasePendingUp()
    }

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        dblClickEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_DBL_KEY)
        brightnessEnabled =
            XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_BRIGHTNESS_KEY)
        if (!dblClickEnabled && !brightnessEnabled) return
        if (!hooked.compareAndSet(false, true)) return

        classLoader = lpparam.classLoader

        try {
            val viewClass = XposedHelpers.findClass(VIEW_CLASS, lpparam.classLoader)
            val method = viewClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            touchMethod = method
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val event = param.args[0] as? MotionEvent ?: return
                    if (onTouch(view, event)) {
                        param.result = true
                    }
                }
            })
            Logger.i(
                TAG,
                "已挂载 $VIEW_CLASS.dispatchTouchEvent 双击=$dblClickEnabled 亮度=$brightnessEnabled"
            )
        } catch (e: Throwable) {
            hooked.set(false)
            Logger.e(TAG, "挂载失败", e)
        }
    }

    /** @return true = 已消费，跳过系统 dispatchTouchEvent */
    private fun onTouch(view: View, event: MotionEvent): Boolean {
        ensureMetrics(view)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 双击第二击：取消暂扣的第一击，吞掉第二击
                if (dblClickEnabled &&
                    phase == Phase.WAIT_SECOND &&
                    firstUpElapsed > 0L &&
                    SystemClock.elapsedRealtime() - firstUpElapsed <= DOUBLE_TAP_WINDOW_MS
                ) {
                    mainHandler.removeCallbacks(releaseFirstUpRunnable)
                    abortFirstGesture(view, event.rawX, event.rawY)
                    recycle(pendingUp)
                    pendingUp = null

                    phase = Phase.SECOND_TRACKING
                    targetView = view
                    downElapsed = SystemClock.elapsedRealtime()
                    downX = event.rawX
                    downY = event.rawY
                    movedOffTap = false
                    Logger.d(TAG) { "双击第二击 DOWN，已取消第一击" }
                    return true
                }

                // 新序列：清理残留，DOWN 立刻放行
                if (phase != Phase.IDLE) {
                    clearWaitingState(abort = true, view = view)
                }

                phase = Phase.FIRST_TRACKING
                targetView = view
                downElapsed = SystemClock.elapsedRealtime()
                downX = event.rawX
                downY = event.rawY
                movedOffTap = false
                directionDecided = false
                directionHorizontal = false
                displayId = try {
                    view.display?.displayId ?: Display.DEFAULT_DISPLAY
                } catch (_: Throwable) {
                    Display.DEFAULT_DISPLAY
                }
                startBrightness = readBrightness(view)
                // 不 return true：DOWN 交给系统，下拉/按压零延迟
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                when (phase) {
                    Phase.FIRST_TRACKING -> {
                        if (brightnessEnabled && !directionDecided) {
                            val dx = event.rawX - downX
                            val dy = event.rawY - downY
                            if (abs(dx) > moveSlopPx || abs(dy) > moveSlopPx) {
                                directionDecided = true
                                directionHorizontal = abs(dx) > abs(dy)
                                if (directionHorizontal) {
                                    Logger.d(BRIGHTNESS_TAG) { "进入亮度滑动" }
                                    // 终止系统侧可能的下拉手势
                                    abortFirstGesture(view, event.rawX, event.rawY)
                                    phase = Phase.BRIGHTNESS_ACTIVE
                                    applyBrightness(view, event)
                                    return true
                                }
                            }
                        }
                        // 纵向 / 未决定：放行给系统（下拉）
                        isMovedOff(event)
                        return false
                    }
                    Phase.BRIGHTNESS_ACTIVE -> {
                        applyBrightness(view, event)
                        return true
                    }
                    Phase.SECOND_TRACKING -> {
                        if (isMovedOff(event)) {
                            Logger.d(TAG) { "第二击滑动，取消双击" }
                            phase = Phase.IDLE
                        }
                        return true
                    }
                    else -> return false
                }
            }

            MotionEvent.ACTION_UP -> {
                when (phase) {
                    Phase.FIRST_TRACKING -> {
                        val held = SystemClock.elapsedRealtime() - downElapsed
                        val isCleanTap = !movedOffTap && !isMovedOff(event) && held < TAP_MAX_HOLD_MS
                        if (dblClickEnabled && isCleanTap) {
                            // 暂扣 UP：窗口内无第二击再放行
                            recycle(pendingUp)
                            pendingUp = MotionEvent.obtain(event)
                            phase = Phase.WAIT_SECOND
                            firstUpElapsed = SystemClock.elapsedRealtime()
                            mainHandler.removeCallbacks(releaseFirstUpRunnable)
                            mainHandler.postDelayed(releaseFirstUpRunnable, DOUBLE_TAP_WINDOW_MS)
                            Logger.d(TAG) { "第一击 UP 暂扣 ${DOUBLE_TAP_WINDOW_MS}ms" }
                            return true
                        }
                        phase = Phase.IDLE
                        return false
                    }
                    Phase.BRIGHTNESS_ACTIVE -> {
                        commitBrightness(view)
                        phase = Phase.IDLE
                        return true
                    }
                    Phase.SECOND_TRACKING -> {
                        val ok = !movedOffTap && !isMovedOff(event)
                        phase = Phase.IDLE
                        firstUpElapsed = 0L
                        if (ok && dblClickEnabled) {
                            Logger.i(TAG, "双击锁屏触发")
                            performLockScreen(view)
                            playHaptic(view.context)
                        } else {
                            Logger.d(TAG) { "第二击抬起已偏离，忽略" }
                        }
                        return true
                    }
                    Phase.WAIT_SECOND -> {
                        // 异常：等待期间又来了 UP，放行暂扣并让当前事件走系统
                        releasePendingUp()
                        return false
                    }
                    else -> return false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                when (phase) {
                    Phase.FIRST_TRACKING -> {
                        phase = Phase.IDLE
                        return false
                    }
                    Phase.WAIT_SECOND -> {
                        clearWaitingState(abort = false, view = view)
                        return true
                    }
                    Phase.SECOND_TRACKING -> {
                        phase = Phase.IDLE
                        return true
                    }
                    Phase.BRIGHTNESS_ACTIVE -> {
                        phase = Phase.IDLE
                        return true
                    }
                    else -> return false
                }
            }
        }
        return false
    }

    private fun ensureMetrics(view: View) {
        try {
            moveSlopPx = MOVE_SLOP_DP * view.resources.displayMetrics.density
        } catch (_: Throwable) {
            moveSlopPx = 24f
        }
    }

    private fun isMovedOff(event: MotionEvent): Boolean {
        val dist = hypot(
            (event.rawX - downX).toDouble(),
            (event.rawY - downY).toDouble()
        ).toFloat()
        if (dist >= moveSlopPx) {
            movedOffTap = true
            return true
        }
        return false
    }

    private fun releasePendingUp() {
        mainHandler.removeCallbacks(releaseFirstUpRunnable)
        val view = targetView
        val method = touchMethod
        val up = pendingUp
        pendingUp = null
        phase = Phase.IDLE
        firstUpElapsed = 0L
        if (view == null || method == null || up == null) {
            recycle(up)
            return
        }
        try {
            XposedBridge.invokeOriginalMethod(method, view, arrayOf(up))
            Logger.d(TAG) { "暂扣 UP 已放行" }
        } catch (e: Throwable) {
            Logger.e(TAG, "放行 UP 失败", e)
        } finally {
            recycle(up)
        }
    }

    /** 终止系统侧仍处按下/待点击的首次手势：发送 ACTION_CANCEL */
    private fun abortFirstGesture(view: View, x: Float, y: Float) {
        val method = touchMethod ?: return
        try {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now - 10, now, MotionEvent.ACTION_CANCEL, x, y, 0)
            try {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                cancel.setLocation(x - loc[0], y - loc[1])
            } catch (_: Throwable) {
            }
            XposedBridge.invokeOriginalMethod(method, view, arrayOf(cancel))
            cancel.recycle()
        } catch (e: Throwable) {
            Logger.w(TAG, "发送 CANCEL 失败: ${e.message}")
        }
        try {
            view.isPressed = false
        } catch (_: Throwable) {
        }
    }

    private fun clearWaitingState(abort: Boolean, view: View?) {
        mainHandler.removeCallbacks(releaseFirstUpRunnable)
        if (abort && view != null && phase == Phase.WAIT_SECOND) {
            abortFirstGesture(view, downX, downY)
        }
        recycle(pendingUp)
        pendingUp = null
        phase = Phase.IDLE
        firstUpElapsed = 0L
        movedOffTap = false
    }

    private fun recycle(event: MotionEvent?) {
        if (event == null) return
        try {
            event.recycle()
        } catch (_: Throwable) {
        }
    }

    private fun readBrightness(view: View): Float {
        // 与 Flyme BrightnessController.getBrightnessInfo() 同源（反编译对照）：
        // context/DisplayManager 的默认显示才有真实亮度信息；view.display 在状态栏
        // 渲染于虚拟显示/截屏显示时 getBrightnessInfo() 返回 null → 读取失败。
        val display = try {
            val dm = view.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(Display.DEFAULT_DISPLAY) ?: view.context.getDisplay() ?: view.display
        } catch (_: Throwable) {
            view.display
        }
        // 实际当前亮度：Flyme 亮度条（BrightnessController）同源，最可靠。
        // BrightnessInfo.brightness 是公开字段（无 getter），反射读字段而非方法。
        val fromInfo = if (display == null) {
            Logger.w(BRIGHTNESS_TAG, "当前亮度读取失败: display 为 null")
            null
        } else try {
            val info = XposedHelpers.callMethod(display, "getBrightnessInfo")
            if (info == null) {
                Logger.w(BRIGHTNESS_TAG, "当前亮度读取失败: getBrightnessInfo 返回 null")
                null
            } else {
                XposedHelpers.getFloatField(info, "brightness")
            }
        } catch (e: Throwable) {
            Logger.w(BRIGHTNESS_TAG, "当前亮度读取失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (fromInfo != null && fromInfo in 0f..1f) {
            Logger.i(BRIGHTNESS_TAG, "当前亮度 info=$fromInfo")
            return fromInfo
        }
        // 兜底：手动设定亮度（0-255）；Flyme 上自动亮度时可能为 0，仅作后备
        val fromSettings = try {
            android.provider.Settings.System.getInt(
                view.context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS
            ).toFloat() / 255f
        } catch (e: Throwable) {
            Logger.w(BRIGHTNESS_TAG, "Settings.SCREEN_BRIGHTNESS 读取失败: ${e.javaClass.simpleName}: ${e.message}")
            -1f
        }
        if (fromSettings in 0f..1f) {
            Logger.i(BRIGHTNESS_TAG, "当前亮度 settings=$fromSettings")
            return fromSettings
        }
        Logger.w(BRIGHTNESS_TAG, "当前亮度读取失败，用 0.5 兜底")
        return 0.5f
    }

    /** 滑动量 = ΔX / 状态栏宽，实时预览 */
    private fun applyBrightness(view: View, event: MotionEvent) {
        try {
            val width = if (view.width > 0) view.width
            else view.resources.displayMetrics.widthPixels
            val ratio = (event.rawX - downX) / width
            val value = (startBrightness + ratio).coerceIn(0f, 1f)
            currentBrightness = value
            val dm = view.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            // @SystemApi 隐藏方法，走反射
            XposedHelpers.callMethod(dm, "setTemporaryBrightness", displayId, value)
            Logger.d(BRIGHTNESS_TAG) { "调亮度 w=$width downX=$downX rawX=${event.rawX} ratio=$ratio value=$value" }
        } catch (e: Throwable) {
            Logger.e(TAG, "实时调亮度失败", e)
        }
    }

    /** 松手持久化 */
    private fun commitBrightness(view: View) {
        try {
            val dm = view.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            XposedHelpers.callMethod(dm, "setBrightness", displayId, currentBrightness)
            Logger.i(BRIGHTNESS_TAG, "亮度已设置 ${(currentBrightness * 255).toInt()}/255")
        } catch (e: Throwable) {
            Logger.e(TAG, "持久化亮度失败", e)
        }
    }

    private fun performLockScreen(view: View) {
        val context = view.context.applicationContext ?: view.context
        mainHandler.post {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis())
                Logger.i(TAG, "已息屏锁屏 (PowerManager.goToSleep)")
            } catch (e: Throwable) {
                Logger.e(TAG, "息屏锁屏失败", e)
            }
        }
    }

    private fun playHaptic(context: Context) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(25L, 120))
        } catch (_: Throwable) {
        }
    }
}
