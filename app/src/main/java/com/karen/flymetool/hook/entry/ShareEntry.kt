package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object ShareEntry : HookEntry {
    override val targetPackage = "com.meizu.share"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "AutoAcceptShareHook" to { com.karen.flymetool.hook.feature.share.AutoAcceptShareHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
