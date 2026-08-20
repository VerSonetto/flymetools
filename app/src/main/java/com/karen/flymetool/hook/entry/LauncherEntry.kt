package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object LauncherEntry : HookEntry {
    override val targetPackage = "com.meizu.flyme.launcher"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "TaskCardHook" to { com.karen.flymetool.hook.feature.launcher.TaskCardHook },
        "HideClearAllButtonHook" to { com.karen.flymetool.hook.feature.launcher.HideClearAllButtonHook },
        "IosDepthStackRecentsHook" to { com.karen.flymetool.hook.feature.launcher.IosDepthStackRecentsHook },
        "MemoryDisplayHook" to { com.karen.flymetool.hook.feature.launcher.MemoryDisplayHook },
        "FolderBlurHook" to { com.karen.flymetool.hook.feature.launcher.FolderBlurHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
