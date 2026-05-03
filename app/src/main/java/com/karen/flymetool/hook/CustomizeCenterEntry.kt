package com.karen.flymetool.hook

import de.robv.android.xposed.callbacks.XC_LoadPackage

object CustomizeCenterEntry : HookEntry {
    override val targetPackage = "com.meizu.customizecenter"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "force_free_theme")) {
            ForceFreeThemeHook.handleLoadPackage(lpparam)
        }
    }
}
