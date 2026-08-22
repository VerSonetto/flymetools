package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SystemUIEditorEntry : HookEntry {
    override val targetPackage = "com.flyme.systemuieditor"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "ForceFullscreenAodHook" to {
            com.karen.flymetool.hook.feature.systemuieditor.ForceFullscreenAodHook
        },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
