package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object ShareEntry : HookEntry {
    override val targetPackage = "com.meizu.share"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "AutoAcceptShareHook" to { com.karen.flymetool.hook.feature.share.AutoAcceptShareHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
