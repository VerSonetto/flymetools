package com.karen.flymetool.hook.feature.systemui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.AttributeSet
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import java.util.Collections
import java.util.HashSet

object NotificationManageHook : FeatureHook {

    private const val NOTIFICATION_INFO = "com.android.systemui.statusbar.notification.row.NotificationInfo"
    private const val DEVELOPER_SETTINGS_CONTROLLER = "com.flyme.developer.DeveloperSettingsController"
    private const val TAG = "NotificationManage"

    /** DeveloperSettingsController uses a localized resource string as the channel id. */
    private val developerChannelIds: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("notification_manage")) return
        if (ctx.packageName != "com.android.systemui") return

        hookNotificationInfo(ctx)
        hookDeveloperChannelId(ctx)
        hookDeveloperChannelCreation(ctx)
    }

    private fun hookNotificationInfo(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(NOTIFICATION_INFO, ctx.classLoader)

            Reflect.hookConstructorOn(
                ctx.api,
                clazz,
                Context::class.java,
                AttributeSet::class.java,
            ) { chain ->
                val result = chain.proceed()
                val notificationInfo = chain.getThisObject()
                Reflect.setObjectField(notificationInfo, "mDisableManagerPkgList", emptyList<String>())
                Logger.d(TAG) { "已清除 mDisableManagerPkgList" }
                result
            }

            Reflect.hookAllMethods(ctx.api, clazz, "bindNotification", excluded = { false }) { chain ->
                val result = chain.proceed()
                try {
                    val notificationInfo = chain.getThisObject()
                    val channel = Reflect.getObjectField(
                        notificationInfo,
                        "mSingleNotificationChannel"
                    ) as? NotificationChannel ?: return@hookAllMethods result
                    if (!isDeveloperChannel(channel)) return@hookAllMethods result

                    channel.setBlockable(true)
                    Reflect.setBooleanField(notificationInfo, "mIsNonblockable", false)
                    Logger.d(TAG) { "已解除开发者通知频道不可阻止状态" }
                    result
                } catch (t: Throwable) {
                    Logger.e(TAG, "处理开发者通知频道状态失败", t)
                    result
                }
            }

            Logger.i(TAG, "已挂载 NotificationInfo 构造器与 bindNotification")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 NotificationInfo 失败", e)
        }
    }

    private fun hookDeveloperChannelId(ctx: HookContext) {
        try {
            val controllerClass = Reflect.findClass(
                DEVELOPER_SETTINGS_CONTROLLER,
                ctx.classLoader
            )
            Reflect.hookMethodOn(ctx.api, controllerClass, "initRes") { chain ->
                val result = chain.proceed()
                try {
                    val channelId = Reflect.getObjectField(
                        chain.getThisObject(),
                        "mDevNotiId"
                    ) as? String ?: return@hookMethodOn result
                    if (channelId.isNotEmpty()) {
                        developerChannelIds.add(channelId)
                        Logger.d(TAG) { "记录开发者通知频道 ID" }
                    }
                    result
                } catch (t: Throwable) {
                    Logger.e(TAG, "记录开发者通知频道 ID 失败", t)
                    result
                }
            }
            Logger.i(TAG, "已挂载 DeveloperSettingsController.initRes")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载开发者通知频道识别失败", e)
        }
    }

    private fun hookDeveloperChannelCreation(ctx: HookContext) {
        try {
            Reflect.hookAllMethods(
                ctx.api,
                NotificationManager::class.java,
                "createNotificationChannel",
                excluded = { false }
            ) { chain ->
                val channel = chain.getArgs().firstOrNull() as? NotificationChannel ?: return@hookAllMethods chain.proceed()
                if (!isDeveloperChannel(channel)) return@hookAllMethods chain.proceed()
                try {
                    channel.setBlockable(true)
                    Logger.d(TAG) { "开发者通知频道已强制设为可阻止" }
                    chain.proceed()
                } catch (t: Throwable) {
                    Logger.e(TAG, "处理开发者通知频道创建失败", t)
                    chain.proceed()
                }
            }
            Logger.i(TAG, "已挂载 NotificationManager.createNotificationChannel")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载开发者通知频道创建失败", e)
        }
    }

    private fun isDeveloperChannel(channel: NotificationChannel): Boolean =
        developerChannelIds.contains(channel.id)
}