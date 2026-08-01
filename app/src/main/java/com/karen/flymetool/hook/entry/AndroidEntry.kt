package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object AndroidEntry : HookEntry {
    override val targetPackage = "android"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "SuperStereoSoundHook" to { com.karen.flymetool.hook.feature.android.SuperStereoSoundHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
