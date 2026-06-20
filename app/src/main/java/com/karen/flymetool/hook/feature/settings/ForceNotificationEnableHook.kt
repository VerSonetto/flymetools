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
    private const val HOOK_NAME = "ForceNotificationEnable"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "force_notification_enable")) return
        if (lpparam.packageName != "com.android.settings") return

        try {
            hookEnableSwitch(lpparam)
            hookRecordCanBeBlocked(lpparam)
            hookGetNotificationsBanned(lpparam)

            Logger.i(HOOK_NAME, "All hooks installed successfully")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
        }
    }

    private fun hookEnableSwitch(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(NOTIFICATION_BACKEND, lpparam.classLoader)
        XposedBridge.hookAllMethods(clazz, "enableSwitch", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = true
            }
        })
        Logger.i(HOOK_NAME, "Hooked enableSwitch")
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
        Logger.i(HOOK_NAME, "Hooked recordCanBeBlocked")
    }

    private fun hookGetNotificationsBanned(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(NOTIFICATION_BACKEND, lpparam.classLoader)
        XposedBridge.hookAllMethods(clazz, "getNotificationsBanned", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = false
            }
        })
        Logger.i(HOOK_NAME, "Hooked getNotificationsBanned")
    }
}
