package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.battery.BatteryHealthStatusHook
import com.karen.flymetool.hook.feature.battery.CustomChargeLimitHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object BatteryEntry : HookEntry {
    override val targetPackage = "com.meizu.battery"

    private val hooks: List<FeatureHook> = listOf(
        BatteryHealthStatusHook,
        CustomChargeLimitHook,
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (hook in hooks) {
            try {
                hook.handle(lpparam, targetPackage)
            } catch (e: Throwable) {
                Logger.e(targetPackage, "Hook ${hook::class.simpleName} failed", e)
            }
        }
    }
}
