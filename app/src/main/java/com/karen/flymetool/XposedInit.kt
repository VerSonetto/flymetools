package com.karen.flymetool

import android.os.Build
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
        private const val TAG = "Boot"

        /** 日志系统每个进程只需初始化一次 */
        private var loggerInitialized = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val entryFactory = entryFactories[packageName] ?: return

        initLoggerOnce(lpparam)

        if (!FlymeVersionUtils.isScopeAvailable(packageName)) {
            Logger.i(TAG, "跳过 $packageName（当前 Flyme 版本不可用）")
            return
        }

        Logger.i(TAG, "加载 $packageName 的 Hook")
        try {
            // 触发一次 prefs 状态诊断日志（仅首次）
            XposedPrefs.isFeatureEnabled(lpparam, packageName, "__prefs_diag__")

            val entry = loadEntry(packageName, entryFactory)
            entry.initHooks(lpparam)
            Logger.i(TAG, "$packageName Hook 全部加载完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "$packageName Hook 加载失败", e)
        }
    }

    /** 每个进程只初始化一次：注入版本号、读取调试开关、启动 logcat 热切换监听，并输出启动横幅。 */
    private fun initLoggerOnce(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (loggerInitialized) return
        loggerInitialized = true
        val debugEnabled = XposedPrefs.isDebugEnabled(lpparam)
        Logger.init(BuildConfig.VERSION_NAME, debugEnabled)
        // logcat 热切换监听会 spawn 子进程，仅在调试开启时启动；关闭态通过模块开关开启后重启目标进程
        if (debugEnabled) {
            Logger.startCommandListener()
        }
        Logger.i(
            TAG,
            "FlymeTool v${Logger.moduleVersion} 已加载 " +
                "| pkg=${lpparam.packageName} " +
                "| flyme=${FlymeVersionUtils.getFullVersion()} " +
                "| sdk=${Build.VERSION.SDK_INT}"
        )
    }

    private fun loadEntry(packageName: String, factory: () -> HookEntry): HookEntry {
        return try {
            factory()
        } catch (t: Throwable) {
            Logger.e(TAG, "$packageName Entry 初始化失败", t)
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
    "com.flyme.systemuieditor" to { com.karen.flymetool.hook.entry.SystemUIEditorEntry },
    "com.meizu.net.search" to { com.karen.flymetool.hook.entry.SearchEntry },
    "com.meizu.media.camera" to { com.karen.flymetool.hook.entry.CameraEntry },
)
