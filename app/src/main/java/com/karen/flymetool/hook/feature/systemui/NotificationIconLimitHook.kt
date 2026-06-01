package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object NotificationIconLimitHook : FeatureHook {

    private const val HOOK_NAME = "NotificationIconLimit"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "notification_icon_limit")) return
        if (lpparam.packageName != "com.android.systemui") return

        val maxIcons = XposedPrefs.getFeatureValue(lpparam, packageName, "notification_icon_limit", 4)
        mount(lpparam, maxIcons)
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam, maxIcons: Int) {
        try {
            val notificationIconContainerClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                notificationIconContainerClass,
                "initResources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thisObject = param.thisObject
                        XposedHelpers.setObjectField(thisObject, "mMaxStaticIcons", maxIcons)
                        Logger.once(HOOK_NAME, "mMaxStaticIcons = $maxIcons")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked NotificationIconContainer.initResources")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
        }
    }
}
