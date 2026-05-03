package com.karen.flymetool.hook.feature.systemui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger

object AODLyricHook {

    private const val AOD_BASIC_VIEW = "com.flyme.aod.view.AODBasicView"
    private const val ADVERT_TICKER_VIEW = "com.flyme.statusbar.ticker.AdvertTickerView"
    private const val HOOK_NAME = "AODLyric"

    private val handler = Handler(Looper.getMainLooper())
    private var lyricTextView: TextView? = null
    private var currentLyric: CharSequence = ""

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        hookAODBasicView(lpparam)
        hookAdvertTickerView(lpparam)
    }

    private fun hookAODBasicView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(AOD_BASIC_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val basicView = param.thisObject as ViewGroup
                        val context = basicView.context

                        lyricTextView = createLyricTextView(context)
                        basicView.addView(lyricTextView)

                        Logger.i(HOOK_NAME, "Added lyric TextView to AODBasicView")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked AODBasicView")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook AODBasicView failed", e)
        }
    }

    private fun hookAdvertTickerView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(ADVERT_TICKER_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "addNotification",
                "android.service.notification.StatusBarNotification",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sbn = param.args[0]
                        val notification = XposedHelpers.getObjectField(sbn, "notification")

                        // 只处理媒体通知
                        val isMediaNotification = XposedHelpers.callMethod(notification, "isMediaNotification") as? Boolean ?: false
                        if (!isMediaNotification) {
                            Logger.d(HOOK_NAME, "Skip non-media notification")
                            return
                        }

                        val tickerText = XposedHelpers.getObjectField(notification, "tickerText") as? CharSequence

                        if (!tickerText.isNullOrEmpty() && tickerText != currentLyric) {
                            currentLyric = tickerText
                            updateLyricText(tickerText)
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
                        currentLyric = ""
                        updateLyricText("")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked AdvertTickerView")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook AdvertTickerView failed", e)
        }
    }

    private fun createLyricTextView(context: android.content.Context): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(context, 8f)
            }
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            maxLines = 2
            setLineSpacing(0f, 1.2f)
            visibility = android.view.View.GONE
        }
    }

    private fun updateLyricText(text: CharSequence) {
        handler.post {
            lyricTextView?.let { tv ->
                if (text.isNotEmpty()) {
                    tv.text = text
                    tv.visibility = android.view.View.VISIBLE
                } else {
                    tv.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun dpToPx(context: android.content.Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
