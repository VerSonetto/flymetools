package com.karen.flymetool.hook.base

import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * Hook 侧读取模块配置。
 *
 * 注意：不得仅凭 [File.canRead] 判定失败就返回默认值。
 * 在部分 LSPosed / SELinux 组合下 canRead() 可能为 false，
 * 但 XSharedPreferences 仍能通过框架路径读到内容；反之亦然。
 * 「全部功能失效」的常见原因就是过严的可读性短路。
 */
object XposedPrefs {

    private const val PREFS_NAME = "flymetool_prefs"
    private const val MODULE_PACKAGE = "com.karen.flymetool"
    private const val HOOK_NAME = "XposedPrefs"

    @Volatile
    private var prefs: XSharedPreferences? = null

    @Volatile
    private var lastLoadStateLogged = false

    @Volatile
    private var lastModified: Long = -1L

    @Volatile
    private var lastLen: Long = -1L

    private fun obtainPrefs(): XSharedPreferences {
        val existing = prefs
        if (existing != null) return existing
        return synchronized(this) {
            prefs ?: XSharedPreferences(MODULE_PACKAGE, PREFS_NAME).also { prefs = it }
        }
    }

    /**
     * 统一加载：优先 reload；失败时仍返回实例供 getXxx 尝试。
     * 使用 mtime+length 做轻量缓存，避免同一进程内无意义重复解析。
     */
    private fun load(force: Boolean = false): XSharedPreferences? {
        return try {
            val p = obtainPrefs()
            // 旧环境可能仍有效；失败忽略
            try {
                @Suppress("DEPRECATION")
                p.makeWorldReadable()
            } catch (_: Throwable) {
            }

            val file: File? = try {
                p.file
            } catch (_: Throwable) {
                null
            }

            val modified = file?.let { if (it.exists()) it.lastModified() else -1L } ?: -1L
            val length = file?.let { if (it.exists()) it.length() else -1L } ?: -1L
            val changed = force || modified != lastModified || length != lastLen

            if (changed) {
                p.reload()
                lastModified = modified
                lastLen = length
            }

            logLoadStateOnce(p, file)
            p
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Failed to load XSharedPreferences", e)
            null
        }
    }

    private fun logLoadStateOnce(p: XSharedPreferences, file: File?) {
        if (lastLoadStateLogged) return
        lastLoadStateLogged = true
        val path = try {
            file?.absolutePath ?: "(null file)"
        } catch (_: Throwable) {
            "(unavailable)"
        }
        val exists = try {
            file?.exists() == true
        } catch (_: Throwable) {
            false
        }
        val canRead = try {
            file?.canRead() == true
        } catch (_: Throwable) {
            false
        }
        val size = try {
            file?.length() ?: -1L
        } catch (_: Throwable) {
            -1L
        }
        // 试读一个不存在的 key，确认 prefs 对象可用
        val probeOk = try {
            p.getBoolean("__flymetool_probe__", false)
            true
        } catch (_: Throwable) {
            false
        }
        Logger.i(
            HOOK_NAME,
            "prefs state: path=$path exists=$exists canRead=$canRead size=$size probeOk=$probeOk"
        )
        if (exists && !canRead) {
            Logger.w(
                HOOK_NAME,
                "prefs file exists but canRead=false; will still try XSharedPreferences APIs " +
                    "(LSPosed may allow this). If all features stay off, fix module prefs permissions " +
                    "or re-open FlymeTool once after reboot."
            )
        }
        if (!exists) {
            Logger.w(
                HOOK_NAME,
                "prefs file missing. Open FlymeTool app once and toggle any feature to create " +
                    "shared_prefs/$PREFS_NAME.xml"
            )
        }
    }

    fun isFeatureEnabled(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
        featureKey: String
    ): Boolean {
        val key = "$packageName:$featureKey"
        return try {
            val p = load() ?: return false
            val value = p.getBoolean(key, false)
            Logger.d(HOOK_NAME, "Read: $key = $value")
            value
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error key=$key", e)
            false
        }
    }

    fun getFeatureValue(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
        featureKey: String,
        defaultValue: Int
    ): Int {
        val key = "$packageName:$featureKey:value"
        return try {
            // 参数可能在 UI 中热更新，强制关注文件变化（load 内 mtime 判断）
            val p = load() ?: return defaultValue
            val value = p.getInt(key, defaultValue)
            Logger.d(HOOK_NAME, "Read: $key = $value")
            value
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error key=$key", e)
            defaultValue
        }
    }

    fun getFeatureExtraValue(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
        featureKey: String,
        suffix: String,
        defaultValue: Int
    ): Int {
        val key = "$packageName:$featureKey:$suffix"
        return try {
            val p = load() ?: return defaultValue
            p.getInt(key, defaultValue)
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error key=$key", e)
            defaultValue
        }
    }

    fun getFeatureStringSet(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
        featureKey: String,
        defaultValue: Set<String>
    ): Set<String> {
        val key = "$packageName:$featureKey:values"
        return try {
            val p = load() ?: return defaultValue
            val value = p.getStringSet(key, defaultValue) ?: defaultValue
            Logger.d(HOOK_NAME, "Read: $key = $value")
            value
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error key=$key", e)
            defaultValue
        }
    }

    fun getFeatureString(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
        featureKey: String,
        defaultValue: String
    ): String {
        val key = "$packageName:$featureKey:value"
        return try {
            val p = load() ?: return defaultValue
            val value = p.getString(key, defaultValue) ?: defaultValue
            Logger.d(HOOK_NAME, "Read: $key = $value")
            value
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error key=$key", e)
            defaultValue
        }
    }

    /** 强制下次读盘（例如已知 UI 刚改过配置）。 */
    fun invalidateCache() {
        lastModified = -1L
        lastLen = -1L
    }
}
