package com.karen.flymetool.hook

import android.content.Context
import android.util.AttributeSet
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object NotificationManageHook {

    private const val NOTIFICATION_INFO = "com.android.systemui.statusbar.notification.row.NotificationInfo"
    private const val HOOK_NAME = "NotificationManage"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

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
                        Logger.d(HOOK_NAME, "Cleared mDisableManagerPkgList")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked NotificationInfo constructor")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
        }
    }
}
