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
    private const val HOOK_NAME = "AODNotification"
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

                        Logger.i(HOOK_NAME, "Added notification container to AODBasicView")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked AODBasicView")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook AODBasicView failed", e)
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
                        Logger.i(HOOK_NAME, "Cleared notification (Flyme 10)")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked Flyme 10: AlertingNotificationManager")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook AlertingNotificationManager failed", e)
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
                        Logger.i(HOOK_NAME, "Cleared notification (Flyme 12)")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked Flyme 12: HeadsUpManagerImpl")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook HeadsUpManagerImpl failed", e)
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
            Logger.i(HOOK_NAME, "Cached notification: $title")
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
                            Logger.i(HOOK_NAME, "Received ticker notification: $tickerText")
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
                        Logger.i(HOOK_NAME, "Cleared ticker notification")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked AdvertTickerView: $className")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook AdvertTickerView failed", e)
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
                    Logger.e(HOOK_NAME, "Failed to set icon", e)
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
