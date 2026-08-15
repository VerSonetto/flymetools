package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SearchEntry : HookEntry {
    override val targetPackage = "com.meizu.net.search"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "CustomSearchEngineHook" to { com.karen.flymetool.hook.feature.search.CustomSearchEngineHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
