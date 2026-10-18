package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.os.Handler
import android.view.View
import android.widget.ImageSwitcher
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

/**
 * 滚动消息只显示在前摄左侧。
 *
 * 居中挖孔机型上 MarqueeTicker.fillTickerContent 会把超长文字用空格推到前摄右侧分段显示；
 * 这里改为把完整文字交给文本视图，在收窄到前摄左侧的宽度内滚动显示。
 */
object TickerLeftOfCameraHook : FeatureHook {

    private const val TAG = "TickerLeftOfCamera"
    private const val KEY = "ticker_left_of_camera"
    private const val TICKER_CLASS = "com.flyme.systemui.statusbar.ticker.MarqueeTicker"
    private const val TICKER_CLASS_LEGACY = "com.flyme.statusbar.ticker.MarqueeTicker"
    private const val TEXT_VIEW_CLASS = "com.flyme.systemui.statusbar.ticker.MarqueeTextView"
    private const val TEXT_VIEW_CLASS_LEGACY = "com.flyme.statusbar.ticker.MarqueeTextView"

    /** 等文本视图按新宽度完成一次布局后再取文本宽度 */
    private const val SCROLL_START_DELAY_MS = 120L

    /** 滚动速度百分比，100 为原生速度 */
    private const val SPEED_DEFAULT_PERCENT = 100
    private const val SPEED_MIN_PERCENT = 50
    private const val SPEED_MAX_PERCENT = 500

    /** 只缩放毫秒级的滚动 tick，滚动前的停留（1500ms）不动 */
    private const val MAX_TICK_DELAY_MS = 100L

    /** 前摄左侧可用宽度（px），供文本视图测量使用 */
    @Volatile
    private var cameraLeftWidth = 0

    /** 滚动专用的 Handler，按实例身份限定速度缩放范围 */
    @Volatile
    private var scrollHandler: Handler? = null

    @Volatile
    private var scrollSpeed = 1f

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.android.systemui") return
        if (!ctx.featureEnabled(KEY)) return

        val tickerClass = findClass(ctx, TICKER_CLASS, TICKER_CLASS_LEGACY)
        val textViewClass = findClass(ctx, TEXT_VIEW_CLASS, TEXT_VIEW_CLASS_LEGACY)
        if (tickerClass == null || textViewClass == null) {
            Logger.w(TAG, "未找到 MarqueeTicker 或 MarqueeTextView")
            return
        }

        scrollSpeed = ctx.featureValue(KEY, SPEED_DEFAULT_PERCENT)
            .coerceIn(SPEED_MIN_PERCENT, SPEED_MAX_PERCENT) / 100f

        try {
            hookScrollSpeed(ctx)
            Reflect.hookMethodOn(
                ctx.api,
                textViewClass,
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ) { chain ->
                val result = chain.proceed()
                val limit = cameraLeftWidth
                val view = chain.getThisObject() as? View
                if (limit > 0 && view != null) {
                    Reflect.callMethod(ctx.api, view, "setMeasuredDimension", limit, view.measuredHeight)
                }
                result
            }

            Reflect.hookMethodOn(ctx.api, tickerClass, "fillTickerContent", List::class.java) { chain ->
                val ticker = chain.getThisObject()
                val chars = chain.getArg(0) as? List<*> ?: return@hookMethodOn chain.proceed()
                val text = StringBuilder().apply { chars.forEach { append(it as? String ?: "") } }.toString()
                if (text.isEmpty()) return@hookMethodOn chain.proceed()
                val limit = cameraLeftLimit(ticker) ?: return@hookMethodOn chain.proceed()
                cameraLeftWidth = limit
                showScrollingText(ctx, ticker, text)
                null
            }

            Logger.i(TAG, "已挂载 MarqueeTicker.fillTickerContent（速度 ${scrollSpeed}x）")
        } catch (e: NoSuchMethodException) {
            Logger.w(TAG, "未找到目标方法，跳过", "error" to e)
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }

    private fun findClass(ctx: HookContext, name: String, legacyName: String): Class<*>? {
        return try {
            Reflect.findClass(name, ctx.classLoader)
        } catch (_: Throwable) {
            try {
                Reflect.findClass(legacyName, ctx.classLoader)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 前摄左侧可用宽度（px）；非居中挖孔（cutout 为 0）或读不到时返回 null */
    private fun cameraLeftLimit(ticker: Any): Int? {
        return try {
            val context = Reflect.getObjectField(ticker, "mContext") as? Context ?: return null
            val cutoutLeft = if (Reflect.getBooleanField(ticker, "mIsPortrait")) {
                Reflect.getIntField(ticker, "mPorDisplayCutoutLeft")
            } else {
                Reflect.getIntField(ticker, "mHorDisplayCutoutLeft")
            }
            val icon = Reflect.getObjectField(ticker, "mIconSwitcher") as? ImageSwitcher
            val iconWidth = if (icon != null && icon.visibility == View.VISIBLE) icon.width else 0
            val holeSpace = Reflect.getFloatField(ticker, "mTickerHoleSpace")
            val holeSpacePx = (holeSpace * context.resources.displayMetrics.density + 0.5f).toInt()
            (cutoutLeft - iconWidth - holeSpacePx).takeIf { it > 0 }
        } catch (e: Throwable) {
            Logger.once(TAG, "noCutout", "读取前摄位置失败，保持原生显示", "error" to e)
            null
        }
    }

    /** 用完整文本替换原生分段内容，并让文本视图在收窄后的宽度内滚动 */
    private fun showScrollingText(ctx: HookContext, ticker: Any, text: String) {
        try {
            val switcher = Reflect.getObjectField(ticker, "mTextSwitcher") ?: return
            val handler = Reflect.getObjectField(ticker, "mHandler") as? Handler
            cancelPendingFinish(ticker, handler)

            Reflect.callMethod(ctx.api, switcher, "setText", text)
            val displayedChild = Reflect.callMethod(ctx.api, switcher, "getDisplayedChild") as? Int ?: 0
            val textView = Reflect.callMethod(ctx.api, switcher, "getChildAt", displayedChild) ?: return
            bindSegment(ticker, textView)
            Reflect.setObjectField(ticker, "mCurrentTicker", textView)
            scrollHandler = Reflect.getObjectField(textView, "mMyHandler") as? Handler
            (switcher as? View)?.requestLayout()

            handler?.postDelayed({
                try {
                    if (Reflect.getObjectField(textView, "mMyHandler") == null) {
                        // 视图未就绪，滚动永远不会开始，直接收尾恢复状态栏
                        Logger.w(TAG, "文本视图未就绪，跳过滚动")
                        Reflect.callMethod(ctx.api, ticker, "halt")
                        return@postDelayed
                    }
                    Reflect.callMethod(ctx.api, textView, "getTextWidth")
                    Reflect.callMethod(ctx.api, textView, "startScrollSoon")
                    // 滚动已开始再接管收尾：滚动结束后切换下一条或隐藏
                    Reflect.callMethod(ctx.api, ticker, "scheduleAdvance")
                } catch (e: Throwable) {
                    Logger.d(TAG) { "启动滚动失败: ${e.javaClass.simpleName}" }
                }
            }, SCROLL_START_DELAY_MS)
        } catch (e: Throwable) {
            Logger.e(TAG, "显示完整消息失败", e)
        }
    }

    /** 滚动速度取决于 tick 间隔，这里按倍率缩放该 Handler 投递的毫秒级延迟 */
    private fun hookScrollSpeed(ctx: HookContext) {
        try {
            Reflect.hookMethodOn(
                ctx.api,
                Handler::class.java,
                "postDelayed",
                Runnable::class.java,
                Long::class.javaPrimitiveType,
            ) { chain ->
                val target = scrollHandler
                if (target == null || chain.getThisObject() !== target) {
                    return@hookMethodOn chain.proceed()
                }
                val delay = chain.getArg(1) as? Long ?: return@hookMethodOn chain.proceed()
                if (delay > MAX_TICK_DELAY_MS) return@hookMethodOn chain.proceed()
                val scaled = (delay / scrollSpeed).toLong().coerceAtLeast(1L)
                chain.proceed(arrayOf<Any>(chain.getArg(0) as Any, scaled))
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载滚动速度失败", e)
        }
    }

    /** 文本视图滚动期间不允许原分段逻辑把消息提前收掉 */
    private fun cancelPendingFinish(ticker: Any, handler: Handler?) {
        if (handler == null) return
        for (name in arrayOf("hideLastTicker", "showUnfinishTicker")) {
            val runnable = try {
                Reflect.getObjectField(ticker, name)
            } catch (_: Throwable) {
                null
            }
            if (runnable is Runnable) handler.removeCallbacks(runnable)
        }
    }

    private fun bindSegment(ticker: Any, textView: Any) {
        val segments = Reflect.getObjectField(ticker, "mSegments") as? List<*> ?: return
        val first = segments.firstOrNull() ?: return
        Reflect.setObjectField(textView, "mSegment", first)
    }
}
