package com.karen.flymetool.hook.feature.settings

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object ForceNotificationEnableHook : FeatureHook {

    private const val NOTIFICATION_BACKEND = "com.android.settings.notification.NotificationBackend"
    private const val TAG = "ForceNotificationEnable"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("force_notification_enable")) return
        if (ctx.packageName != "com.android.settings") return

        try {
            hookEnableSwitch(ctx)
            hookRecordCanBeBlocked(ctx)
            hookGetNotificationsBanned(ctx)

            Logger.i(TAG, "全部 Hook 安装成功")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }

    private fun hookEnableSwitch(ctx: HookContext) {
        val clazz = Reflect.findClass(NOTIFICATION_BACKEND, ctx.classLoader)
        Reflect.hookAllMethods(ctx.api, clazz, "enableSwitch") { chain ->
            chain.proceed()
            true
        }
        Logger.i(TAG, "已挂载 enableSwitch")
    }

    private fun hookRecordCanBeBlocked(ctx: HookContext) {
        val clazz = Reflect.findClass(NOTIFICATION_BACKEND, ctx.classLoader)
        Reflect.hookAllMethods(ctx.api, clazz, "recordCanBeBlocked") { chain ->
            val result = chain.proceed()
            val appRow = chain.getArgs().lastOrNull() ?: return@hookAllMethods result
            try {
                Reflect.setBooleanField(appRow, "lockedImportance", false)
                Reflect.setBooleanField(appRow, "permissionStateLocked", false)
                Reflect.setBooleanField(appRow, "systemApp", false)
            } catch (_: Throwable) {}
            return@hookAllMethods result
        }
        Logger.i(TAG, "已挂载 recordCanBeBlocked")
    }

    private fun hookGetNotificationsBanned(ctx: HookContext) {
        val clazz = Reflect.findClass(NOTIFICATION_BACKEND, ctx.classLoader)
        Reflect.hookAllMethods(ctx.api, clazz, "getNotificationsBanned") { chain ->
            chain.proceed()
            false
        }
        Logger.i(TAG, "已挂载 getNotificationsBanned")
    }
}