package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.settings.ForceNotificationEnableHook
import com.karen.flymetool.hook.feature.settings.NeverLockScreenHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SettingsEntry : HookEntry {
    override val targetPackage = "com.android.settings"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "never_lock_screen")) {
            NeverLockScreenHook.handleLoadPackage(lpparam)
        }

        if (XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "force_notification_enable")) {
            ForceNotificationEnableHook.handleLoadPackage(lpparam)
        }
    }
}
