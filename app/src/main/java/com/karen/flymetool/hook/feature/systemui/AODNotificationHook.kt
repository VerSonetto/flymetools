package com.karen.flymetool.hook.feature.systemui

import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

object AODNotificationHook : FeatureHook {

    private const val AOD_BASIC_VIEW = "com.flyme.aod.view.AODBasicView"
    private const val ALERTING_MANAGER = "com.android.systemui.statusbar.AlertingNotificationManager"
    private const val HEADS_UP_MANAGER_IMPL = "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl"
    private const val ADVERT_TICKER_VIEW_OLD = "com.flyme.statusbar.ticker.AdvertTickerView"
    private const val ADVERT_TICKER_VIEW_NEW = "com.flyme.systemui.statusbar.ticker.AdvertTickerView"
    private const val NOTIFICATION_ENTRY = "com.android.systemui.statusbar.notification.collection.NotificationEntry"
    private const val TAG = "AODNotification"
    private const val DEFAULT_MAX_LINES = 3
    /** 与历史硬编码 textSize=11f 一致；extra key，避免覆盖最大行数 */
    private const val TEXT_SIZE_SUFFIX = "text_size"
    private const val DEFAULT_TEXT_SIZE_SP = 11

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var containerLayout: LinearLayout? = null
    private var iconView: ImageView? = null
    private var notificationTextView: TextView? = null

    @Volatile
    private var cachedNotification: NotificationData? = null

    private data class NotificationData(
        val icon: Any?,
        val title: CharSequence,
        val content: CharSequence
    ) {
        fun format(): CharSequence {
            return if (title.isNotEmpty() && content.isNotEmpty()) {
                "$title: $content"
            } else if (content.isNotEmpty()) {
                content
            } else {
                title
            }
        }
    }

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("aod_notification")) return
        if (ctx.packageName != "com.android.systemui") return

        hookAODBasicView(ctx)

        when {
            FlymeVersionUtils.isFlyme12() -> {
                hookHeadsUpManager(ctx)
                hookAdvertTickerView(ctx, ADVERT_TICKER_VIEW_NEW)
            }
            else -> {
                hookAlertingManager(ctx)
                hookAdvertTickerView(ctx, ADVERT_TICKER_VIEW_OLD)
            }
        }
    }

    private fun hookAODBasicView(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(AOD_BASIC_VIEW, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "onFinishInflate",
            ) { chain ->
                val result = chain.proceed()
                val basicView = chain.getThisObject() as LinearLayout
                val context = basicView.context

                containerLayout = createContainerLayout(context, ctx)
                basicView.addView(containerLayout)

                cachedNotification?.let { data ->
                    updateNotificationDisplay(data)
                }

                Logger.i(TAG, "已向 AODBasicView 添加通知容器")
                result
            }

            Logger.i(TAG, "已挂载 AODBasicView")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 AODBasicView 失败", e)
        }
    }

    private fun hookAlertingManager(ctx: HookContext) {
        try {
            val managerClass = Reflect.findClass(ALERTING_MANAGER, ctx.classLoader)
            val entryClass = Reflect.findClass(NOTIFICATION_ENTRY, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                managerClass,
                "showNotification",
                entryClass,
            ) { chain ->
                val result = chain.proceed()
                val entry = chain.getArg(0) ?: return@hookMethodOn result
                processNotificationEntry(ctx, entry)
                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                managerClass,
                "removeAlertEntry",
                String::class.java,
            ) { chain ->
                val result = chain.proceed()
                cachedNotification = null
                clearNotificationDisplay()
                Logger.i(TAG, "已清除通知（Flyme 10）")
                result
            }

            Logger.i(TAG, "已挂载 Flyme 10: AlertingNotificationManager")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 AlertingNotificationManager 失败", e)
        }
    }

    private fun hookHeadsUpManager(ctx: HookContext) {
        try {
            val managerClass = Reflect.findClass(HEADS_UP_MANAGER_IMPL, ctx.classLoader)
            val entryClass = Reflect.findClass(NOTIFICATION_ENTRY, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                managerClass,
                "showNotification",
                entryClass,
                Boolean::class.java,
            ) { chain ->
                val result = chain.proceed()
                val entry = chain.getArg(0) ?: return@hookMethodOn result
                processNotificationEntry(ctx, entry)
                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                managerClass,
                "removeNotification",
                String::class.java,
                Boolean::class.java,
                String::class.java,
            ) { chain ->
                val result = chain.proceed()
                cachedNotification = null
                clearNotificationDisplay()
                Logger.i(TAG, "已清除通知（Flyme 12）")
                result
            }

            Logger.i(TAG, "已挂载 Flyme 12: HeadsUpManagerImpl")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 HeadsUpManagerImpl 失败", e)
        }
    }

    private fun processNotificationEntry(ctx: HookContext, entry: Any) {
        val sbn = Reflect.callMethod(ctx.api, entry, "getSbn") ?: return
        val notification = Reflect.callMethod(ctx.api, sbn, "getNotification") as? Notification ?: return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE, "") ?: ""
        val content = extras.getCharSequence(Notification.EXTRA_TEXT, "") ?: ""

        if (title.isNotEmpty() || content.isNotEmpty()) {
            cachedNotification = NotificationData(notification.smallIcon, title, content)
            updateNotificationDisplay(cachedNotification!!)
            Logger.i(TAG, "已缓存通知: $title")
        }
    }

    private fun hookAdvertTickerView(ctx: HookContext, className: String) {
        try {
            val clazz = Reflect.findClass(className, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "addNotification",
                Reflect.findClass("android.service.notification.StatusBarNotification", ctx.classLoader),
            ) { chain ->
                val result = chain.proceed()
                val sbn = chain.getArg(0) ?: return@hookMethodOn result
                val notification = Reflect.callMethod(ctx.api, sbn, "getNotification") as? Notification ?: return@hookMethodOn result

                if (Reflect.callMethod(ctx.api, notification, "isMediaNotification") as Boolean) {
                    return@hookMethodOn result
                }

                val tickerText = Reflect.getObjectField(notification, "tickerText") as? CharSequence ?: return@hookMethodOn result
                val icon = notification.smallIcon

                if (tickerText.isNotEmpty()) {
                    cachedNotification = NotificationData(icon, "", tickerText)
                    updateNotificationDisplay(cachedNotification!!)
                    Logger.i(TAG, "收到 ticker 通知: $tickerText")
                }
                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "removeNotification",
                String::class.java,
            ) { chain ->
                val result = chain.proceed()
                cachedNotification = null
                clearNotificationDisplay()
                Logger.i(TAG, "已清除 ticker 通知")
                result
            }

            Logger.i(TAG, "已挂载 AdvertTickerView: $className")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 AdvertTickerView 失败", e, "host" to className)
        }
    }

    private fun createContainerLayout(
        context: Context,
        ctx: HookContext
    ): LinearLayout {
        val maxLines = ctx.featureValue("aod_notification", DEFAULT_MAX_LINES)
        val textSizeSp = ctx.featureExtraValue(
            "aod_notification", TEXT_SIZE_SUFFIX, DEFAULT_TEXT_SIZE_SP
        ).coerceIn(8, 28).toFloat()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(context, 16f)
            }

            iconView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(context, 20f),
                    dpToPx(context, 20f)
                ).apply {
                    marginEnd = dpToPx(context, 8f)
                }
                visibility = View.GONE
            }
            addView(iconView)

            notificationTextView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                textSize = textSizeSp
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                this.maxLines = maxLines
                setLineSpacing(0f, 1.2f)
                visibility = View.GONE
            }
            addView(notificationTextView)
        }
    }

    private fun updateNotificationDisplay(data: NotificationData) {
        handler.post {
            iconView?.let { iv ->
                try {
                    iv.setImageIcon(data.icon as? android.graphics.drawable.Icon)
                    iv.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Logger.e(TAG, "设置图标失败", e)
                    iv.visibility = View.GONE
                }
            }

            notificationTextView?.let { tv ->
                tv.text = data.format()
                tv.visibility = View.VISIBLE
            }

            containerLayout?.visibility = View.VISIBLE
        }
    }

    private fun clearNotificationDisplay() {
        handler.post {
            iconView?.visibility = View.GONE
            notificationTextView?.visibility = View.GONE
            containerLayout?.visibility = View.GONE
        }
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}