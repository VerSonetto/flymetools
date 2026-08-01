package com.karen.flymetool.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File

object PrefsHelper {

    private const val PREFS_NAME = "flymetool_prefs"
    private const val INTRO_VERSION_KEY = "intro_version"
    private const val CURRENT_INTRO_VERSION = 1
    private const val DONATE_DIALOG_SHOWN_KEY = "donate_dialog_shown"

    private fun getPrefs(context: Context): SharedPreferences {
        // Android N+ 上 MODE_WORLD_READABLE 会抛 SecurityException。
        // 仍优先尝试，以便旧环境 / 部分框架下生成更易被 Xposed 读取的文件。
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

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

            // dataDir: 751, shared_prefs: 771/755, xml: 664
            setPerms(File(dataDir), ownerOnlyRead = false, execute = true)
            setPerms(prefsDir, ownerOnlyRead = false, execute = true)
            if (prefsFile.exists()) {
                setPerms(prefsFile, ownerOnlyRead = false, execute = false)
            }
        } catch (_: Throwable) {
            // 权限修复失败不阻塞正常读写
        }
    }

    private fun setPerms(file: File, ownerOnlyRead: Boolean, execute: Boolean) {
        try {
            // 先尝试 Java API
            file.setReadable(true, ownerOnlyRead)
            file.setWritable(true, true)
            if (execute) {
                file.setExecutable(true, ownerOnlyRead)
            }
        } catch (_: Throwable) {
        }

        // 再尝试 chmod（部分设备 setReadable 对“其他用户”无效）
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

    private fun editPrefs(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val editor = getPrefs(context).edit()
        editor.block()
        // commit 确保文件立刻落盘，便于 Hook 侧 FileObserver / reload 立刻看到
        editor.commit()
        ensurePrefsReadable(context)
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

    fun isIntroShown(context: Context): Boolean {
        return getPrefs(context).getInt(INTRO_VERSION_KEY, 0) >= CURRENT_INTRO_VERSION
    }

    fun markIntroShown(context: Context) {
        editPrefs(context) {
            putInt(INTRO_VERSION_KEY, CURRENT_INTRO_VERSION)
        }
    }

    fun isDonateDialogShown(context: Context): Boolean {
        return getPrefs(context).getBoolean(DONATE_DIALOG_SHOWN_KEY, false)
    }

    fun markDonateDialogShown(context: Context) {
        editPrefs(context) {
            putBoolean(DONATE_DIALOG_SHOWN_KEY, true)
        }
    }

    /** 启动时调用：创建 prefs 并修复权限，避免从未打开模块时 Hook 读不到文件。 */
    fun warmup(context: Context) {
        getPrefs(context)
        ensurePrefsReadable(context)
    }
}
