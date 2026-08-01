package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object BatteryEntry : HookEntry {
    override val targetPackage = "com.meizu.battery"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "BatteryHealthStatusHook" to { com.karen.flymetool.hook.feature.battery.BatteryHealthStatusHook },
        "CustomChargeLimitHook" to { com.karen.flymetool.hook.feature.battery.CustomChargeLimitHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
