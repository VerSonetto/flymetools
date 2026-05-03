package com.karen.flymetool.hook

import de.robv.android.xposed.callbacks.XC_LoadPackage

object PackageInstallerEntry : HookEntry {
    override val targetPackage = "com.android.packageinstaller"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "skip_install_scan")) {
            PackageInstallerHook.handleLoadPackage(lpparam)
        }
    }
}
