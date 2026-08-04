package com.karen.flymetool.hook.feature.systemui

import android.telephony.SubscriptionManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object ShowDataSimOnlyHook : FeatureHook {

    private const val MOBILE_SIGNAL_CONTROLLER = "com.android.systemui.statusbar.connectivity.MobileSignalController"
    private const val SIGNAL_CALLBACK = "com.android.systemui.statusbar.connectivity.SignalCallback"
    private const val CELLULAR_ICON_VIEW_MODEL = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.CellularIconViewModel"
    private const val MOBILE_ICON_INTERACTOR = "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor"
    private const val AIRPLANE_MODE_INTERACTOR = "com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor"
    private const val CONNECTIVITY_CONSTANTS = "com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants"
    private const val TAG = "ShowDataSimOnly"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "show_data_sim_only")) return
        if (lpparam.packageName != "com.android.systemui") return

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(lpparam)
            FlymeVersionUtils.isFlyme11() -> hookNotifyListeners(lpparam, "Flyme 11")
            FlymeVersionUtils.isFlyme10() -> hookNotifyListeners(lpparam, "Flyme 10")
            else -> hookNotifyListeners(lpparam, "Flyme 10")
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
                            Logger.d(TAG) { "Pipeline: 拦截 subId=$subId, active=$activeSubId" }
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载 Flyme 12 Pipeline: CellularIconViewModel")
        } catch (e: Throwable) {
            Logger.e(TAG, "Pipeline Hook 失败（Flyme 12）", e)
        }
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

            Logger.i(TAG, "已挂载 $tag: MobileSignalController.notifyListeners")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 $tag 失败", e)
        }
    }
}
