package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object FlymeUpdateEntry : HookEntry {
    override val targetPackage = "com.meizu.flyme.update"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "DisableUpdateCheckHook" to { com.karen.flymetool.hook.feature.flymeupdate.DisableUpdateCheckHook },
        "ForceFullPackageHook" to { com.karen.flymetool.hook.feature.flymeupdate.ForceFullPackageHook },
        "CaptureUpdateLinkHook" to { com.karen.flymetool.hook.feature.flymeupdate.CaptureUpdateLinkHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
