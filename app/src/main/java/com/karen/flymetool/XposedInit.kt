package com.karen.flymetool

import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.entry.AndroidEntry
import com.karen.flymetool.hook.entry.BatteryEntry
import com.karen.flymetool.hook.entry.CustomizeCenterEntry
import com.karen.flymetool.hook.entry.HookEntry
import com.karen.flymetool.hook.entry.LauncherEntry
import com.karen.flymetool.hook.entry.MmsEntry
import com.karen.flymetool.hook.entry.PackageInstallerEntry
import com.karen.flymetool.hook.entry.PickerEntry
import com.karen.flymetool.hook.entry.SettingsEntry
import com.karen.flymetool.hook.entry.FlymeUpdateEntry
import com.karen.flymetool.hook.entry.ShareEntry
import com.karen.flymetool.hook.entry.SuggestionEntry
import com.karen.flymetool.hook.entry.SystemUIEntry
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "XposedInit"

        private val ENTRIES: List<HookEntry> = listOf(
            SystemUIEntry,
            SettingsEntry,
            PackageInstallerEntry,
            AndroidEntry,
            CustomizeCenterEntry,
            LauncherEntry,
            MmsEntry,
            ShareEntry,
            SuggestionEntry,
            PickerEntry,
            FlymeUpdateEntry,
            BatteryEntry
        )

        private val ENTRY_MAP: Map<String, HookEntry> = ENTRIES.associateBy { it.targetPackage }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        val entry = ENTRY_MAP[packageName] ?: return
        if (!FlymeVersionUtils.isScopeAvailable(packageName)) {
            Logger.i(TAG, "Skipping $packageName - hidden on this version")
            return
        }
        Logger.i(TAG, "Loading hooks for $packageName")
        try {
            entry.initHooks(lpparam)
            Logger.i(TAG, "Hooks loaded successfully for $packageName")
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to load hooks for $packageName", e)
        }
    }
}
