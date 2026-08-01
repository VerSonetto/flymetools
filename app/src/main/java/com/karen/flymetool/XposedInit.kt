package com.karen.flymetool

import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.entry.HookEntry
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。
 *
 * 重要：不得在本类的静态初始化里直接引用各 Entry/Hook object。
 * 否则单个 Hook 的 <clinit> 失败（如 Flyme 上 new Paint NPE）会变成
 * ExceptionInInitializerError，导致 Failed to load class XposedInit，
 * 全部作用域功能一起失效。
 */
class XposedInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "XposedInit"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val entryFactory = entryFactories[packageName] ?: return

        if (!FlymeVersionUtils.isScopeAvailable(packageName)) {
            Logger.i(TAG, "Skipping $packageName - hidden on this version")
            return
        }

        Logger.i(TAG, "Loading hooks for $packageName")
        try {
            // 触发一次 prefs 状态诊断日志（仅首次）
            XposedPrefs.isFeatureEnabled(lpparam, packageName, "__prefs_diag__")

            val entry = loadEntry(packageName, entryFactory)
            entry.initHooks(lpparam)
            Logger.i(TAG, "Hooks loaded successfully for $packageName")
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to load hooks for $packageName", e)
        }
    }

    private fun loadEntry(packageName: String, factory: () -> HookEntry): HookEntry {
        return try {
            factory()
        } catch (t: Throwable) {
            Logger.e(TAG, "Entry init failed for $packageName", t)
            object : HookEntry {
                override val targetPackage: String = packageName
                override fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) = Unit
            }
        }
    }
}

/**
 * 按包名惰性创建 Entry，避免加载 XposedInit 时连锁初始化全部 Hook。
 * lambda 内再引用 object，类加载失败只影响对应作用域。
 */
private val entryFactories: Map<String, () -> HookEntry> = mapOf(
    "com.android.systemui" to { com.karen.flymetool.hook.entry.SystemUIEntry },
    "com.android.settings" to { com.karen.flymetool.hook.entry.SettingsEntry },
    "com.android.packageinstaller" to { com.karen.flymetool.hook.entry.PackageInstallerEntry },
    "android" to { com.karen.flymetool.hook.entry.AndroidEntry },
    "com.meizu.customizecenter" to { com.karen.flymetool.hook.entry.CustomizeCenterEntry },
    "com.meizu.flyme.launcher" to { com.karen.flymetool.hook.entry.LauncherEntry },
    "com.android.mms" to { com.karen.flymetool.hook.entry.MmsEntry },
    "com.meizu.share" to { com.karen.flymetool.hook.entry.ShareEntry },
    "com.meizu.suggestion" to { com.karen.flymetool.hook.entry.SuggestionEntry },
    "com.meizu.picker" to { com.karen.flymetool.hook.entry.PickerEntry },
    "com.meizu.flyme.update" to { com.karen.flymetool.hook.entry.FlymeUpdateEntry },
    "com.meizu.battery" to { com.karen.flymetool.hook.entry.BatteryEntry },
    "com.flyme.systemuitools" to { com.karen.flymetool.hook.entry.SystemToolsEntry },
)
