package com.karen.flymetool.hook.feature.systemui

import android.telephony.SubscriptionManager
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

object ShowDataSimOnlyHook : FeatureHook {

    private const val MOBILE_SIGNAL_CONTROLLER = "com.android.systemui.statusbar.connectivity.MobileSignalController"
    private const val SIGNAL_CALLBACK = "com.android.systemui.statusbar.connectivity.SignalCallback"
    private const val CELLULAR_ICON_VIEW_MODEL = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.CellularIconViewModel"
    private const val MOBILE_ICON_INTERACTOR = "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor"
    private const val AIRPLANE_MODE_INTERACTOR = "com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor"
    private const val CONNECTIVITY_CONSTANTS = "com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants"
    private const val TAG = "ShowDataSimOnly"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("show_data_sim_only")) return
        if (ctx.packageName != "com.android.systemui") return

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(ctx)
            FlymeVersionUtils.isFlyme11() -> hookNotifyListeners(ctx, "Flyme 11")
            FlymeVersionUtils.isFlyme10() -> hookNotifyListeners(ctx, "Flyme 10")
            else -> hookNotifyListeners(ctx, "Flyme 10")
        }
    }

    private fun hookFlyme12(ctx: HookContext) {
        hookNotifyListeners(ctx, "Flyme 12")

        try {
            val vmClass = Reflect.findClass(CELLULAR_ICON_VIEW_MODEL, ctx.classLoader)
            val interactorClass = Reflect.findClass(MOBILE_ICON_INTERACTOR, ctx.classLoader)
            val airplaneClass = Reflect.findClass(AIRPLANE_MODE_INTERACTOR, ctx.classLoader)
            val constantsClass = Reflect.findClass(CONNECTIVITY_CONSTANTS, ctx.classLoader)
            val scopeClass = Reflect.findClass("kotlinx.coroutines.CoroutineScope", ctx.classLoader)

            Reflect.hookConstructorOn(
                ctx.api,
                vmClass,
                Integer.TYPE,
                interactorClass,
                airplaneClass,
                constantsClass,
                scopeClass,
            ) { chain ->
                val result = chain.proceed()
                val subId = chain.getArg(0) as Int
                val activeSubId = SubscriptionManager.getActiveDataSubscriptionId()

                if (SubscriptionManager.isValidSubscriptionId(activeSubId) && subId != activeSubId) {
                    val stateFlowKt = Reflect.findClass(
                        "kotlinx.coroutines.flow.StateFlowKt",
                        ctx.classLoader
                    )
                    val falseFlow = Reflect.callStaticMethod(
                        ctx.api,
                        stateFlowKt,
                        "MutableStateFlow",
                        java.lang.Boolean.FALSE
                    )
                    Reflect.setObjectField(chain.getThisObject(), "isVisible", falseFlow)
                    Logger.d(TAG) { "Pipeline: 拦截 subId=$subId, active=$activeSubId" }
                }
                result
            }

            Logger.i(TAG, "已挂载 Flyme 12 Pipeline: CellularIconViewModel")
        } catch (e: Throwable) {
            Logger.e(TAG, "Pipeline Hook 失败（Flyme 12）", e)
        }
    }

    private fun hookNotifyListeners(ctx: HookContext, tag: String) {
        try {
            val controllerClass = Reflect.findClass(MOBILE_SIGNAL_CONTROLLER, ctx.classLoader)
            val callbackClass = Reflect.findClass(SIGNAL_CALLBACK, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                controllerClass,
                "notifyListeners",
                callbackClass,
            ) { chain ->
                val currentState = Reflect.getObjectField(chain.getThisObject(), "mCurrentState")
                val dataSim = currentState?.let { Reflect.getBooleanField(it, "dataSim") } ?: true

                if (!dataSim) {
                    null
                } else {
                    chain.proceed()
                }
            }

            Logger.i(TAG, "已挂载 $tag: MobileSignalController.notifyListeners")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 $tag 失败", e)
        }
    }
}