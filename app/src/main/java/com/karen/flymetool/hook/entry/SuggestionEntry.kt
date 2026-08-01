package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SuggestionEntry : HookEntry {
    override val targetPackage = "com.meizu.suggestion"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "CustomBrowserHook" to { com.karen.flymetool.hook.feature.suggestion.CustomBrowserHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
