package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.flymeupdate.DisableUpdateCheckHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object FlymeUpdateEntry : HookEntry {
    override val targetPackage = "com.meizu.flyme.update"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "disable_update_check")) {
            DisableUpdateCheckHook.handleLoadPackage(lpparam)
        }
    }
}
