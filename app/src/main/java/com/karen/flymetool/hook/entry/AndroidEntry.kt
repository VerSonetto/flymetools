package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.android.SuperStereoSoundHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object AndroidEntry : HookEntry {
    override val targetPackage = "android"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "force_super_stereo")) {
            SuperStereoSoundHook.handleLoadPackage(lpparam)
        }
    }
}
