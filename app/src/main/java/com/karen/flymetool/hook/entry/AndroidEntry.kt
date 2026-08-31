package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object AndroidEntry : HookEntry {
    override val targetPackage = "android"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "SuperStereoSoundHook" to { com.karen.flymetool.hook.feature.android.SuperStereoSoundHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
