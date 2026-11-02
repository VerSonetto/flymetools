package com.karen.flymetool.hook.feature.systemui

import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageSwitcher
import android.widget.TextSwitcher
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import io.github.libxposed.api.XposedInterface

object TickerClickHook : FeatureHook {

    /** Flyme 12 起 ticker 移入 systemui 子包；旧版在 statusbar.ticker（与 AppIconNotificationHook 同源兼容） */
    private const val MARQUEE_TICKER = "com.flyme.systemui.statusbar.ticker.MarqueeTicker"
    private const val MARQUEE_TICKER_LEGACY = "com.flyme.statusbar.ticker.MarqueeTicker"
    private const val PHONE_STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
    private const val TAG = "TickerClick"
    private const val MAX_TAP_DURATION_MS = 500L

    /** 当前显示的 ticker；ticker 结束后 mTickerView 为 GONE，判定自动失效 */
    private var activeTicker: Any? = null

    private var tapDownRawX = 0f
    private var tapDownRawY = 0f
    private var tapDownTime = 0L
    private var isTapCandidate = false
    private var touchSlop = -1

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("ticker_click")) return
        if (ctx.packageName != "com.android.systemui") return

        hookMarqueeTicker(ctx)
    }

    private fun findMarqueeTickerClass(ctx: HookContext): Class<*>? {
        return try {
            Reflect.findClass(MARQUEE_TICKER, ctx.classLoader)
        } catch (_: Throwable) {
            try {
                Reflect.findClass(MARQUEE_TICKER_LEGACY, ctx.classLoader)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun hookMarqueeTicker(ctx: HookContext) {
        val clazz = findMarqueeTickerClass(ctx)
        if (clazz == null) {
            Logger.e(TAG, "未找到 MarqueeTicker 类（新旧包名均不存在）")
            return
        }

        // tap 判定上移到 PhoneStatusBarView.onTouchEvent 层，滚动期间可下拉；挂载失败降级 clickable 旧机制
        val tapInterceptHooked = hookStatusBarTouchTapIntercept(ctx)

        try {
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "addEntry",
                StatusBarNotification::class.java,
            ) { chain ->
                val result = chain.proceed()
                if (tapInterceptHooked) {
                    rememberTicker(chain.getThisObject())
                } else {
                    setupClickListener(chain.getThisObject(), ctx)
                }
                result
            }

            Logger.i(TAG, "已挂载 MarqueeTicker.addEntry（${if (tapInterceptHooked) "tap 拦截" else "clickable 降级"}）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MarqueeTicker.addEntry 失败", e)
            tryAlternativeHook(clazz, ctx, tapInterceptHooked)
        }
    }

    private fun tryAlternativeHook(clazz: Class<*>, ctx: HookContext, tapInterceptHooked: Boolean) {
        try {
            Reflect.hookConstructorOn(
                ctx.api,
                clazz,
                android.content.Context::class.java,
                android.view.View::class.java,
            ) { chain ->
                val result = chain.proceed()
                if (tapInterceptHooked) {
                    rememberTicker(chain.getThisObject())
                } else {
                    setupClickListener(chain.getThisObject(), ctx)
                }
                result
            }

            Logger.i(TAG, "已挂载 MarqueeTicker 构造器（回退方案）")
        } catch (e: Throwable) {
            Logger.e(TAG, "回退方案挂载也失败", e)
        }
    }

    /** ticker 可见期间拦截 tap 跳转，拖动/长按放行；@return 是否挂载成功 */
    private fun hookStatusBarTouchTapIntercept(ctx: HookContext): Boolean {
        return try {
            Reflect.hookMethodOn(
                ctx.api,
                Reflect.findClass(PHONE_STATUS_BAR_VIEW, ctx.classLoader),
                "onTouchEvent",
                MotionEvent::class.java,
            ) { chain ->
                val event = chain.getArg(0) as? MotionEvent ?: return@hookMethodOn chain.proceed()
                val ticker = activeTicker ?: return@hookMethodOn chain.proceed()
                val tickerView = try {
                    Reflect.getObjectField(ticker, "mTickerView") as? View
                } catch (_: Throwable) {
                    null
                } ?: return@hookMethodOn chain.proceed()
                if (tickerView.visibility != View.VISIBLE) return@hookMethodOn chain.proceed()

                var intercept = false

                fun movedBeyondSlop(ev: MotionEvent): Boolean {
                    if (touchSlop < 0) {
                        val view = chain.getThisObject() as? View ?: return true
                        touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
                    }
                    return Math.abs(ev.rawX - tapDownRawX) > touchSlop ||
                        Math.abs(ev.rawY - tapDownRawY) > touchSlop
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        tapDownRawX = event.rawX
                        tapDownRawY = event.rawY
                        tapDownTime = event.eventTime
                        isTapCandidate = true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (isTapCandidate && movedBeyondSlop(event)) {
                            isTapCandidate = false
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (isTapCandidate &&
                            !movedBeyondSlop(event) &&
                            event.eventTime - tapDownTime <= MAX_TAP_DURATION_MS
                        ) {
                            intercept = true
                            handleTickerClick(ticker, ctx)
                        }
                        isTapCandidate = false
                    }

                    MotionEvent.ACTION_CANCEL -> isTapCandidate = false
                }

                if (intercept) true else chain.proceed()
            }
            Logger.i(TAG, "已挂载 PhoneStatusBarView.onTouchEvent tap 拦截（滚动消息期间可正常下拉）")
            true
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 PhoneStatusBarView.onTouchEvent 失败，降级 clickable 机制", e)
            false
        }
    }

    private fun rememberTicker(ticker: Any) {
        activeTicker = ticker
    }

    /** 降级方案：clickable 捕获点击（跳转可用，滚动期间无法下拉） */
    private fun setupClickListener(ticker: Any, ctx: HookContext) {
        try {
            val textSwitcher = Reflect.getObjectField(ticker, "mTextSwitcher") as? TextSwitcher
            val iconSwitcher = Reflect.getObjectField(ticker, "mIconSwitcher") as? ImageSwitcher

            if (textSwitcher == null || iconSwitcher == null) {
                Logger.w(TAG, "未找到 Switcher 视图")
                return
            }

            val clickListener = View.OnClickListener {
                handleTickerClick(ticker, ctx)
            }

            textSwitcher.setOnClickListener(clickListener)
            iconSwitcher.setOnClickListener(clickListener)

            textSwitcher.isClickable = true
            iconSwitcher.isClickable = true

            Logger.d(TAG) { "点击监听设置完成" }
        } catch (e: Throwable) {
            Logger.e(TAG, "设置点击监听失败", e)
        }
    }

    private fun handleTickerClick(ticker: Any, ctx: HookContext) {
        try {
            val segments = Reflect.getObjectField(ticker, "mSegments") as? ArrayList<*>
            if (segments.isNullOrEmpty()) {
                Logger.d(TAG) { "无可用 segments" }
                return
            }

            val firstSegment = segments[0] ?: return
            val notification = Reflect.getObjectField(firstSegment, "notification") as? StatusBarNotification
            if (notification == null) {
                Logger.d(TAG) { "segment 中无通知" }
                return
            }

            val pkgName = notification.packageName
            val contentIntent = notification.notification.contentIntent

            val context = Reflect.getObjectField(ticker, "mContext") as? android.content.Context
            if (context == null) {
                Logger.e(TAG, "Context 为空")
                return
            }

            NotificationLauncher.open(ctx, context, pkgName, contentIntent, TAG)

            haltTicker(ctx.api, ticker)
        } catch (e: Throwable) {
            Logger.e(TAG, "处理 ticker 点击失败", e)
        }
    }

    private fun haltTicker(api: XposedInterface, ticker: Any) {
        try {
            Reflect.callMethod(api, ticker, "halt")
        } catch (e: Throwable) {
            Logger.d(TAG) { "halt ticker 失败（可忽略）" }
        }
    }
}