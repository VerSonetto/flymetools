package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.share.AutoAcceptShareHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object ShareEntry : HookEntry {
    override val targetPackage = "com.meizu.share"

    private val hooks: List<FeatureHook> = listOf(
        AutoAcceptShareHook,
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
