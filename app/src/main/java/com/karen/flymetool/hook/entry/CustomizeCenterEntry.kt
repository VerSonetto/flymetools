package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.customizecenter.ForceFreeFontHook
import com.karen.flymetool.hook.feature.customizecenter.ForceFreeThemeHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CustomizeCenterEntry : HookEntry {
    override val targetPackage = "com.meizu.customizecenter"

    private val hooks: List<FeatureHook> = listOf(
        ForceFreeThemeHook,
        ForceFreeFontHook,
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
