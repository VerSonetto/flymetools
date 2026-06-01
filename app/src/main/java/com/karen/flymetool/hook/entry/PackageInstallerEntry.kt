package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.packageinstaller.PackageInstallerHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object PackageInstallerEntry : HookEntry {
    override val targetPackage = "com.android.packageinstaller"

    private val hooks: List<FeatureHook> = listOf(
        PackageInstallerHook,
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (hook in hooks) {
            try {
                hook.handle(lpparam, targetPackage)
            } catch (e: Throwable) {
                Logger.e(targetPackage, "Hook ${hook::class.simpleName} failed", e)
            }
        }
    }
}
