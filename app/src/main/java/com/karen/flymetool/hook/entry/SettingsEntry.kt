package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.settings.ForceNotificationEnableHook
import com.karen.flymetool.hook.feature.settings.NeverLockScreenHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SettingsEntry : HookEntry {
    override val targetPackage = "com.android.settings"

    private val hooks: List<FeatureHook> = listOf(
        NeverLockScreenHook,
        ForceNotificationEnableHook,
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
