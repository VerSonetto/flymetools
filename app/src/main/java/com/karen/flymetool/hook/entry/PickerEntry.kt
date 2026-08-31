package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object PickerEntry : HookEntry {
    override val targetPackage = "com.meizu.picker"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "CustomBrowserHook" to { com.karen.flymetool.hook.feature.picker.CustomBrowserHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
