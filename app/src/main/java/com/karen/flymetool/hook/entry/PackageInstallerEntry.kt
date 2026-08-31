package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext

object PackageInstallerEntry : HookEntry {
    override val targetPackage = "com.android.packageinstaller"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "PackageInstallerHook" to { com.karen.flymetool.hook.feature.packageinstaller.PackageInstallerHook },
    )

    override fun initHooks(ctx: HookContext) {
        safeInitHooks(ctx, hookFactories)
    }
}
