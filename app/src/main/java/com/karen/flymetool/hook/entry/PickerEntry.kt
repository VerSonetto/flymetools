package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.picker.CustomBrowserHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object PickerEntry : HookEntry {
    override val targetPackage = "com.meizu.picker"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "custom_browser")) {
            CustomBrowserHook.handleLoadPackage(lpparam)
        }
    }
}
