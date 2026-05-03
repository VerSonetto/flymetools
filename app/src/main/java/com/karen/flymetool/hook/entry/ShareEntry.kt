package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.share.AutoAcceptShareHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object ShareEntry : HookEntry {
    override val targetPackage = "com.meizu.share"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "auto_accept_share")) {
            AutoAcceptShareHook.handleLoadPackage(lpparam)
        }
    }
}
