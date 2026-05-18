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
    private const val CELLULAR_ICON_VIEW_MODEL = "com.android.systemui.statusbar.pipeline.mobile.p114ui.viewmodel.CellularIconViewModel"
    private const val MOBILE_ICON_INTERACTOR = "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor"
    private const val AIRPLANE_MODE_INTERACTOR = "com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor"
    private const val CONNECTIVITY_CONSTANTS = "com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants"
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
        hookNotifyListeners(lpparam, "Flyme 12")

        try {
            val vmClass = XposedHelpers.findClass(CELLULAR_ICON_VIEW_MODEL, lpparam.classLoader)
            val interactorClass = XposedHelpers.findClass(MOBILE_ICON_INTERACTOR, lpparam.classLoader)
            val airplaneClass = XposedHelpers.findClass(AIRPLANE_MODE_INTERACTOR, lpparam.classLoader)
            val constantsClass = XposedHelpers.findClass(CONNECTIVITY_CONSTANTS, lpparam.classLoader)
            val scopeClass = XposedHelpers.findClass("kotlinx.coroutines.CoroutineScope", lpparam.classLoader)

            XposedHelpers.findAndHookConstructor(
                vmClass,
                Integer.TYPE,
                interactorClass,
                airplaneClass,
                constantsClass,
                scopeClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val subId = param.args[0] as Int
                        val activeSubId = SubscriptionManager.getActiveDataSubscriptionId()

                        if (SubscriptionManager.isValidSubscriptionId(activeSubId) && subId != activeSubId) {
                            val stateFlowKt = XposedHelpers.findClass(
                                "kotlinx.coroutines.flow.StateFlowKt",
                                lpparam.classLoader
                            )
                            val falseFlow = XposedHelpers.callStaticMethod(
                                stateFlowKt,
                                "MutableStateFlow",
                                java.lang.Boolean.FALSE
                            )
                            XposedHelpers.setObjectField(param.thisObject, "isVisible", falseFlow)
                            Logger.i(HOOK_NAME, "Pipeline: blocked subId=$subId, active=$activeSubId")
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked Flyme 12 Pipeline: CellularIconViewModel")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Pipeline hook failed for Flyme 12", e)
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
