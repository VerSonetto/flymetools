package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object MmsEntry : HookEntry {
    override val targetPackage = "com.android.mms"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "AutoCopyVerifyCodeHook" to { com.karen.flymetool.hook.feature.mms.AutoCopyVerifyCodeHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
