package com.karen.flymetool.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.File

/**
 * App 侧配置读写（libxposed API 101 + service）。
 *
 * 现代化模块的配置真实存储位于框架远程偏好组（LSPosed 数据库），
 * Hook 侧经 getRemotePreferences("flymetool_prefs") 只读，App 侧经 service 读写：
 * - service 绑定成功后：读写全部走远程；并把本地 SP 全量同步一次（幂等迁移存量配置）。
 * - service 不可用（早期 / 非框架环境）：回退本地 SP（保留权限修复逻辑，用于兼容旧框架）。
 * - 本地 SP 始终镜像写入，便于回滚排查。
 */
object PrefsHelper {

    private const val PREFS_NAME = "flymetool_prefs"
    private const val INTRO_VERSION_KEY = "intro_version"
    private const val CURRENT_INTRO_VERSION = 1

    /** 全局调试日志开关（不带 package 前缀，Hook 侧启动读取） */
    private const val DEBUG_LOG_KEY = "__debug__"

    /** 远程偏好（service 绑定后可用） */
    @Volatile
    private var remotePrefs: SharedPreferences? = null

    @Volatile
    private var initialized = false

    private fun getPrefs(context: Context): SharedPreferences {
        // 优先远程（框架数据库），未连接时回退本地
        remotePrefs?.let { return it }
        return getLocalPrefs(context)
    }

    private fun getLocalPrefs(context: Context): SharedPreferences {
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * 启动时调用：创建本地 prefs 镜像并绑定 libxposed service。
     * service 绑定后切换为远程偏好，并把本地存量配置全量同步到远程组（幂等）。
     */
    fun warmup(context: Context) {
        getLocalPrefs(context)
        ensurePrefsReadable(context)
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                try {
                    val remote = service.getRemotePreferences(PREFS_NAME)
                    remotePrefs = remote
                    migrateLocalToRemote(app, remote)
                } catch (t: Throwable) {
                    // 框架不支持远程偏好等场景，继续使用本地回退
                }
            }

            override fun onServiceDied(service: XposedService) {
                remotePrefs = null
            }
        })
    }

    /** 存量本地配置 → 远程，幂等（对全部键覆盖写入同值）。 */
    private fun migrateLocalToRemote(context: Context, remote: SharedPreferences) {
        try {
            val local = getLocalPrefs(context)
            val editor = remote.edit()
            var dirty = false
            for ((key, value) in local.all) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    else -> {}
                }
                dirty = true
            }
            if (dirty) editor.apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * 写本地（镜像）+ 远程（可用时）。commit 确保立刻落盘。
     */
    private fun editPrefs(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val editor = getLocalPrefs(context).edit()
        editor.block()
        // commit 确保本地文件立刻落盘（远程未连接时的兜底读取依赖它）
        editor.commit()
        ensurePrefsReadable(context)

        remotePrefs?.let { remote ->
            try {
                val rEditor = remote.edit()
                rEditor.block()
                rEditor.apply()
            } catch (_: Throwable) {
            }
        }
    }

    fun isFeatureEnabled(context: Context, packageName: String, featureKey: String): Boolean {
        return getPrefs(context).getBoolean("$packageName:$featureKey", false)
    }

    fun setFeatureEnabled(context: Context, packageName: String, featureKey: String, enabled: Boolean) {
        editPrefs(context) {
            putBoolean("$packageName:$featureKey", enabled)
        }
    }

    fun getFeatureBoolean(context: Context, packageName: String, featureKey: String, defaultValue: Boolean): Boolean {
        return getPrefs(context).getBoolean("$packageName:$featureKey", defaultValue)
    }

    fun setFeatureBoolean(context: Context, packageName: String, featureKey: String, value: Boolean) {
        editPrefs(context) {
            putBoolean("$packageName:$featureKey", value)
        }
    }

    fun getFeatureValue(context: Context, packageName: String, featureKey: String, defaultValue: Int): Int {
        return getPrefs(context).getInt("$packageName:$featureKey:value", defaultValue)
    }

    fun setFeatureValue(context: Context, packageName: String, featureKey: String, value: Int) {
        editPrefs(context) {
            putInt("$packageName:$featureKey:value", value)
        }
    }

    /** 同一功能的额外 int 参数，key 为 package:feature:suffix */
    fun getFeatureExtraValue(
        context: Context,
        packageName: String,
        featureKey: String,
        suffix: String,
        defaultValue: Int
    ): Int {
        return getPrefs(context).getInt("$packageName:$featureKey:$suffix", defaultValue)
    }

    fun setFeatureExtraValue(
        context: Context,
        packageName: String,
        featureKey: String,
        suffix: String,
        value: Int
    ) {
        editPrefs(context) {
            putInt("$packageName:$featureKey:$suffix", value)
        }
    }

    fun getFeatureStringSet(context: Context, packageName: String, featureKey: String, defaultValue: Set<String>): Set<String> {
        return getPrefs(context).getStringSet("$packageName:$featureKey:values", defaultValue) ?: defaultValue
    }

    fun setFeatureStringSet(context: Context, packageName: String, featureKey: String, values: Set<String>) {
        editPrefs(context) {
            putStringSet("$packageName:$featureKey:values", values)
        }
    }

    fun getFeatureString(context: Context, packageName: String, featureKey: String, defaultValue: String): String {
        return getPrefs(context).getString("$packageName:$featureKey:value", defaultValue) ?: defaultValue
    }

    fun setFeatureString(context: Context, packageName: String, featureKey: String, value: String) {
        editPrefs(context) {
            putString("$packageName:$featureKey:value", value)
        }
    }

    fun isDebugLogEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(DEBUG_LOG_KEY, false)
    }

    fun setDebugLogEnabled(context: Context, enabled: Boolean) {
        editPrefs(context) {
            putBoolean(DEBUG_LOG_KEY, enabled)
        }
    }

    fun isIntroShown(context: Context): Boolean {
        return getLocalPrefs(context).getInt(INTRO_VERSION_KEY, 0) >= CURRENT_INTRO_VERSION
    }

    fun markIntroShown(context: Context) {
        try {
            getLocalPrefs(context).edit().putInt(INTRO_VERSION_KEY, CURRENT_INTRO_VERSION).apply()
        } catch (_: Throwable) {
        }
    }

    // ---------- 本地镜像权限修复（仅本地回退需要） ----------

    /**
     * LSPosed 通常能读模块私有 SP；但部分机型/框架仍依赖文件权限。
     * 在每次写入后调用，降低「UI 显示已开启、Hook 全读 false」的概率。
     */
    private fun ensurePrefsReadable(context: Context) {
        try {
            val dataDir = context.applicationInfo.dataDir ?: return
            val prefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "$PREFS_NAME.xml")

            // 触发一次空提交，保证 xml 被创建
            if (!prefsFile.exists()) {
                try {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("__flymetool_init__", true)
                        .commit()
                } catch (_: Throwable) {
                }
            }

            setPerms(File(dataDir), ownerOnlyRead = false, execute = true)
            setPerms(prefsDir, ownerOnlyRead = false, execute = true)
            if (prefsFile.exists()) {
                setPerms(prefsFile, ownerOnlyRead = false, execute = false)
            }
        } catch (_: Throwable) {
        }
    }

    private fun setPerms(file: File, ownerOnlyRead: Boolean, execute: Boolean) {
        try {
            file.setReadable(true, ownerOnlyRead)
            file.setWritable(true, true)
            if (execute) {
                file.setExecutable(true, ownerOnlyRead)
            }
        } catch (_: Throwable) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val mode = when {
                    execute && !ownerOnlyRead -> "755"
                    !execute && !ownerOnlyRead -> "664"
                    execute -> "700"
                    else -> "600"
                }
                Runtime.getRuntime().exec(arrayOf("chmod", mode, file.absolutePath)).waitFor()
            } catch (_: Throwable) {
            }
        }
    }
}