package com.karen.flymetool.hook.base

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * Hook 侧读取模块配置（libxposed API 101 远程偏好）。
 *
 * 配置存于框架（LSPosed 数据库），Hook 侧经 getRemotePreferences 只读，
 * App 侧经 libxposed service 写入；两端以相同 group（flymetool_prefs）共享。
 *
 * 注意：必须在 [attach] 之后调用（onModuleLoaded 时机），否则抛 IllegalStateException。
 */
object XposedPrefs {

    private const val PREFS_NAME = "flymetool_prefs"
    private const val TAG = "XposedPrefs"

    /** 全局调试日志开关 key（UI「关于」页写入，不带 package 前缀） */
    private const val KEY_DEBUG = "__debug__"

    @Volatile
    private var api: XposedInterface? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 由入口在 onModuleLoaded 时注入框架接口（每个进程一次）。 */
    fun attach(interface_: XposedInterface) {
        api = interface_
    }

    private fun p(): SharedPreferences {
        val existing = prefs
        if (existing != null) return existing
        val iface = api ?: throw IllegalStateException("XposedPrefs 未 attach（需在 onModuleLoaded 后调用）")
        return synchronized(this) {
            prefs ?: iface.getRemotePreferences(PREFS_NAME).also { prefs = it }
        }
    }

    fun isFeatureEnabled(packageName: String, featureKey: String): Boolean {
        val key = "$packageName:$featureKey"
        return try {
            val value = p().getBoolean(key, false)
            Logger.d(TAG) { "读取配置: $key = $value" }
            value
        } catch (e: Throwable) {
            Logger.e(TAG, "读取配置失败", e, "key" to key)
            false
        }
    }

    fun getFeatureValue(packageName: String, featureKey: String, defaultValue: Int): Int {
        val key = "$packageName:$featureKey:value"
        return try {
            val value = p().getInt(key, defaultValue)
            Logger.d(TAG) { "读取配置: $key = $value" }
            value
        } catch (e: Throwable) {
            Logger.e(TAG, "读取配置失败", e, "key" to key)
            defaultValue
        }
    }

    fun getFeatureExtraValue(
        packageName: String,
        featureKey: String,
        suffix: String,
        defaultValue: Int
    ): Int {
        val key = "$packageName:$featureKey:$suffix"
        return try {
            p().getInt(key, defaultValue)
        } catch (e: Throwable) {
            Logger.e(TAG, "读取配置失败", e, "key" to key)
            defaultValue
        }
    }

    fun getFeatureStringSet(
        packageName: String,
        featureKey: String,
        defaultValue: Set<String>
    ): Set<String> {
        val key = "$packageName:$featureKey:values"
        return try {
            val value = p().getStringSet(key, defaultValue)
            Logger.d(TAG) { "读取配置: $key = $value" }
            value ?: defaultValue
        } catch (e: Throwable) {
            Logger.e(TAG, "读取配置失败", e, "key" to key)
            defaultValue
        }
    }

    fun getFeatureString(
        packageName: String,
        featureKey: String,
        defaultValue: String
    ): String {
        val key = "$packageName:$featureKey:value"
        return try {
            val value = p().getString(key, defaultValue)
            Logger.d(TAG) { "读取配置: $key = $value" }
            value ?: defaultValue
        } catch (e: Throwable) {
            Logger.e(TAG, "读取配置失败", e, "key" to key)
            defaultValue
        }
    }

    /**
     * 全局调试日志开关：由 XposedInit.onModuleLoaded 读取一次，
     * 之后经 logcat 命令热切换（Logger.startCommandListener）。
     */
    fun isDebugEnabled(): Boolean {
        return try {
            p().getBoolean(KEY_DEBUG, false)
        } catch (_: Throwable) {
            false
        }
    }
}