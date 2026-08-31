package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object BatteryEntry : HookEntry {
    override val targetPackage = "com.meizu.battery"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "BatteryHealthStatusHook" to { com.karen.flymetool.hook.feature.battery.BatteryHealthStatusHook },
        "CustomChargeLimitHook" to { com.karen.flymetool.hook.feature.battery.CustomChargeLimitHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
