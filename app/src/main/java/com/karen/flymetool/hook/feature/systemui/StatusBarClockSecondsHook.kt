package com.karen.flymetool.hook.feature.systemui

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object StatusBarClockSecondsHook : FeatureHook {

    private const val CLOCK_CLASS = "com.android.systemui.statusbar.policy.Clock"
    private const val TAG = "StatusBarClockSeconds"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("statusbar_clock_seconds")) return

        try {
            val clockClass = Reflect.findClass(CLOCK_CLASS, ctx.classLoader)

            Reflect.hookMethodOn(ctx.api, clockClass, "onTuningChanged", String::class.java, String::class.java) { chain ->
                if ("clock_seconds" == chain.getArg(0) as String) {
                    val args = chain.getArgs().toTypedArray()
                    args[1] = "1"
                    chain.proceed(args)
                } else {
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "主 Hook 失败，尝试回退方案", e)
            tryFallbackHook(ctx)
        }
    }

    private fun tryFallbackHook(ctx: HookContext) {
        try {
            val clockClass = Reflect.findClass(CLOCK_CLASS, ctx.classLoader)

            Reflect.hookMethodOn(ctx.api, clockClass, "onAttachedToWindow") { chain ->
                Reflect.setBooleanField(chain.getThisObject(), "mShowSeconds", true)
                chain.proceed()
            }

            Reflect.hookMethodOn(ctx.api, clockClass, "updateShowSeconds") { chain ->
                Reflect.setBooleanField(chain.getThisObject(), "mShowSeconds", true)
                val result = chain.proceed()
                Reflect.callMethod(ctx.api, chain.getThisObject(), "updateClock")
                result
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "回退 Hook 也失败", e)
        }
    }
}