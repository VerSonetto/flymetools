package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object CustomizeCenterEntry : HookEntry {
    override val targetPackage = "com.meizu.customizecenter"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "ForceFreeThemeHook" to { com.karen.flymetool.hook.feature.customizecenter.ForceFreeThemeHook },
        "ForceFreeFontHook" to { com.karen.flymetool.hook.feature.customizecenter.ForceFreeFontHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
