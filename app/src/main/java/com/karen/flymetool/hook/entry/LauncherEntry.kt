package com.karen.flymetool.hook.entry

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.feature.launcher.FolderBlurHook
import com.karen.flymetool.hook.feature.launcher.MemoryDisplayHook
import com.karen.flymetool.hook.feature.launcher.TaskCardHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

object LauncherEntry : HookEntry {
    override val targetPackage = "com.meizu.flyme.launcher"

    private val hooks: List<FeatureHook> = listOf(
        TaskCardHook,
        MemoryDisplayHook,
        FolderBlurHook,
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
