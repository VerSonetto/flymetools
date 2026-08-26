package com.karen.flymetool.hook.feature.systemui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.AttributeSet
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import java.util.Collections
import java.util.HashSet

object NotificationManageHook : FeatureHook {

    private const val NOTIFICATION_INFO = "com.android.systemui.statusbar.notification.row.NotificationInfo"
    private const val DEVELOPER_SETTINGS_CONTROLLER = "com.flyme.developer.DeveloperSettingsController"
    private const val TAG = "NotificationManage"

    /** DeveloperSettingsController uses a localized resource string as the channel id. */
    private val developerChannelIds: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "notification_manage")) return
        if (lpparam.packageName != "com.android.systemui") return

        hookNotificationInfo(lpparam)
        hookDeveloperChannelId(lpparam)
        hookDeveloperChannelCreation()
    }

    private fun hookNotificationInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(NOTIFICATION_INFO, lpparam.classLoader)

            XposedHelpers.findAndHookConstructor(
                clazz,
                Context::class.java,
                AttributeSet::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val notificationInfo = param.thisObject
                        XposedHelpers.setObjectField(notificationInfo, "mDisableManagerPkgList", emptyList<String>())
                        Logger.d(TAG) { "已清除 mDisableManagerPkgList" }
                    }
                }
            )

            XposedBridge.hookAllMethods(clazz, "bindNotification", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val notificationInfo = param.thisObject
                        val channel = XposedHelpers.getObjectField(
                            notificationInfo,
                            "mSingleNotificationChannel"
                        ) as? NotificationChannel ?: return
                        if (!isDeveloperChannel(channel)) return

                        channel.setBlockable(true)
                        XposedHelpers.setBooleanField(notificationInfo, "mIsNonblockable", false)
                        Logger.d(TAG) { "已解除开发者通知频道不可阻止状态" }
                    } catch (t: Throwable) {
                        Logger.e(TAG, "处理开发者通知频道状态失败", t)
                    }
                }
            })

            Logger.i(TAG, "已挂载 NotificationInfo 构造器与 bindNotification")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 NotificationInfo 失败", e)
        }
    }

    private fun hookDeveloperChannelId(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val controllerClass = XposedHelpers.findClass(
                DEVELOPER_SETTINGS_CONTROLLER,
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                controllerClass,
                "initRes",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val channelId = XposedHelpers.getObjectField(
                                param.thisObject,
                                "mDevNotiId"
                            ) as? String ?: return
                            if (channelId.isNotEmpty()) {
                                developerChannelIds.add(channelId)
                                Logger.d(TAG) { "记录开发者通知频道 ID" }
                            }
                        } catch (t: Throwable) {
                            Logger.e(TAG, "记录开发者通知频道 ID 失败", t)
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 DeveloperSettingsController.initRes")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载开发者通知频道识别失败", e)
        }
    }

    private fun hookDeveloperChannelCreation() {
        try {
            XposedBridge.hookAllMethods(
                NotificationManager::class.java,
                "createNotificationChannel",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val channel = param.args.firstOrNull() as? NotificationChannel ?: return
                            if (!isDeveloperChannel(channel)) return
                            channel.setBlockable(true)
                            Logger.d(TAG) { "开发者通知频道已强制设为可阻止" }
                        } catch (t: Throwable) {
                            Logger.e(TAG, "处理开发者通知频道创建失败", t)
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 NotificationManager.createNotificationChannel")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载开发者通知频道创建失败", e)
        }
    }

    private fun isDeveloperChannel(channel: NotificationChannel): Boolean =
        developerChannelIds.contains(channel.id)
}
