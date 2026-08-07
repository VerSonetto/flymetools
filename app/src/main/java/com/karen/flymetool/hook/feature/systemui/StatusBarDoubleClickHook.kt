package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
import kotlin.math.hypot

/**
 * 双击状态栏任意位置息屏锁屏。
 *
 * 挂载点：PhoneStatusBarView.dispatchTouchEvent —— 状态栏根 View，无论点击落在哪个子
 * View（时钟/通知图标/系统图标/空白区），父 View 的 dispatchTouchEvent 均收到每个事件，
 * 覆盖整个状态栏。
 *
 * 双击状态机与 MBackDoubleClickHook 一致：第一击 DOWN 放行、干净 UP 暂扣，
 * 窗口内第二击吞掉并触发锁屏（PowerManager.goToSleep）。息屏后系统对所有窗口补发
 * CANCEL 终止触摸序列，状态栏 touch handler 自动复位，无需手动清理。
 */
object StatusBarDoubleClickHook : FeatureHook {

    private const val TAG = "StatusBarDblClick"
    private const val FEATURE_KEY = "status_bar_double_click_lock"

    private const val VIEW_CLASS = "com.android.systemui.statusbar.phone.PhoneStatusBarView"

    /** 干净点按抬起后，等多久才确认是单击并放行 UP */
    private const val DOUBLE_TAP_WINDOW_MS = 200L

    /** 按下超过此时长再抬起，不当作双击候选，UP 立刻放行 */
    private const val TAP_MAX_HOLD_MS = 180L

    private const val MOVE_SLOP_DP = 12f

    private val hooked = AtomicBoolean(false)

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
        if (lpparam.packageName != "com.android.systemui") return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return
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
            Logger.i(TAG, "已挂载 $VIEW_CLASS.dispatchTouchEvent，窗口=${DOUBLE_TAP_WINDOW_MS}ms")
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
                // 不 return true：DOWN 交给系统，下拉/按压零延迟
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                when (phase) {
                    Phase.FIRST_TRACKING -> {
                        isMovedOff(event)
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
                    Phase.SECOND_TRACKING -> {
                        val ok = !movedOffTap && !isMovedOff(event)
                        phase = Phase.IDLE
                        firstUpElapsed = 0L
                        if (ok) {
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
            try {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                cancel.setLocation(downX - loc[0], downY - loc[1])
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
            abortFirstGesture(view)
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
