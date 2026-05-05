package com.karen.flymetool.hook.base

import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

object XposedPrefs {

    private const val PREFS_NAME = "flymetool_prefs"
    private const val MODULE_PACKAGE = "com.karen.flymetool"
    private const val HOOK_NAME = "XposedPrefs"

    fun isFeatureEnabled(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String, featureKey: String): Boolean {
        val key = "$packageName:$featureKey"
        return try {
            val prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
            if (!prefs.file.canRead()) {
                Logger.e(HOOK_NAME, "XSharedPreferences file not readable")
                return false
            }
            val value = prefs.getBoolean(key, false)
            Logger.d(HOOK_NAME, "Read: $key = $value")
            value
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error", e)
            false
        }
    }

    fun getFeatureValue(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String, featureKey: String, defaultValue: Int): Int {
        val key = "$packageName:$featureKey:value"
        return try {
            val prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
            if (!prefs.file.canRead()) {
                Logger.e(HOOK_NAME, "XSharedPreferences file not readable")
                return defaultValue
            }
            val value = prefs.getInt(key, defaultValue)
            Logger.d(HOOK_NAME, "Read: $key = $value")
            value
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error", e)
            defaultValue
        }
    }

    fun getFeatureStringSet(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String, featureKey: String, defaultValue: Set<String>): Set<String> {
        val key = "$packageName:$featureKey:values"
        return try {
            val prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
            if (!prefs.file.canRead()) {
                Logger.e(HOOK_NAME, "XSharedPreferences file not readable")
                return defaultValue
            }
            val value = prefs.getStringSet(key, defaultValue) ?: defaultValue
            Logger.d(HOOK_NAME, "Read: $key = $value")
            value
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error", e)
            defaultValue
        }
    }

    fun getFeatureString(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String, featureKey: String, defaultValue: String): String {
        val key = "$packageName:$featureKey:value"
        return try {
            val prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
            if (!prefs.file.canRead()) {
                Logger.e(HOOK_NAME, "XSharedPreferences file not readable")
                return defaultValue
            }
            val value = prefs.getString(key, defaultValue) ?: defaultValue
            Logger.d(HOOK_NAME, "Read: $key = $value")
            value
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Read error", e)
            defaultValue
        }
    }
}
