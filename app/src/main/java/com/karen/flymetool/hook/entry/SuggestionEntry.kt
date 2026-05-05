package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.suggestion.CustomBrowserHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SuggestionEntry : HookEntry {
    override val targetPackage = "com.meizu.suggestion"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "custom_browser")) {
            CustomBrowserHook.handleLoadPackage(lpparam)
        }
    }
}
