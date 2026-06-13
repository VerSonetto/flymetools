package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object StatusBarClockSecondsHook : FeatureHook {

    private const val CLOCK_CLASS = "com.android.systemui.statusbar.policy.Clock"
    private const val HOOK_NAME = "StatusBarClockSeconds"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "statusbar_clock_seconds")) return

        try {
            val clockClass = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(clockClass, "onTuningChanged", String::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ("clock_seconds" == param.args[0] as String) {
                        param.args[1] = "1"
                    }
                }
            })
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Primary hook failed", e)
            tryFallbackHook(lpparam)
        }
    }

    private fun tryFallbackHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clockClass = XposedHelpers.findClass(CLOCK_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(clockClass, "onAttachedToWindow", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XposedHelpers.setBooleanField(param.thisObject, "mShowSeconds", true)
                }
            })

            XposedHelpers.findAndHookMethod(clockClass, "updateShowSeconds", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XposedHelpers.setBooleanField(param.thisObject, "mShowSeconds", true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    XposedHelpers.callMethod(param.thisObject, "updateClock")
                }
            })
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Fallback hook also failed", e)
        }
    }
}
