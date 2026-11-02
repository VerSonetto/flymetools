package com.karen.flymetool.hook.feature.systemui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

/**
 * 从状态栏打开通知对应应用界面。
 *
 * 与系统点击通知保持一致：优先走 contentIntent（含系统「跳转小窗」行为），
 * 失败再回退 IntentSender，最后按包名启动。TickerClickHook 与状态栏歌词共用。
 */
internal object NotificationLauncher {

    /**
     * 打开 [packageName] 的应用界面。
     *
     * [pendingIntent] 为通知的 contentIntent 或媒体会话的会话界面，为空时按包名启动。
     */
    fun open(
        ctx: HookContext,
        context: Context,
        packageName: String?,
        pendingIntent: PendingIntent?,
        tag: String,
    ) {
        if (pendingIntent != null) {
            try {
                sendPendingIntent(ctx, context, packageName, pendingIntent, tag)
                return
            } catch (e: Throwable) {
                Logger.d(tag) { "正常启动失败（${e.javaClass.simpleName}），尝试回退" }
            }
            if (sendByIntentSender(ctx, context, packageName, pendingIntent, tag)) return
        }
        if (packageName == null) {
            Logger.w(tag, "缺少目标应用与启动 Intent，忽略")
            return
        }
        launchAppByPackageName(ctx, context, packageName, tag)
    }

    private fun sendPendingIntent(
        ctx: HookContext,
        context: Context,
        packageName: String?,
        pendingIntent: PendingIntent,
        tag: String,
    ) {
        try {
            val activityManagerNative = Reflect.findClass("android.app.ActivityManagerNative", ctx.classLoader)
            val am = Reflect.callStaticMethod(ctx.api, activityManagerNative, "getDefault")
            Reflect.callMethod(ctx.api, am, "resumeAppSwitches")
        } catch (e: Throwable) {
            Logger.d(tag) { "resumeAppSwitches 失败（可忽略）" }
        }

        val options = buildLaunchOptions(ctx, context, packageName, tag)
        val fillInIntent = Intent().apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        pendingIntent.send(context, 0, fillInIntent, null, null, null, options)
        clearWindowModeMark(context, packageName, ctx)
        Logger.i(tag, "已通过 contentIntent 启动: ${packageName ?: "unknown"}")
    }

    /** 构造启动 options；系统开启「跳转小窗」时附加与系统一致的小窗标记 */
    private fun buildLaunchOptions(
        ctx: HookContext,
        context: Context,
        packageName: String?,
        tag: String,
    ): Bundle {
        val options = ActivityOptions.makeBasic().apply {
            try {
                val method = ActivityOptions::class.java.getMethod(
                    "setPendingIntentBackgroundActivityLaunchAllowed",
                    Boolean::class.java,
                )
                method.invoke(this, true)
            } catch (e: Throwable) {
            }
        }.toBundle()

        if (packageName == null || !shouldOpenInWindowMode(context)) return options

        try {
            val faoClass = ctx.classLoader.loadClass("flyme.app.FlymeActivityOptions")
            val ctor = faoClass.getConstructor(Bundle::class.java)
            val fao = ctor.newInstance(options)
            faoClass.getMethod("setStartWindowMode", Boolean::class.javaPrimitiveType).invoke(fao, true)
            faoClass.getMethod("setPendingIntentBackgroundActivityStartMode", Int::class.javaPrimitiveType)
                .invoke(fao, 1)
            val finalBundle = faoClass.getMethod("toBundle").invoke(fao) as? Bundle
            if (finalBundle != null) {
                options.clear()
                options.putAll(finalBundle)
            }
        } catch (e: Throwable) {
            options.putBoolean("start_windowmode", true)
            Logger.d(tag) { "FlymeActivityOptions 反射失败，改用 bundle 标记" }
        }

        // 与系统 StatusBarNotificationActivityStarter 一致：预标记本次启动转小窗
        try {
            val wmeClass = ctx.classLoader.loadClass("flyme.view.WindowManagerExt")
            val instance = wmeClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            wmeClass.getMethod("setStartWindowMode", String::class.java)
                .invoke(instance, packageName)
        } catch (e: Throwable) {
            Logger.d(tag) { "WindowManagerExt 预标记失败（可忽略）" }
        }
        return options
    }

    /** 清除 WindowManagerExt 小窗预标记，避免影响该应用后续启动 */
    private fun clearWindowModeMark(context: Context, packageName: String?, ctx: HookContext) {
        if (packageName == null) return
        try {
            val wmeClass = ctx.classLoader.loadClass("flyme.view.WindowManagerExt")
            val instance = wmeClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            wmeClass.getMethod("setStartWindowMode", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(instance, packageName, false)
        } catch (e: Throwable) {
        }
    }

    /** 系统「跳转小窗」开关：window_mode_vertical_notification（竖屏）/ window_mode_horizontal_notification（横屏） */
    private fun shouldOpenInWindowMode(context: Context): Boolean {
        val resolver = context.contentResolver
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            Settings.System.getInt(resolver, "window_mode_horizontal_notification", 1) == 1
        } else {
            Settings.System.getInt(resolver, "window_mode_vertical_notification", 0) == 1
        }
    }

    /** @return 是否已启动 */
    private fun sendByIntentSender(
        ctx: HookContext,
        context: Context,
        packageName: String?,
        pendingIntent: PendingIntent,
        tag: String,
    ): Boolean {
        try {
            val intentSender = pendingIntent.intentSender
            val fillInIntent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startIntentSender(
                intentSender,
                fillInIntent,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                0,
                0,
                buildLaunchOptions(ctx, context, packageName, tag),
            )
            clearWindowModeMark(context, packageName, ctx)
            Logger.i(tag, "已通过 IntentSender 启动: ${packageName ?: "unknown"}")
            return true
        } catch (e: Throwable) {
            Logger.d(tag) { "IntentSender 失败，尝试直接 send()" }
        }

        return try {
            pendingIntent.send()
            Logger.i(tag, "已通过 send() 启动: ${packageName ?: "unknown"}")
            true
        } catch (e: Throwable) {
            Logger.d(tag) { "send() 失败，按包名启动" }
            false
        }
    }

    private fun launchAppByPackageName(
        ctx: HookContext,
        context: Context,
        packageName: String,
        tag: String,
    ) {
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent, buildLaunchOptions(ctx, context, packageName, tag))
                Logger.i(tag, "已按包名启动: $packageName")
            } else {
                Logger.w(tag, "无启动 Intent", "pkg" to packageName)
            }
        } catch (e: Throwable) {
            Logger.e(tag, "按包名启动失败", e, "pkg" to packageName)
        }
    }
}
