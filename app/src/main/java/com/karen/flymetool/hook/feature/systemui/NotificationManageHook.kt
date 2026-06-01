package com.karen.flymetool.hook.feature.systemui

import android.content.Context
import android.util.AttributeSet
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object NotificationManageHook : FeatureHook {

    private const val NOTIFICATION_INFO = "com.android.systemui.statusbar.notification.row.NotificationInfo"
    private const val HOOK_NAME = "NotificationManage"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "notification_manage")) return
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
