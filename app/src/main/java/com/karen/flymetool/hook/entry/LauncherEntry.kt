package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.feature.launcher.MemoryDisplayHook
import com.karen.flymetool.hook.feature.launcher.TaskCardHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object LauncherEntry : HookEntry {
    override val targetPackage = "com.meizu.flyme.launcher"

    override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val radiusEnabled = XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "task_card_radius")
        val blurEnabled = XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "task_blur_intensity")
        val memoryDisplayEnabled = XposedPrefs.isFeatureEnabled(lpparam, targetPackage, "memory_display")

        val radiusDp = if (radiusEnabled) {
            XposedPrefs.getFeatureValue(lpparam, targetPackage, "task_card_radius", 24)
        } else -1

        val blurIntensity = if (blurEnabled) {
            XposedPrefs.getFeatureValue(lpparam, targetPackage, "task_blur_intensity", 50)
        } else -1

        val memoryInterval = if (memoryDisplayEnabled) {
            XposedPrefs.getFeatureValue(lpparam, targetPackage, "memory_display", 2000).toLong()
        } else 0

        if (radiusEnabled || blurEnabled) {
            TaskCardHook.handleLoadPackage(lpparam, radiusDp, blurIntensity)
        }

        if (memoryDisplayEnabled) {
            MemoryDisplayHook.handleLoadPackage(lpparam, memoryInterval)
        }
    }
}
