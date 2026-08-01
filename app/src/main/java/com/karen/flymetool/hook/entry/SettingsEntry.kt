package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SettingsEntry : HookEntry {
    override val targetPackage = "com.android.settings"

    private val hookFactories: List<Pair<String, () -> FeatureHook>> = listOf(
        "NeverLockScreenHook" to { com.karen.flymetool.hook.feature.settings.NeverLockScreenHook },
        "ForceNotificationEnableHook" to { com.karen.flymetool.hook.feature.settings.ForceNotificationEnableHook },
    )

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeInitHooks(targetPackage, lpparam, hookFactories)
    }
}
