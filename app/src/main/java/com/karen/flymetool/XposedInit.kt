package com.karen.flymetool

import android.os.Build
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.hook.entry.HookEntry
import com.karen.flymetool.util.FlymeVersionUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口（libxposed API 101）。
 *
 * 重要：不得在本类的静态初始化里直接引用各 Entry/Hook object。
 * 否则单个 Hook 的 <clinit> 失败（如 Flyme 上 new Paint NPE）会变成
 * ExceptionInInitializerError，导致模块加载失败，全部作用域功能一起失效。
 *
 * 生命周期（101）：
 * - onModuleLoaded：进入目标进程时调用一次，先于一切包回调。这里注入框架接口（prefs/日志）。
 * - onPackageLoaded：每个有 code 的包加载进进程时调用（system_server 的第一回调被下面替代）。
 * - onSystemServerStarting：system_server 场景，取代第一个包回调挂载 "android" 作用域。
 */
class XposedInit : XposedModule() {

    companion object {
        private const val TAG = "Boot"

        /** 日志系统每个进程只需初始化一次 */
        private var loggerInitialized = false
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // 框架接口已 attach（XposedModule 基类），先注入基础组件
        XposedPrefs.attach(this)
        Logger.attachApi(this)

        initLoggerOnce()

        Logger.i(
            TAG,
            "FlymeTool v${Logger.moduleVersion} 已加载 " +
                "| process=${param.processName} " +
                "| systemServer=${param.isSystemServer()} " +
                "| flyme=${FlymeVersionUtils.getFullVersion()} " +
                "| sdk=${Build.VERSION.SDK_INT}"
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        handlePackage(param.packageName, param.defaultClassLoader) { ctx ->
            entryFactories[ctx.packageName]?.let { factory ->
                loadEntry(ctx.packageName, factory).initHooks(ctx)
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // system_server：第一回调被替换到这里，挂载 "android" 作用域
        handlePackage("android", param.classLoader) { ctx ->
            entryFactories["android"]?.let { factory ->
                loadEntry("android", factory).initHooks(ctx)
            }
        }
    }

    /** 公共挂载流程：日志初始化、Flyme 版本可用性过滤。 */
    private fun handlePackage(packageName: String, classLoader: ClassLoader, mount: (HookContext) -> Unit) {
        initLoggerOnce()
        if (!FlymeVersionUtils.isScopeAvailable(packageName)) {
            Logger.i(TAG, "跳过 $packageName（当前 Flyme 版本不可用）")
            return
        }
        Logger.i(TAG, "加载 $packageName 的 Hook")
        try {
            mount(HookContext(this, packageName, classLoader))
            Logger.i(TAG, "$packageName Hook 全部加载完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "$packageName Hook 加载失败", e)
        }
    }

    /** 每个进程只初始化一次：注入版本号、读取调试开关、启动 logcat 热切换监听。 */
    private fun initLoggerOnce() {
        if (loggerInitialized) return
        loggerInitialized = true
        val debugEnabled = try {
            XposedPrefs.isDebugEnabled()
        } catch (_: Throwable) {
            false
        }
        Logger.init(BuildConfig.VERSION_NAME, debugEnabled)
        // logcat 热切换监听会 spawn 子进程，仅在调试开启时启动；关闭态通过模块开关开启后重启目标进程
        if (debugEnabled) {
            Logger.startCommandListener()
        }
    }

    private fun loadEntry(packageName: String, factory: () -> HookEntry): HookEntry {
        return try {
            factory()
        } catch (t: Throwable) {
            Logger.e(TAG, "$packageName Entry 初始化失败", t)
            object : HookEntry {
                override val targetPackage: String = packageName
                override fun initHooks(ctx: HookContext) = Unit
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