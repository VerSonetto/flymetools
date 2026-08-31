package com.karen.flymetool.hook.feature.systemui

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object ConnectionRateLowSpeedHideHook : FeatureHook {

    private const val CONNECTION_RATE_VIEW = "com.flyme.statusbar.connectionRateView.ConnectionRateView"
    private const val TAG = "ConnectionRateLowSpeedHide"

    private var thresholdKbPerS: Int = 10
    private var lastHiddenState = false

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("connection_rate_low_speed_hide")) return
        if (ctx.packageName != "com.android.systemui") return

        val threshold = ctx.featureValue("connection_rate_low_speed_hide", 10)
        mount(ctx, threshold)
    }

    private fun mount(ctx: HookContext, threshold: Int) {
        thresholdKbPerS = threshold
        lastHiddenState = false
        hookOnConnectionRateChange(ctx)
        Logger.i(TAG, "已加载，阈值=${thresholdKbPerS}KB/s")
    }

    private fun hookOnConnectionRateChange(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(CONNECTION_RATE_VIEW, ctx.classLoader)

            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "onConnectionRateChange",
                Boolean::class.java,
                Double::class.java,
            ) { chain ->
                val array = chain.getArgs().toMutableList()
                val rate = array[1] as Double

                if (array[0] as Boolean) {
                    val shouldHide = rate < thresholdKbPerS
                    if (shouldHide) {
                        array[0] = false
                        // 只在“显示 -> 隐藏”状态切换时打日志，避免每次网速回调都刷屏
                        if (!lastHiddenState) {
                            lastHiddenState = true
                            Logger.d(TAG) { "隐藏低速率: rate=${rate.toInt()}KB/s < 阈值=${thresholdKbPerS}KB/s" }
                        }
                    } else if (lastHiddenState) {
                        lastHiddenState = false
                        Logger.d(TAG) { "恢复显示低速率: rate=${rate.toInt()}KB/s >= 阈值=${thresholdKbPerS}KB/s" }
                    }
                }
                chain.proceed(array.toTypedArray())
            }

            Logger.i(TAG, "已挂载 ConnectionRateView.onConnectionRateChange")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }
}