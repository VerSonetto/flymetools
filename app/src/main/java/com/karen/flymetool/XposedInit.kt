package com.karen.flymetool

import com.karen.flymetool.hook.AndroidEntry
import com.karen.flymetool.hook.CustomizeCenterEntry
import com.karen.flymetool.hook.HookEntry
import com.karen.flymetool.hook.LauncherEntry
import com.karen.flymetool.hook.Logger
import com.karen.flymetool.hook.PackageInstallerEntry
import com.karen.flymetool.hook.SettingsEntry
import com.karen.flymetool.hook.SystemUIEntry
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
            LauncherEntry
        )

        private val ENTRY_MAP: Map<String, HookEntry> = ENTRIES.associateBy { it.targetPackage }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        val entry = ENTRY_MAP[packageName]
        if (entry != null) {
            Logger.i(TAG, "Loading hooks for $packageName")
            try {
                entry.initHooks(lpparam)
                Logger.i(TAG, "Hooks loaded successfully for $packageName")
            } catch (e: Throwable) {
                Logger.e(TAG, "Failed to load hooks for $packageName", e)
            }
        }
    }
}
