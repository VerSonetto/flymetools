package com.karen.flymetool.hook.feature.systemui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageSwitcher
import android.widget.TextSwitcher
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

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

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "ticker_click")) return
        if (lpparam.packageName != "com.android.systemui") return

        hookMarqueeTicker(lpparam)
    }

    private fun findMarqueeTickerClass(lpparam: XC_LoadPackage.LoadPackageParam): Class<*>? {
        return try {
            XposedHelpers.findClass(MARQUEE_TICKER, lpparam.classLoader)
        } catch (_: Throwable) {
            try {
                XposedHelpers.findClass(MARQUEE_TICKER_LEGACY, lpparam.classLoader)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun hookMarqueeTicker(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = findMarqueeTickerClass(lpparam)
        if (clazz == null) {
            Logger.e(TAG, "未找到 MarqueeTicker 类（新旧包名均不存在）")
            return
        }

        // tap 判定上移到 PhoneStatusBarView.onTouchEvent 层，滚动期间可下拉；挂载失败降级 clickable 旧机制
        val tapInterceptHooked = hookStatusBarTouchTapIntercept(lpparam)

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "addEntry",
                StatusBarNotification::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (tapInterceptHooked) {
                            rememberTicker(param.thisObject)
                        } else {
                            setupClickListener(param.thisObject, lpparam)
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载 MarqueeTicker.addEntry（${if (tapInterceptHooked) "tap 拦截" else "clickable 降级"}）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MarqueeTicker.addEntry 失败", e)
            tryAlternativeHook(clazz, lpparam, tapInterceptHooked)
        }
    }

    private fun tryAlternativeHook(clazz: Class<*>, lpparam: XC_LoadPackage.LoadPackageParam, tapInterceptHooked: Boolean) {
        try {
            XposedHelpers.findAndHookConstructor(
                clazz,
                android.content.Context::class.java,
                android.view.View::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (tapInterceptHooked) {
                            rememberTicker(param.thisObject)
                        } else {
                            setupClickListener(param.thisObject, lpparam)
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载 MarqueeTicker 构造器（回退方案）")
        } catch (e: Throwable) {
            Logger.e(TAG, "回退方案挂载也失败", e)
        }
    }

    /** ticker 可见期间拦截 tap 跳转，拖动/长按放行；@return 是否挂载成功 */
    private fun hookStatusBarTouchTapIntercept(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return try {
            XposedHelpers.findAndHookMethod(
                PHONE_STATUS_BAR_VIEW,
                lpparam.classLoader,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args[0] as? MotionEvent ?: return
                        val ticker = activeTicker ?: return
                        val tickerView = try {
                            XposedHelpers.getObjectField(ticker, "mTickerView") as? View
                        } catch (_: Throwable) {
                            null
                        } ?: return
                        if (tickerView.visibility != View.VISIBLE) return

                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                tapDownRawX = event.rawX
                                tapDownRawY = event.rawY
                                tapDownTime = event.eventTime
                                isTapCandidate = true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (isTapCandidate && movedBeyondSlop(param, event)) {
                                    isTapCandidate = false
                                }
                            }

                            MotionEvent.ACTION_UP -> {
                                if (isTapCandidate &&
                                    !movedBeyondSlop(param, event) &&
                                    event.eventTime - tapDownTime <= MAX_TAP_DURATION_MS
                                ) {
                                    param.result = true
                                    handleTickerClick(ticker, lpparam)
                                }
                                isTapCandidate = false
                            }

                            MotionEvent.ACTION_CANCEL -> isTapCandidate = false
                        }
                    }

                    private fun movedBeyondSlop(param: MethodHookParam, event: MotionEvent): Boolean {
                        if (touchSlop < 0) {
                            val view = param.thisObject as? View ?: return true
                            touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
                        }
                        return Math.abs(event.rawX - tapDownRawX) > touchSlop ||
                            Math.abs(event.rawY - tapDownRawY) > touchSlop
                    }
                }
            )
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
                    launchPendingIntentFallback(contentIntent, context, pkgName, lpparam)
                }
            } else {
                launchAppByPackageName(context, pkgName, lpparam)
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

            val options = buildLaunchOptions(lpparam, context, pkgName)
            val fillInIntent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            contentIntent.send(context, 0, fillInIntent, null, null, null, options)
            clearWindowModeMark(context, pkgName, lpparam)
            Logger.i(TAG, "已通过 contentIntent 启动: $pkgName")
        } catch (e: Throwable) {
            throw e
        }
    }

    /** 构造启动 options；系统开启"跳转小窗"时附加与系统一致的小窗标记 */
    private fun buildLaunchOptions(
        lpparam: XC_LoadPackage.LoadPackageParam,
        context: android.content.Context,
        pkgName: String
    ): Bundle {
        val options = ActivityOptions.makeBasic().apply {
            try {
                val method = ActivityOptions::class.java.getMethod("setPendingIntentBackgroundActivityLaunchAllowed", Boolean::class.java)
                method.invoke(this, true)
            } catch (e: Throwable) {
            }
        }.toBundle()

        if (!shouldOpenInWindowMode(context)) return options

        try {
            val faoClass = lpparam.classLoader.loadClass("flyme.app.FlymeActivityOptions")
            val ctor = faoClass.getConstructor(Bundle::class.java)
            val fao = ctor.newInstance(options)
            faoClass.getMethod("setStartWindowMode", Boolean::class.javaPrimitiveType).invoke(fao, true)
            faoClass.getMethod("setPendingIntentBackgroundActivityStartMode", Int::class.javaPrimitiveType).invoke(fao, 1)
            val finalBundle = faoClass.getMethod("toBundle").invoke(fao) as? Bundle
            if (finalBundle != null) {
                options.clear()
                options.putAll(finalBundle)
            }
        } catch (e: Throwable) {
            options.putBoolean("start_windowmode", true)
            Logger.d(TAG) { "FlymeActivityOptions 反射失败，改用 bundle 标记" }
        }

        // 与系统 StatusBarNotificationActivityStarter 一致：预标记本次启动转小窗
        try {
            val wmeClass = lpparam.classLoader.loadClass("flyme.view.WindowManagerExt")
            val instance = wmeClass.getMethod("getInstance", android.content.Context::class.java).invoke(null, context)
            wmeClass.getMethod("setStartWindowMode", String::class.java).invoke(instance, pkgName)
        } catch (e: Throwable) {
            Logger.d(TAG) { "WindowManagerExt 预标记失败（可忽略）" }
        }
        return options
    }

    /** 清除 WindowManagerExt 小窗预标记，避免影响该应用后续启动 */
    private fun clearWindowModeMark(
        context: android.content.Context,
        pkgName: String,
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        try {
            val wmeClass = lpparam.classLoader.loadClass("flyme.view.WindowManagerExt")
            val instance = wmeClass.getMethod("getInstance", android.content.Context::class.java).invoke(null, context)
            wmeClass.getMethod("setStartWindowMode", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(instance, pkgName, false)
        } catch (e: Throwable) {
        }
    }

    /** 系统"跳转小窗"开关：window_mode_vertical_notification（竖屏）/ window_mode_horizontal_notification（横屏） */
    private fun shouldOpenInWindowMode(context: android.content.Context): Boolean {
        val resolver = context.contentResolver
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            Settings.System.getInt(resolver, "window_mode_horizontal_notification", 1) == 1
        } else {
            Settings.System.getInt(resolver, "window_mode_vertical_notification", 0) == 1
        }
    }

    private fun launchPendingIntentFallback(
        contentIntent: PendingIntent,
        context: android.content.Context,
        pkgName: String,
        lpparam: XC_LoadPackage.LoadPackageParam
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
                0,
                buildLaunchOptions(lpparam, context, pkgName)
            )
            clearWindowModeMark(context, pkgName, lpparam)
            Logger.i(TAG, "已通过 IntentSender 启动: $pkgName")
        } catch (e: Throwable) {
            Logger.d(TAG) { "IntentSender 失败，尝试直接 send()" }

            try {
                contentIntent.send()
                Logger.i(TAG, "已通过 send() 启动: $pkgName")
            } catch (e2: Throwable) {
                Logger.d(TAG) { "send() 失败，按包名启动" }
                launchAppByPackageName(context, pkgName, lpparam)
            }
        }
    }

    private fun launchAppByPackageName(
        context: android.content.Context,
        pkgName: String,
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent, buildLaunchOptions(lpparam, context, pkgName))
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
