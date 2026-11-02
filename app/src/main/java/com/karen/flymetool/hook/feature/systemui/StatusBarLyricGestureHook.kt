package com.karen.flymetool.hook.feature.systemui

import android.app.PendingIntent
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 状态栏歌词手势：点击歌词跳转播放器、左右滑动切换歌曲。
 *
 * 歌词与 AOD 歌词同源（见 AODLyricHook）：音乐播放器的常驻媒体通知带 tickerText 时，
 * AdvertTickerView（status_bar_ticker_ext 的 ticker_ext）会顶替状态栏左侧内容滚动显示歌词。
 * 两个功能共用一份触摸状态机、各自独立开关，避免同一条触摸事件被多个 hook 重复消费：
 * - 点击：打开当前歌词对应播放器的界面（优先通知 contentIntent，其次媒体会话界面）。
 * - 横滑：左滑下一首、右滑上一首，通过 MediaSession 的 TransportControls 发送。
 *
 * 挂载点：StatusBarWindowView.dispatchTouchEvent —— 状态栏窗口根 View，先于
 * PhoneStatusBarView 及其子 View 收到事件，因此可以在「双击状态栏锁屏」「滑动状态栏调亮度」
 * 等状态栏手势之前判定歌词区域；只有确认是歌词手势才消费事件，纵向拖动照常交给系统下拉。
 */
object StatusBarLyricGestureHook : FeatureHook {

    private const val TAG = "StatusBarLyricGesture"
    private const val KEY_CLICK = "statusbar_lyric_click"
    private const val KEY_SWIPE = "statusbar_lyric_swipe"

    /** Flyme 12 起歌词与状态栏窗口移入 systemui 子包，旧版沿用原包名 */
    private const val TICKER_VIEW = "com.flyme.systemui.statusbar.ticker.AdvertTickerView"
    private const val TICKER_VIEW_LEGACY = "com.flyme.statusbar.ticker.AdvertTickerView"
    private const val WINDOW_VIEW = "com.android.systemui.statusbar.window.StatusBarWindowView"
    private const val WINDOW_VIEW_LEGACY = "com.android.systemui.statusbar.phone.StatusBarWindowView"
    private const val PHONE_STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView"

    /** 接管横滑的最小横向位移（dp）；与「滑动状态栏调亮度」同量级，确保先于其判定 */
    private const val CLAIM_DISTANCE_DP = 16f

    /** 触发切歌的横向位移（dp） */
    private const val SWIPE_DISTANCE_DP = 40f

    /** 横向须明显大于纵向，避免把斜向拖动判成切歌 */
    private const val HORIZONTAL_DOMINANCE = 2f

    /** 点按最长按住时长 */
    private const val TAP_MAX_HOLD_MS = 500L

    private var clickEnabled = false
    private var swipeEnabled = false

    /** 最近一次真正显示出来的歌词视图与通知，由 AdvertTickerView.addNotification 维护 */
    @Volatile
    private var tickerView: View? = null

    @Volatile
    private var tickerSbn: StatusBarNotification? = null

    private var tracking = false
    private var claimed = false
    private var swipeTriggered = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var touchSlop = 0f
    private var claimDistance = 0f
    private var swipeDistance = 0f

    private var activeView: View? = null
    private var activeState: LyricState? = null

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.android.systemui") return
        clickEnabled = ctx.featureEnabled(KEY_CLICK)
        swipeEnabled = ctx.featureEnabled(KEY_SWIPE)
        if (!clickEnabled && !swipeEnabled) return

        val tickerClass = findClass(ctx, TICKER_VIEW, TICKER_VIEW_LEGACY)
        if (tickerClass == null) {
            Logger.w(TAG, "未找到状态栏歌词组件，跳过")
            return
        }
        hookLyricNotification(ctx, tickerClass)
        hookStatusBarTouch(ctx)
    }

    // ---------- 歌词来源 ----------

    /** 记录当前显示的歌词通知：addNotification 返回 true 表示这条通知真的显示在状态栏上了 */
    private fun hookLyricNotification(ctx: HookContext, tickerClass: Class<*>) {
        try {
            Reflect.hookMethodOn(
                ctx.api,
                tickerClass,
                "addNotification",
                StatusBarNotification::class.java,
            ) { chain ->
                val shown = chain.proceed()
                try {
                    val view = chain.getThisObject() as? View
                    val sbn = chain.getArg(0) as? StatusBarNotification
                    if (view != null && sbn != null) {
                        if (shown == true) {
                            tickerView = view
                            tickerSbn = sbn
                        } else if (tickerSbn?.key == sbn.key) {
                            // 同一条通知不再作为歌词显示（播放结束 / 被清除）
                            tickerSbn = null
                        }
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "记录歌词通知失败", e)
                }
                shown
            }
            Logger.i(TAG, "已挂载歌词通知回调: ${tickerClass.name}")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载歌词通知回调失败", e, "target" to tickerClass.name)
        }
    }

    // ---------- 手势 ----------

    private fun hookStatusBarTouch(ctx: HookContext) {
        val windowClass = findClass(ctx, WINDOW_VIEW, WINDOW_VIEW_LEGACY)
            ?: findClass(ctx, PHONE_STATUS_BAR_VIEW, PHONE_STATUS_BAR_VIEW)
        if (windowClass == null) {
            Logger.w(TAG, "未找到状态栏触摸入口，跳过")
            return
        }
        try {
            Reflect.hookMethodOn(
                ctx.api,
                windowClass,
                "dispatchTouchEvent",
                MotionEvent::class.java,
            ) { chain ->
                val event = chain.getArg(0) as? MotionEvent ?: return@hookMethodOn chain.proceed()
                val view = chain.getThisObject() as? View ?: return@hookMethodOn chain.proceed()
                val result = try {
                    handleTouch(ctx, view, event)
                } catch (e: Throwable) {
                    resetGesture()
                    Logger.e(TAG, "处理歌词手势失败", e)
                    TouchResult.PASS
                }
                when (result) {
                    TouchResult.PASS -> chain.proceed()
                    TouchResult.CONSUME -> true
                    TouchResult.CONSUME_CANCEL -> {
                        sendCancel(chain, event)
                        true
                    }
                }
            }
            Logger.i(
                TAG,
                "已挂载状态栏触摸拦截: ${windowClass.name}，点击=$clickEnabled 横滑=$swipeEnabled",
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载状态栏触摸拦截失败", e, "target" to windowClass.name)
        }
    }

    /** @return 当前事件的处理方式 */
    private enum class TouchResult {
        /** 放行给系统 */
        PASS,
        /** 消费当前事件 */
        CONSUME,
        /** 先向系统补发 CANCEL 终止其已经开始的按下手势，再消费当前事件 */
        CONSUME_CANCEL,
    }

    /** @return true = 已消费，跳过系统 dispatchTouchEvent */
    private fun handleTouch(ctx: HookContext, window: View, event: MotionEvent): TouchResult {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetGesture()
                val lyric = tickerView
                if (lyric == null || !lyric.isShown) return TouchResult.PASS
                if (!isInside(lyric, event.rawX, event.rawY)) return TouchResult.PASS
                val state = readLyricState(lyric) ?: return TouchResult.PASS

                ensureMetrics(window.context)
                tracking = true
                activeView = lyric
                activeState = state
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
                Logger.d(TAG) { "歌词手势开始 pkg=${state.pkg}" }
                return TouchResult.PASS
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return TouchResult.PASS
                if (event.pointerCount > 1) {
                    resetGesture()
                    return TouchResult.PASS
                }
                if (swipeTriggered) return TouchResult.CONSUME

                val dx = event.rawX - downX
                val dy = event.rawY - downY

                if (!swipeEnabled) {
                    // 只做点击：移动超出点按容差就放弃这次手势，交还系统
                    if (hypot(dx, dy) > touchSlop) tracking = false
                    return TouchResult.PASS
                }

                if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    // 纵向拖动（下拉通知栏等）交给系统
                    tracking = false
                    return TouchResult.PASS
                }

                if (!claimed) {
                    if (abs(dx) < claimDistance || abs(dx) < abs(dy) * HORIZONTAL_DOMINANCE) {
                        return TouchResult.PASS
                    }
                    claimed = true
                    Logger.d(TAG) { "接管歌词横滑 dx=$dx dy=$dy" }
                    if (abs(dx) >= swipeDistance) {
                        swipeTriggered = true
                        switchTrack(window.context, activeState, next = dx < 0)
                    }
                    // 第一个被接管的事件：终止系统侧已经开始的按下手势
                    return TouchResult.CONSUME_CANCEL
                }

                if (!swipeTriggered && abs(dx) >= swipeDistance) {
                    swipeTriggered = true
                    switchTrack(window.context, activeState, next = dx < 0)
                }
                return TouchResult.CONSUME
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return TouchResult.PASS
                val wasSwipe = swipeTriggered
                val wasClaimed = claimed
                val held = event.eventTime - downTime
                val moved = hypot(event.rawX - downX, event.rawY - downY)
                val state = activeState
                val lyric = activeView
                resetGesture()

                if (wasSwipe || wasClaimed) return TouchResult.CONSUME
                if (clickEnabled && state != null && lyric != null && lyric.isShown &&
                    held <= TAP_MAX_HOLD_MS && moved <= touchSlop
                ) {
                    openPlayer(ctx, window.context, state)
                    return TouchResult.CONSUME_CANCEL
                }
                return TouchResult.PASS
            }

            MotionEvent.ACTION_CANCEL -> {
                resetGesture()
                return TouchResult.PASS
            }
        }
        return TouchResult.PASS
    }

    /** 向系统补发 CANCEL：终止状态栏自身的按下手势（长按检测、tracking 状态），避免长按延迟触发 */
    private fun sendCancel(chain: io.github.libxposed.api.XposedInterface.Chain, event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            chain.proceed(arrayOf<Any>(cancel))
        } catch (e: Throwable) {
            Logger.w(TAG, "补发 CANCEL 失败", "error" to e)
        } finally {
            cancel.recycle()
        }
    }

    /** 重置手势状态，不修改歌词来源记录 */
    private fun resetGesture() {
        tracking = false
        claimed = false
        swipeTriggered = false
        activeView = null
        activeState = null
    }

    private fun ensureMetrics(context: Context) {
        if (touchSlop > 0f) return
        val density = context.resources.displayMetrics.density
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        claimDistance = CLAIM_DISTANCE_DP * density
        swipeDistance = SWIPE_DISTANCE_DP * density
    }

    private fun isInside(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
    }

    /**
     * 读取当前歌词状态；歌词视图只在有 tickerText 时显示，文字为空说明没有歌词。
     *
     * @return null 表示当前不是歌词（或字段不可读），此时不接管手势
     */
    private fun readLyricState(view: View): LyricState? {
        val text = try {
            Reflect.getObjectField(view, "mTickerText") as? CharSequence
        } catch (e: Throwable) {
            Logger.once(TAG, "tickerText", "读取歌词文字失败，跳过歌词手势", "error" to e)
            return null
        }
        if (text.isNullOrEmpty()) return null

        val key = try {
            Reflect.getObjectField(view, "mCurrentKey") as? String
        } catch (_: Throwable) {
            null
        }
        val pkg = try {
            Reflect.getObjectField(view, "mCurrentPkg") as? String
        } catch (_: Throwable) {
            null
        }
        // 通知已更新到别的应用时不再使用旧记录
        val sbn = tickerSbn?.takeIf { key == null || it.key == key }
        return LyricState(pkg, sbn)
    }

    // ---------- 与状态栏其它手势的优先级 ----------

    /**
     * 该触点是否落在歌词手势区域。
     *
     * 横滑切歌与「滑动状态栏调亮度」都是状态栏横滑，歌词显示时以切歌为准：
     * StatusBarDoubleClickHook 在 ACTION_DOWN 命中这里时整体跳过本次手势，
     * 既不会改亮度也不会参与双击锁屏；状态栏其余位置两者照旧。
     *
     * 只在「横滑切歌」开启时让位，只开点击时歌词区域的亮度滑动不受影响。
     */
    internal fun isLyricGestureArea(rawX: Float, rawY: Float): Boolean {
        if (!swipeEnabled) return false
        val view = tickerView ?: return false
        return try {
            view.isShown && isInside(view, rawX, rawY) && readLyricState(view) != null
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 点击跳转播放器 ----------

    private fun openPlayer(ctx: HookContext, context: Context, state: LyricState) {
        val pkg = state.pkg ?: state.sbn?.packageName
        val pendingIntent = state.sbn?.notification?.contentIntent ?: sessionActivity(context, pkg)
        Logger.i(TAG, "点击歌词，打开播放器界面: ${pkg ?: "unknown"}")
        NotificationLauncher.open(ctx, context, pkg, pendingIntent, TAG)
    }

    /** 播放器没有可用 contentIntent 时的兜底：媒体会话声明的会话界面 */
    private fun sessionActivity(context: Context, pkg: String?): PendingIntent? {
        val controller = findController(context, pkg) ?: return null
        return try {
            controller.sessionActivity
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- 横滑切歌 ----------

    private fun switchTrack(context: Context, state: LyricState?, next: Boolean) {
        val pkg = state?.pkg ?: state?.sbn?.packageName
        val controller = findController(context, pkg)
        if (controller == null) {
            Logger.w(TAG, "没有可控制的媒体会话，忽略切歌", "pkg" to (pkg ?: "unknown"))
            return
        }
        try {
            val action = if (next) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
            val playback = controller.playbackState
            if (playback != null && (playback.actions and action) != 0L) {
                if (next) controller.transportControls.skipToNext() else controller.transportControls.skipToPrevious()
            } else {
                // 播放器没在 PlaybackState 里声明切歌能力时退回媒体按键，兼容更多播放器
                val keyCode = if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS
                controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            Logger.i(TAG, if (next) "歌词左滑：下一首（${controller.packageName}）" else "歌词右滑：上一首（${controller.packageName}）")
        } catch (e: Throwable) {
            Logger.e(TAG, "切歌失败", e, "next" to next)
        }
    }

    private fun findController(context: Context, pkg: String?): MediaController? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (manager == null) {
            Logger.w(TAG, "MediaSessionManager 不可用")
            return null
        }
        val sessions = try {
            manager.getActiveSessions(null)
        } catch (e: Throwable) {
            Logger.once(TAG, "sessions", "读取活动媒体会话失败", "error" to e)
            return null
        }
        sessions.firstOrNull { pkg != null && it.packageName == pkg }?.let { return it }
        // 歌词通知的包名与实际播放器不一致（歌词转发类应用）时，退而选择唯一正在播放的会话
        val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        return playing.singleOrNull() ?: sessions.singleOrNull()
    }

    private fun findClass(ctx: HookContext, name: String, legacyName: String): Class<*>? {
        for (candidate in arrayOf(name, legacyName)) {
            try {
                return Reflect.findClass(candidate, ctx.classLoader)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** 当前歌词对应的包名与通知 */
    private class LyricState(val pkg: String?, val sbn: StatusBarNotification?)
}
