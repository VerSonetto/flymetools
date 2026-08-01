package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object PackageInstallerEntry : HookEntry {
    override val targetPackage = "com.android.packageinstaller"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "PackageInstallerHook" to { com.karen.flymetool.hook.feature.packageinstaller.PackageInstallerHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
