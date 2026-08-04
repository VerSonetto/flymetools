package com.karen.flymetool.hook.feature.settings

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object ForceNotificationEnableHook : FeatureHook {

    private const val NOTIFICATION_BACKEND = "com.android.settings.notification.NotificationBackend"
    private const val TAG = "ForceNotificationEnable"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "force_notification_enable")) return
        if (lpparam.packageName != "com.android.settings") return

        try {
            hookEnableSwitch(lpparam)
            hookRecordCanBeBlocked(lpparam)
            hookGetNotificationsBanned(lpparam)

            Logger.i(TAG, "全部 Hook 安装成功")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }

    private fun hookEnableSwitch(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(NOTIFICATION_BACKEND, lpparam.classLoader)
        XposedBridge.hookAllMethods(clazz, "enableSwitch", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = true
            }
        })
        Logger.i(TAG, "已挂载 enableSwitch")
    }

    private fun hookRecordCanBeBlocked(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(NOTIFICATION_BACKEND, lpparam.classLoader)
        XposedBridge.hookAllMethods(clazz, "recordCanBeBlocked", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val appRow = param.args.lastOrNull() ?: return
                try {
                    XposedHelpers.setBooleanField(appRow, "lockedImportance", false)
                    XposedHelpers.setBooleanField(appRow, "permissionStateLocked", false)
                    XposedHelpers.setBooleanField(appRow, "systemApp", false)
                } catch (_: Throwable) {}
            }
        })
        Logger.i(TAG, "已挂载 recordCanBeBlocked")
    }

    private fun hookGetNotificationsBanned(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(NOTIFICATION_BACKEND, lpparam.classLoader)
        XposedBridge.hookAllMethods(clazz, "getNotificationsBanned", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = false
            }
        })
        Logger.i(TAG, "已挂载 getNotificationsBanned")
    }
}
