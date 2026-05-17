package com.karen.flymetool.hook.feature.systemui

import android.telephony.SubscriptionManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.util.FlymeVersionUtils

object ShowDataSimOnlyHook {

    private const val MOBILE_SIGNAL_CONTROLLER = "com.android.systemui.statusbar.connectivity.MobileSignalController"
    private const val SIGNAL_CALLBACK = "com.android.systemui.statusbar.connectivity.SignalCallback"
    private const val CALLBACK_HANDLER = "com.android.systemui.statusbar.connectivity.CallbackHandler"
    private const val MOBILE_DATA_INDICATORS = "com.android.systemui.statusbar.connectivity.MobileDataIndicators"
    private const val HOOK_NAME = "ShowDataSimOnly"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(lpparam)
            FlymeVersionUtils.isFlyme11() -> hookFlyme11(lpparam)
            FlymeVersionUtils.isFlyme10() -> hookFlyme10(lpparam)
            else -> hookFlyme10(lpparam)
        }
    }

    private fun hookFlyme12(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val handlerClass = XposedHelpers.findClass(CALLBACK_HANDLER, lpparam.classLoader)
            val indicatorsClass = XposedHelpers.findClass(MOBILE_DATA_INDICATORS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                handlerClass,
                "setMobileDataIndicators",
                indicatorsClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val indicators = param.args[0] ?: return
                        val subId = XposedHelpers.getIntField(indicators, "subId")
                        val activeSubId = SubscriptionManager.getActiveDataSubscriptionId()

                        if (SubscriptionManager.isValidSubscriptionId(activeSubId) && subId != activeSubId) {
                            param.result = null
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked Flyme 12: CallbackHandler.setMobileDataIndicators")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook Flyme 12 failed", e)
        }
    }

    private fun hookFlyme11(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookNotifyListeners(lpparam, "Flyme 11")
    }

    private fun hookFlyme10(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookNotifyListeners(lpparam, "Flyme 10")
    }

    private fun hookNotifyListeners(lpparam: XC_LoadPackage.LoadPackageParam, tag: String) {
        try {
            val controllerClass = XposedHelpers.findClass(MOBILE_SIGNAL_CONTROLLER, lpparam.classLoader)
            val callbackClass = XposedHelpers.findClass(SIGNAL_CALLBACK, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                controllerClass,
                "notifyListeners",
                callbackClass,
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

            Logger.i(HOOK_NAME, "Hooked $tag: MobileSignalController.notifyListeners")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook $tag failed", e)
        }
    }
}
