package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object PickerEntry : HookEntry {
    override val targetPackage = "com.meizu.picker"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "CustomBrowserHook" to { com.karen.flymetool.hook.feature.picker.CustomBrowserHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
