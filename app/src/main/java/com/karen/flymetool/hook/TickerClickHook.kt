package com.karen.flymetool.hook

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.ImageSwitcher
import android.widget.TextSwitcher
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object TickerClickHook {

    private const val MARQUEE_TICKER = "com.flyme.statusbar.ticker.MarqueeTicker"
    private const val HOOK_NAME = "TickerClick"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        hookMarqueeTicker(lpparam)
    }

    private fun hookMarqueeTicker(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(MARQUEE_TICKER, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "addEntry",
                StatusBarNotification::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        setupClickListener(param.thisObject, lpparam)
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked MarqueeTicker.addEntry")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook MarqueeTicker.addEntry failed", e)
            tryAlternativeHook(lpparam)
        }
    }

    private fun tryAlternativeHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(MARQUEE_TICKER, lpparam.classLoader)

            XposedHelpers.findAndHookConstructor(
                clazz,
                android.content.Context::class.java,
                android.view.View::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        setupClickListener(param.thisObject, lpparam)
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked MarqueeTicker constructor")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Alternative hook also failed", e)
        }
    }

    private fun setupClickListener(ticker: Any, lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val textSwitcher = XposedHelpers.getObjectField(ticker, "mTextSwitcher") as? TextSwitcher
            val iconSwitcher = XposedHelpers.getObjectField(ticker, "mIconSwitcher") as? ImageSwitcher

            if (textSwitcher == null || iconSwitcher == null) {
                Logger.e(HOOK_NAME, "Switcher views not found")
                return
            }

            val clickListener = View.OnClickListener {
                handleTickerClick(ticker, lpparam)
            }

            textSwitcher.setOnClickListener(clickListener)
            iconSwitcher.setOnClickListener(clickListener)

            textSwitcher.isClickable = true
            iconSwitcher.isClickable = true

            Logger.d(HOOK_NAME, "Click listener setup completed")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Setup click listener failed", e)
        }
    }

    private fun handleTickerClick(ticker: Any, lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val segments = XposedHelpers.getObjectField(ticker, "mSegments") as? ArrayList<*>
            if (segments.isNullOrEmpty()) {
                Logger.d(HOOK_NAME, "No segments available")
                return
            }

            val firstSegment = segments[0] ?: return
            val notification = XposedHelpers.getObjectField(firstSegment, "notification") as? StatusBarNotification
            if (notification == null) {
                Logger.d(HOOK_NAME, "No notification in segment")
                return
            }

            val pkgName = notification.packageName
            val contentIntent = notification.notification.contentIntent

            val context = XposedHelpers.getObjectField(ticker, "mContext") as? android.content.Context
            if (context == null) {
                Logger.e(HOOK_NAME, "Context is null")
                return
            }

            if (contentIntent != null) {
                try {
                    launchPendingIntentProperly(contentIntent, context, pkgName, lpparam)
                } catch (e: Throwable) {
                    Logger.d(HOOK_NAME, "Proper launch failed: ${e.message}, trying fallback")
                    launchPendingIntentFallback(contentIntent, context, pkgName)
                }
            } else {
                launchAppByPackageName(context, pkgName)
            }

            haltTicker(ticker)
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Handle ticker click failed", e)
        }
    }

    private fun launchPendingIntentProperly(
        contentIntent: PendingIntent,
        context: android.content.Context,
        pkgName: String,
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        try {
            try {
                val activityManagerNative = XposedHelpers.findClass("android.app.ActivityManagerNative", lpparam.classLoader)
                val am = XposedHelpers.callStaticMethod(activityManagerNative, "getDefault")
                XposedHelpers.callMethod(am, "resumeAppSwitches")
            } catch (e: Throwable) {
                Logger.d(HOOK_NAME, "resumeAppSwitches failed: ${e.message}")
            }

            val options = ActivityOptions.makeBasic().apply {
                try {
                    val method = ActivityOptions::class.java.getMethod("setPendingIntentBackgroundActivityLaunchAllowed", Boolean::class.java)
                    method.invoke(this, true)
                } catch (e: Throwable) {
                }
            }.toBundle()

            val fillInIntent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            contentIntent.send(context, 0, fillInIntent, null, null, null, options)
            Logger.d(HOOK_NAME, "Launched contentIntent for $pkgName")
        } catch (e: Throwable) {
            throw e
        }
    }

    private fun launchPendingIntentFallback(
        contentIntent: PendingIntent,
        context: android.content.Context,
        pkgName: String
    ) {
        try {
            val intentSender = contentIntent.intentSender
            val fillInIntent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startIntentSender(
                intentSender,
                fillInIntent,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                0,
                0
            )
            Logger.d(HOOK_NAME, "Launched via IntentSender for $pkgName")
        } catch (e: Throwable) {
            Logger.d(HOOK_NAME, "IntentSender failed: ${e.message}, trying direct send")

            try {
                contentIntent.send()
                Logger.d(HOOK_NAME, "Launched via simple send() for $pkgName")
            } catch (e2: Throwable) {
                Logger.d(HOOK_NAME, "Simple send failed: ${e2.message}, launching by package")
                launchAppByPackageName(context, pkgName)
            }
        }
    }

    private fun launchAppByPackageName(context: android.content.Context, pkgName: String) {
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Logger.d(HOOK_NAME, "Launched app: $pkgName")
            } else {
                Logger.w(HOOK_NAME, "No launch intent for: $pkgName")
            }
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Launch app failed", e)
        }
    }

    private fun haltTicker(ticker: Any) {
        try {
            XposedHelpers.callMethod(ticker, "halt")
        } catch (e: Throwable) {
            Logger.d(HOOK_NAME, "Halt ticker failed: ${e.message}")
        }
    }
}
