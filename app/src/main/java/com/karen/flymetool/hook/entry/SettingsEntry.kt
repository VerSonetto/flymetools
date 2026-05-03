package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.settings.ForceNotificationEnableHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SettingsEntry : HookEntry {
    override val targetPackage = "com.android.settings"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "force_notification_enable")) {
            ForceNotificationEnableHook.handleLoadPackage(lpparam)
        }
    }
}
