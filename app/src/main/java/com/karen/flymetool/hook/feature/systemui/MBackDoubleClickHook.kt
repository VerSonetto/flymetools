package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * 底部手势条双击：DOWN 即时放行，干净 UP 短暂扣；窗口内第二击取消返回并执行动作。
 */
object MBackDoubleClickHook : FeatureHook {

    private const val TAG = "MBackDoubleClick"
    private const val FEATURE_KEY = "mback_double_click"
    private const val ACTION_FLASHLIGHT = "flashlight"
    private const val ACTION_SCREENSHOT = "screenshot"
    private const val ACTION_SLEEP = "sleep"
    private const val ACTION_MUTE = "mute"
    private const val DEFAULT_ACTION = ACTION_FLASHLIGHT

    private const val VIEW_CLASS = "com.flyme.systemui.navigationbar.MBackButtonView"
    private const val LONG_TOUCH_HELPER =
        "com.flyme.systemui.navigationbar.actions.NavBarMBackLongTouchHelper"

    /** 干净点按抬起后，等多久才确认是单击并放行 UP（短于系统默认 ~300ms） */
    private const val DOUBLE_TAP_WINDOW_MS = 200L

    /** 按下超过此时长再抬起，不当作双击候选，UP 立刻放行 */
    private const val TAP_MAX_HOLD_MS = 180L

    private const val MOVE_SLOP_DP = 12f
    private const val PREFS_TTL_MS = 800L

    private val hooked = AtomicBoolean(false)

    @Volatile
    private var action: String = DEFAULT_ACTION

    private var prefsCachedAt = 0L
    private var loadParam: XC_LoadPackage.LoadPackageParam? = null
    private var classLoader: ClassLoader? = null
    private var touchMethod: Method? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private enum class Phase {
        IDLE,
        /** 第一击进行中，DOWN 已交给系统 */
        FIRST_TRACKING,
        /** 第一击干净 UP 被暂扣，等待第二击 */
        WAIT_SECOND,
        /** 第二击进行中，整段吞掉 */
        SECOND_TRACKING,
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

    private val releaseFirstUpRunnable = Runnable {
        if (phase != Phase.WAIT_SECOND) return@Runnable
        Logger.d(TAG) { "单击确认，放行暂扣的 UP" }
        releasePendingUp()
    }

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
        if (lpparam.packageName != "com.android.systemui") return
        if (!hooked.compareAndSet(false, true)) return

        loadParam = lpparam
        classLoader = lpparam.classLoader
        refreshPrefs(force = true)

        try {
            val viewClass = XposedHelpers.findClass(VIEW_CLASS, lpparam.classLoader)
            val method = viewClass.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
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
            Logger.i(TAG, "已挂载 $VIEW_CLASS.onTouchEvent，动作=$action，窗口=${DOUBLE_TAP_WINDOW_MS}ms")
        } catch (e: Throwable) {
            hooked.set(false)
            Logger.e(TAG, "挂载失败", e)
        }
    }

    private fun refreshPrefs(force: Boolean = false) {
        val lp = loadParam ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - prefsCachedAt < PREFS_TTL_MS) return
        prefsCachedAt = now
        action = XposedPrefs.getFeatureString(
            lp, "com.android.systemui", FEATURE_KEY, DEFAULT_ACTION
        ).ifBlank { DEFAULT_ACTION }
    }

    /** @return true = 已消费，跳过系统 onTouchEvent */
    private fun onTouch(view: View, event: MotionEvent): Boolean {
        refreshPrefs()
        ensureMetrics(view)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 双击第二击：取消暂扣的返回，吞掉第二击
                if (phase == Phase.WAIT_SECOND &&
                    firstUpElapsed > 0L &&
                    SystemClock.elapsedRealtime() - firstUpElapsed <= DOUBLE_TAP_WINDOW_MS
                ) {
                    mainHandler.removeCallbacks(releaseFirstUpRunnable)
                    abortFirstGesture(view)
                    recycle(pendingUp)
                    pendingUp = null

                    phase = Phase.SECOND_TRACKING
                    targetView = view
                    downElapsed = SystemClock.elapsedRealtime()
                    downX = event.rawX
                    downY = event.rawY
                    movedOffTap = false
                    Logger.d(TAG) { "双击第二击 DOWN，已取消第一击返回" }
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
                // 不 return true：DOWN 交给系统，按压/涟漪零延迟
                Logger.d(TAG) { "第一击 DOWN 放行" }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                when (phase) {
                    Phase.FIRST_TRACKING -> {
                        if (isMovedOff(event)) {
                            // 已偏离点按：后续不再暂扣 UP
                            Logger.d(TAG) { "第一击滑动，后续 UP 直接放行" }
                        }
                        return false
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
                        if (!isCleanTap) {
                            phase = Phase.IDLE
                            Logger.d(TAG) { "第一击非干净点按，UP 放行 held=$held" }
                            return false
                        }
                        // 暂扣 UP：窗口内无第二击再放行 → 返回
                        recycle(pendingUp)
                        pendingUp = MotionEvent.obtain(event)
                        phase = Phase.WAIT_SECOND
                        firstUpElapsed = SystemClock.elapsedRealtime()
                        mainHandler.removeCallbacks(releaseFirstUpRunnable)
                        mainHandler.postDelayed(releaseFirstUpRunnable, DOUBLE_TAP_WINDOW_MS)
                        Logger.d(TAG) { "第一击 UP 暂扣 ${DOUBLE_TAP_WINDOW_MS}ms" }
                        return true
                    }
                    Phase.SECOND_TRACKING -> {
                        val ok = !movedOffTap && !isMovedOff(event)
                        phase = Phase.IDLE
                        firstUpElapsed = 0L
                        if (ok) {
                            Logger.i(TAG, "双击触发 action=$action")
                            cancelSystemSideEffects(view)
                            performAction(view.context.applicationContext ?: view.context)
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
                    else -> return false
                }
            }
        }
        return false
    }

    private fun ensureMetrics(view: View) {
        try {
            moveSlopPx = MOVE_SLOP_DP * view.resources.displayMetrics.density
            // 窗口用固定短值；系统 doubleTapTimeout 往往 300ms，单击会慢
            ViewConfiguration.get(view.context)
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
            invokeOriginal(method, view, up)
            Logger.d(TAG) { "暂扣 UP 已放行" }
        } catch (e: Throwable) {
            Logger.e(TAG, "放行 UP 失败", e)
        } finally {
            recycle(up)
        }
    }

    /** 第二击到来：取消系统侧仍处按下/待点击的第一击 */
    private fun abortFirstGesture(view: View) {
        val method = touchMethod ?: return
        try {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(
                now - 10,
                now,
                MotionEvent.ACTION_CANCEL,
                downX,
                downY,
                0
            )
            // 尽量用 view 本地坐标
            try {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                cancel.setLocation(downX - loc[0], downY - loc[1])
            } catch (_: Throwable) {
            }
            invokeOriginal(method, view, cancel)
            cancel.recycle()
        } catch (e: Throwable) {
            Logger.w(TAG, "发送 CANCEL 失败: ${e.message}")
        }
        cancelSystemSideEffects(view)
        try {
            view.isPressed = false
        } catch (_: Throwable) {
        }
    }

    private fun clearWaitingState(abort: Boolean, view: View?) {
        mainHandler.removeCallbacks(releaseFirstUpRunnable)
        if (abort && view != null && phase == Phase.WAIT_SECOND) {
            // 有未放行 UP：补 CANCEL 结束系统按下态
            abortFirstGesture(view)
        }
        recycle(pendingUp)
        pendingUp = null
        phase = Phase.IDLE
        firstUpElapsed = 0L
        movedOffTap = false
    }

    private fun invokeOriginal(method: Method, view: View, event: MotionEvent) {
        XposedBridge.invokeOriginalMethod(method, view, arrayOf(event))
    }

    private fun recycle(event: MotionEvent?) {
        if (event == null) return
        try {
            event.recycle()
        } catch (_: Throwable) {
        }
    }

    private fun cancelSystemSideEffects(view: View) {
        try {
            val helper = XposedHelpers.findClass(LONG_TOUCH_HELPER, classLoader)
            XposedHelpers.callStaticMethod(helper, "cancelAnyPendingLongTouch")
        } catch (_: Throwable) {
        }
        try {
            val controller = XposedHelpers.getObjectField(view, "mMBackButtonController")
            val handler = XposedHelpers.getObjectField(controller, "mHandler") as? Handler
            handler?.removeCallbacksAndMessages(null)
            XposedHelpers.setBooleanField(controller, "mTouchEventDown", false)
            XposedHelpers.setBooleanField(controller, "mIsLongClick", false)
            XposedHelpers.setIntField(controller, "mTouchFlag", 0)
        } catch (_: Throwable) {
        }
    }

    private fun performAction(context: Context) {
        when (action) {
            ACTION_FLASHLIGHT -> toggleFlashlight(context)
            ACTION_SCREENSHOT -> takeScreenshot(context)
            ACTION_SLEEP -> goToSleep(context)
            ACTION_MUTE -> toggleMute(context)
            else -> {
                Logger.w(TAG, "未知动作 $action，回退手电筒")
                toggleFlashlight(context)
            }
        }
    }

    /**
     * 切换系统铃声模式：静音 ↔ 响铃。
     * 若当前为震动，则进入静音（与状态栏/快捷开关「静音」一致）。
     */
    private fun toggleMute(context: Context) {
        mainHandler.post {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val current = am.ringerMode
                if (current == AudioManager.RINGER_MODE_SILENT) {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    Logger.i(TAG, "已取消静音 (RINGER_MODE_NORMAL)")
                } else {
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT
                    Logger.i(TAG, "已静音 (RINGER_MODE_SILENT)，原模式=$current")
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "切换静音失败", e)
            }
        }
    }

    private fun goToSleep(context: Context) {
        mainHandler.post {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis())
                Logger.i(TAG, "已息屏 (PowerManager.goToSleep)")
            } catch (e: Throwable) {
                Logger.e(TAG, "息屏失败", e)
            }
        }
    }

    private fun takeScreenshot(context: Context) {
        mainHandler.post {
            try {
                val helperClass = XposedHelpers.findClass(
                    "com.android.internal.util.ScreenshotHelper",
                    classLoader ?: context.classLoader
                )
                val helper = XposedHelpers.newInstance(helperClass, context)
                val method = helperClass.getMethod(
                    "takeScreenshot",
                    Int::class.javaPrimitiveType,
                    Handler::class.java,
                    java.util.function.Consumer::class.java
                )
                method.invoke(helper, 4, mainHandler, null)
                Logger.i(TAG, "已触发截图 (ScreenshotHelper)")
            } catch (e: Throwable) {
                Logger.w(TAG, "ScreenshotHelper 失败: ${e.message}，尝试按键注入")
                injectSysrqKey()
            }
        }
    }

    private fun injectSysrqKey() {
        try {
            val inputManagerClass = XposedHelpers.findClass(
                "android.hardware.input.InputManager",
                classLoader
            )
            val im = XposedHelpers.callStaticMethod(inputManagerClass, "getInstance")
            val now = SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(
                now, now, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_SYSRQ, 0
            )
            val up = android.view.KeyEvent(
                now, now + 10, android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_SYSRQ, 0
            )
            XposedHelpers.callMethod(im, "injectInputEvent", down, 0)
            XposedHelpers.callMethod(im, "injectInputEvent", up, 0)
            Logger.i(TAG, "已触发截图 (KEYCODE_SYSRQ)")
        } catch (e: Throwable) {
            Logger.e(TAG, "截图失败", e)
        }
    }

    private fun toggleFlashlight(context: Context) {
        if (toggleFlashlightViaDependency()) return
        toggleFlashlightViaCameraManager(context)
    }

    private fun toggleFlashlightViaDependency(): Boolean {
        val cl = classLoader ?: return false
        return try {
            val dependencyClass = XposedHelpers.findClass(
                "com.android.systemui.Dependency", cl
            )
            val flashlightClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.FlashlightController", cl
            )
            val controller = XposedHelpers.callStaticMethod(
                dependencyClass, "get", flashlightClass
            ) ?: return false

            try {
                XposedHelpers.callMethod(controller, "reverseFlashLight")
                Logger.i(TAG, "手电筒已切换 (reverseFlashLight)")
                return true
            } catch (_: Throwable) {
            }

            val enabled = try {
                XposedHelpers.callMethod(controller, "isEnabled") as Boolean
            } catch (_: Throwable) {
                false
            }
            XposedHelpers.callMethod(controller, "setFlashlight", !enabled)
            Logger.i(TAG, "手电筒已切换 (setFlashlight ${!enabled})")
            true
        } catch (e: Throwable) {
            Logger.w(TAG, "FlashlightController 不可用: ${e.message}")
            false
        }
    }

    private fun toggleFlashlightViaCameraManager(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run {
                Logger.w(TAG, "无可用闪光灯相机")
                return
            }
            val sp = context.getSharedPreferences("flymetool_mback", Context.MODE_PRIVATE)
            val on = sp.getBoolean("torch_on", false)
            cm.setTorchMode(id, !on)
            sp.edit().putBoolean("torch_on", !on).apply()
            Logger.i(TAG, "手电筒已切换 (CameraManager -> ${!on})")
        } catch (e: Throwable) {
            Logger.e(TAG, "CameraManager 切换手电筒失败", e)
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
