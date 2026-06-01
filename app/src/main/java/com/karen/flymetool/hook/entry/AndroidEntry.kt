package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.android.SuperStereoSoundHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object AndroidEntry : HookEntry {
    override val targetPackage = "android"

    private val hooks: List<FeatureHook> = listOf(
        SuperStereoSoundHook,
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
