package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object FlymeUpdateEntry : HookEntry {
    override val targetPackage = "com.meizu.flyme.update"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "DisableUpdateCheckHook" to { com.karen.flymetool.hook.feature.flymeupdate.DisableUpdateCheckHook },
        "ForceFullPackageHook" to { com.karen.flymetool.hook.feature.flymeupdate.ForceFullPackageHook },
        "CaptureUpdateLinkHook" to { com.karen.flymetool.hook.feature.flymeupdate.CaptureUpdateLinkHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
