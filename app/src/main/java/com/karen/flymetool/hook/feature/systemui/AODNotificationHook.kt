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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
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

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var containerLayout: LinearLayout? = null
    private var iconView: ImageView? = null
    private var notificationTextView: TextView? = null
    private var packageName: String = ""

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

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        this.packageName = packageName
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "aod_notification")) return
        if (lpparam.packageName != "com.android.systemui") return

        hookAODBasicView(lpparam)

        when {
            FlymeVersionUtils.isFlyme12() -> {
                hookHeadsUpManager(lpparam)
                hookAdvertTickerView(lpparam, ADVERT_TICKER_VIEW_NEW)
            }
            else -> {
                hookAlertingManager(lpparam)
                hookAdvertTickerView(lpparam, ADVERT_TICKER_VIEW_OLD)
            }
        }
    }

    private fun hookAODBasicView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(AOD_BASIC_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val basicView = param.thisObject as LinearLayout
                        val context = basicView.context

                        containerLayout = createContainerLayout(context, lpparam, packageName)
                        basicView.addView(containerLayout)

                        cachedNotification?.let { data ->
                            updateNotificationDisplay(data)
                        }

                        Logger.i(TAG, "已向 AODBasicView 添加通知容器")
                    }
                }
            )

            Logger.i(TAG, "已挂载 AODBasicView")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 AODBasicView 失败", e)
        }
    }

    private fun hookAlertingManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val managerClass = XposedHelpers.findClass(ALERTING_MANAGER, lpparam.classLoader)
            val entryClass = XposedHelpers.findClass(NOTIFICATION_ENTRY, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                managerClass,
                "showNotification",
                entryClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val entry = param.args[0] ?: return
                        processNotificationEntry(entry)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                managerClass,
                "removeAlertEntry",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        cachedNotification = null
                        clearNotificationDisplay()
                        Logger.i(TAG, "已清除通知（Flyme 10）")
                    }
                }
            )

            Logger.i(TAG, "已挂载 Flyme 10: AlertingNotificationManager")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 AlertingNotificationManager 失败", e)
        }
    }

    private fun hookHeadsUpManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val managerClass = XposedHelpers.findClass(HEADS_UP_MANAGER_IMPL, lpparam.classLoader)
            val entryClass = XposedHelpers.findClass(NOTIFICATION_ENTRY, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                managerClass,
                "showNotification",
                entryClass,
                Boolean::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val entry = param.args[0] ?: return
                        processNotificationEntry(entry)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                managerClass,
                "removeNotification",
                String::class.java,
                Boolean::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        cachedNotification = null
                        clearNotificationDisplay()
                        Logger.i(TAG, "已清除通知（Flyme 12）")
                    }
                }
            )

            Logger.i(TAG, "已挂载 Flyme 12: HeadsUpManagerImpl")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 HeadsUpManagerImpl 失败", e)
        }
    }

    private fun processNotificationEntry(entry: Any) {
        val sbn = XposedHelpers.callMethod(entry, "getSbn") ?: return
        val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE, "") ?: ""
        val content = extras.getCharSequence(Notification.EXTRA_TEXT, "") ?: ""

        if (title.isNotEmpty() || content.isNotEmpty()) {
            cachedNotification = NotificationData(notification.smallIcon, title, content)
            updateNotificationDisplay(cachedNotification!!)
            Logger.i(TAG, "已缓存通知: $title")
        }
    }

    private fun hookAdvertTickerView(lpparam: XC_LoadPackage.LoadPackageParam, className: String) {
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "addNotification",
                "android.service.notification.StatusBarNotification",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sbn = param.args[0] ?: return
                        val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return

                        if (XposedHelpers.callMethod(notification, "isMediaNotification") as Boolean) {
                            return
                        }

                        val tickerText = XposedHelpers.getObjectField(notification, "tickerText") as? CharSequence ?: return
                        val icon = notification.smallIcon

                        if (tickerText.isNotEmpty()) {
                            cachedNotification = NotificationData(icon, "", tickerText)
                            updateNotificationDisplay(cachedNotification!!)
                            Logger.i(TAG, "收到 ticker 通知: $tickerText")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                clazz,
                "removeNotification",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        cachedNotification = null
                        clearNotificationDisplay()
                        Logger.i(TAG, "已清除 ticker 通知")
                    }
                }
            )

            Logger.i(TAG, "已挂载 AdvertTickerView: $className")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 AdvertTickerView 失败", e, "host" to className)
        }
    }

    private fun createContainerLayout(
        context: Context,
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String
    ): LinearLayout {
        val maxLines = XposedPrefs.getFeatureValue(lpparam, packageName, "aod_notification", DEFAULT_MAX_LINES)

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
                textSize = 11f
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
