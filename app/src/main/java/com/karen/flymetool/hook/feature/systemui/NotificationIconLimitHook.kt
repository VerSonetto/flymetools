package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger

object NotificationIconLimitHook {

    private const val HOOK_NAME = "NotificationIconLimit"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, maxIcons: Int) {
        if (lpparam.packageName != "com.android.systemui") return

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
