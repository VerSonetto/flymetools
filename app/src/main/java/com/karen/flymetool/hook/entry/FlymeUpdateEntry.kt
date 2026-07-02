package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.flymeupdate.CaptureUpdateLinkHook
import com.karen.flymetool.hook.feature.flymeupdate.DisableUpdateCheckHook
import com.karen.flymetool.hook.feature.flymeupdate.ForceFullPackageHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object FlymeUpdateEntry : HookEntry {
    override val targetPackage = "com.meizu.flyme.update"

    private val hooks: List<FeatureHook> = listOf(
        DisableUpdateCheckHook,
        ForceFullPackageHook,
        CaptureUpdateLinkHook,
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
