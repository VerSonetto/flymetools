package com.karen.flymetool.hook.feature.systemui

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object HideChargingAnimationHook : FeatureHook {

    private const val CHARGE_ANIMATION_CONTROLLER = "com.flyme.keyguard.charging.ChargeAnimationController"
    private const val TAG = "HideChargingAnimation"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("hide_charging_animation")) return
        if (ctx.packageName != "com.android.systemui") return

        hookStartWireAnimation(ctx)
        Logger.i(TAG, "已加载")
    }

    private fun hookStartWireAnimation(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(CHARGE_ANIMATION_CONTROLLER, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "startWireAnimation",
            ) { chain ->
                Logger.d(TAG) { "拦截充电动画" }

                val thisObject = chain.getThisObject()

                Reflect.setBooleanField(thisObject, "mAnimationStarted", true)

                val mHandler = Reflect.getObjectField(thisObject, "mHandler") as? android.os.Handler
                val mRemoveWindow = Reflect.getObjectField(thisObject, "mRemoveWindow") as Runnable?

                mRemoveWindow?.let { mHandler?.postDelayed(it, 100) }

                null
            }

            Logger.i(TAG, "已挂载 ChargeAnimationController.startWireAnimation")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }
}