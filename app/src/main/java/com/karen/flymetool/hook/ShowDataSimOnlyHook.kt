package com.karen.flymetool.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object ShowDataSimOnlyHook {

    private const val MOBILE_SIGNAL_CONTROLLER = "com.android.systemui.statusbar.connectivity.MobileSignalController"
    private const val HOOK_NAME = "ShowDataSimOnly"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val clazz = XposedHelpers.findClass(MOBILE_SIGNAL_CONTROLLER, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "notifyListeners",
                "com.android.systemui.statusbar.connectivity.SignalCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val currentState = XposedHelpers.getObjectField(param.thisObject, "mCurrentState")
                        val dataSim = XposedHelpers.getBooleanField(currentState, "dataSim")

                        if (!dataSim) {
                            param.result = null
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked MobileSignalController.notifyListeners")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
        }
    }
}
