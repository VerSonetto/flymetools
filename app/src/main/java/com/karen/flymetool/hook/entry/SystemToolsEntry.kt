package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SystemToolsEntry : HookEntry {
    override val targetPackage = "com.flyme.systemuitools"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "SlideGestureMultiArcHook" to { com.karen.flymetool.hook.feature.systemtools.SlideGestureMultiArcHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
