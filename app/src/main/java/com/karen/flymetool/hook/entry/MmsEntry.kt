package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.mms.AutoCopyVerifyCodeHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object MmsEntry : HookEntry {
    override val targetPackage = "com.android.mms"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "auto_copy_verify_code")) {
            AutoCopyVerifyCodeHook.handleLoadPackage(lpparam)
        }
    }
}
