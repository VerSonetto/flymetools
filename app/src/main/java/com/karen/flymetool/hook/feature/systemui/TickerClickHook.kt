package com.karen.flymetool.hook.feature.systemui

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
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object TickerClickHook : FeatureHook {

    private const val MARQUEE_TICKER = "com.flyme.statusbar.ticker.MarqueeTicker"
    private const val TAG = "TickerClick"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "ticker_click")) return
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

            Logger.i(TAG, "已挂载 MarqueeTicker.addEntry")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MarqueeTicker.addEntry 失败", e)
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

            Logger.i(TAG, "已挂载 MarqueeTicker 构造器（回退方案）")
        } catch (e: Throwable) {
            Logger.e(TAG, "回退方案挂载也失败", e)
        }
    }

    private fun setupClickListener(ticker: Any, lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val textSwitcher = XposedHelpers.getObjectField(ticker, "mTextSwitcher") as? TextSwitcher
            val iconSwitcher = XposedHelpers.getObjectField(ticker, "mIconSwitcher") as? ImageSwitcher

            if (textSwitcher == null || iconSwitcher == null) {
                Logger.w(TAG, "未找到 Switcher 视图")
                return
            }

            val clickListener = View.OnClickListener {
                handleTickerClick(ticker, lpparam)
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

    private fun handleTickerClick(ticker: Any, lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val segments = XposedHelpers.getObjectField(ticker, "mSegments") as? ArrayList<*>
            if (segments.isNullOrEmpty()) {
                Logger.d(TAG) { "无可用 segments" }
                return
            }

            val firstSegment = segments[0] ?: return
            val notification = XposedHelpers.getObjectField(firstSegment, "notification") as? StatusBarNotification
            if (notification == null) {
                Logger.d(TAG) { "segment 中无通知" }
                return
            }

            val pkgName = notification.packageName
            val contentIntent = notification.notification.contentIntent

            val context = XposedHelpers.getObjectField(ticker, "mContext") as? android.content.Context
            if (context == null) {
                Logger.e(TAG, "Context 为空")
                return
            }

            if (contentIntent != null) {
                try {
                    launchPendingIntentProperly(contentIntent, context, pkgName, lpparam)
                } catch (e: Throwable) {
                    Logger.d(TAG) { "正常启动失败（${e.javaClass.simpleName}），尝试回退" }
                    launchPendingIntentFallback(contentIntent, context, pkgName)
                }
            } else {
                launchAppByPackageName(context, pkgName)
            }

            haltTicker(ticker)
        } catch (e: Throwable) {
            Logger.e(TAG, "处理 ticker 点击失败", e)
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
                Logger.d(TAG) { "resumeAppSwitches 失败（可忽略）" }
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
            Logger.i(TAG, "已通过 contentIntent 启动: $pkgName")
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
            Logger.i(TAG, "已通过 IntentSender 启动: $pkgName")
        } catch (e: Throwable) {
            Logger.d(TAG) { "IntentSender 失败，尝试直接 send()" }

            try {
                contentIntent.send()
                Logger.i(TAG, "已通过 send() 启动: $pkgName")
            } catch (e2: Throwable) {
                Logger.d(TAG) { "send() 失败，按包名启动" }
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
                Logger.i(TAG, "已按包名启动: $pkgName")
            } else {
                Logger.w(TAG, "无启动 Intent: $pkgName")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "按包名启动失败", e)
        }
    }

    private fun haltTicker(ticker: Any) {
        try {
            XposedHelpers.callMethod(ticker, "halt")
        } catch (e: Throwable) {
            Logger.d(TAG) { "halt ticker 失败（可忽略）" }
        }
    }
}
