package com.karen.flymetool.hook.feature.systemui

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object PulldownAreaRatioHook : FeatureHook {

    private const val TAG = "PulldownAreaRatio"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("pulldown_area_ratio")) return
        if (ctx.packageName != "com.android.systemui") return

        val ratio = ctx.featureValue("pulldown_area_ratio", 50)
        mount(ctx, ratio)
    }

    private fun mount(ctx: HookContext, controlCenterRatio: Int) {
        try {
            val centerControllerClass = Reflect.findClass(
                "com.flyme.systemui.controlcenter.phone.CenterController",
                ctx.classLoader
            )

            Reflect.hookMethodOn(ctx.api, centerControllerClass, "updateResources") { chain ->
                val result = chain.proceed()
                val thisObject = chain.getThisObject()
                val context = Reflect.getObjectField(thisObject, "mContext") as android.content.Context
                val dm = context.resources.displayMetrics
                val widthPixels = dm.widthPixels

                val newRegion = when (controlCenterRatio) {
                    25 -> widthPixels * 3 / 4
                    50 -> widthPixels / 2
                    75 -> widthPixels / 4
                    else -> widthPixels * 3 / 4
                }

                Reflect.setObjectField(thisObject, "mHandleEventRegion", newRegion)
                Logger.once(TAG, "event_region", "mHandleEventRegion = $newRegion (ratio=$controlCenterRatio%)")
                result
            }

            Logger.i(TAG, "已挂载 CenterController.updateResources")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }
}