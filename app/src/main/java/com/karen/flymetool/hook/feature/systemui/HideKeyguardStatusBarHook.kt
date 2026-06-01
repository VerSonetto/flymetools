package com.karen.flymetool.hook.feature.systemui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object HideKeyguardStatusBarHook : FeatureHook {

    private const val VIEW_CLASS = "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
    private const val HOOK_NAME = "HideKeyguardStatusBar"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_keyguard_status_bar")) return
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val clazz = XposedHelpers.findClass(VIEW_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = View.GONE
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked KeyguardStatusBarView.setVisibility")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
        }
    }
}
