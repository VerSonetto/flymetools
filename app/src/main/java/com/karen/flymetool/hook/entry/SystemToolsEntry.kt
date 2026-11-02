package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object SystemToolsEntry : HookEntry {
    override val targetPackage = "com.flyme.systemuitools"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "SlideGestureMultiArcHook" to { com.karen.flymetool.hook.feature.systemtools.SlideGestureMultiArcHook },
        "SlideGestureSearchEntryHook" to { com.karen.flymetool.hook.feature.systemtools.SlideGestureSearchEntryHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
