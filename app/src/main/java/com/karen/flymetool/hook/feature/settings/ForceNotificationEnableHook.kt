package com.karen.flymetool.hook.feature.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.XC_MethodHook
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

        XposedHelpers.findAndHookMethod(
            clazz,
            "enableSwitch",
            Context::class.java,
            ApplicationInfo::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                    Logger.d(HOOK_NAME, "enableSwitch forced to true")
                }
            }
        )

        Logger.i(HOOK_NAME, "Hooked enableSwitch")
    }

    private fun hookRecordCanBeBlocked(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(NOTIFICATION_BACKEND, lpparam.classLoader)
        val appRowClass = XposedHelpers.findClass("com.android.settings.notification.NotificationBackend\$AppRow", lpparam.classLoader)

        XposedHelpers.findAndHookMethod(
            clazz,
            "recordCanBeBlocked",
            android.content.pm.PackageInfo::class.java,
            appRowClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val appRow = param.args[1]
                    XposedHelpers.setBooleanField(appRow, "lockedImportance", false)
                    XposedHelpers.setBooleanField(appRow, "permissionStateLocked", false)
                    XposedHelpers.setBooleanField(appRow, "systemApp", false)
                    Logger.d(HOOK_NAME, "recordCanBeBlocked: unlocked importance and permission")
                }
            }
        )

        Logger.i(HOOK_NAME, "Hooked recordCanBeBlocked")
    }

    private fun hookGetNotificationsBanned(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClass(NOTIFICATION_BACKEND, lpparam.classLoader)

        XposedHelpers.findAndHookMethod(
            clazz,
            "getNotificationsBanned",
            String::class.java,
            Int::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = false
                    Logger.d(HOOK_NAME, "getNotificationsBanned forced to false for ${param.args[0]}")
                }
            }
        )

        Logger.i(HOOK_NAME, "Hooked getNotificationsBanned")
    }
}
