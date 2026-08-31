package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object SystemUIEditorEntry : HookEntry {
    override val targetPackage = "com.flyme.systemuieditor"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "ClassicClockDepthHook" to {
            com.karen.flymetool.hook.feature.systemuieditor.ClassicClockDepthHook
        },
        "ForceFullscreenAodHook" to {
            com.karen.flymetool.hook.feature.systemuieditor.ForceFullscreenAodHook
        },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
