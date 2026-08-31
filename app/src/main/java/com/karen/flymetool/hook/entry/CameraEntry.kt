package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object CameraEntry : HookEntry {
    override val targetPackage = "com.meizu.media.camera"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "FilterMemoryHook" to { com.karen.flymetool.hook.feature.camera.FilterMemoryHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
