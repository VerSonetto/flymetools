package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object SearchEntry : HookEntry {
    override val targetPackage = "com.meizu.net.search"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "CustomSearchEngineHook" to { com.karen.flymetool.hook.feature.search.CustomSearchEngineHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
