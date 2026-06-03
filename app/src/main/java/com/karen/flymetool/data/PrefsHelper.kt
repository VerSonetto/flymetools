package com.karen.flymetool.data

import android.content.Context
import android.content.SharedPreferences

object PrefsHelper {

    private const val PREFS_NAME = "flymetool_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isFeatureEnabled(context: Context, packageName: String, featureKey: String): Boolean {
        return getPrefs(context).getBoolean("$packageName:$featureKey", false)
    }

    fun setFeatureEnabled(context: Context, packageName: String, featureKey: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("$packageName:$featureKey", enabled).apply()
    }

    fun getFeatureValue(context: Context, packageName: String, featureKey: String, defaultValue: Int): Int {
        return getPrefs(context).getInt("$packageName:$featureKey:value", defaultValue)
    }

    fun setFeatureValue(context: Context, packageName: String, featureKey: String, value: Int) {
        getPrefs(context).edit().putInt("$packageName:$featureKey:value", value).apply()
    }

    fun getFeatureStringSet(context: Context, packageName: String, featureKey: String, defaultValue: Set<String>): Set<String> {
        return getPrefs(context).getStringSet("$packageName:$featureKey:values", defaultValue) ?: defaultValue
    }

    fun setFeatureStringSet(context: Context, packageName: String, featureKey: String, values: Set<String>) {
        getPrefs(context).edit().putStringSet("$packageName:$featureKey:values", values).apply()
    }

    fun getFeatureString(context: Context, packageName: String, featureKey: String, defaultValue: String): String {
        return getPrefs(context).getString("$packageName:$featureKey:value", defaultValue) ?: defaultValue
    }

    fun setFeatureString(context: Context, packageName: String, featureKey: String, value: String) {
        getPrefs(context).edit().putString("$packageName:$featureKey:value", value).apply()
    }

    fun isIntroShown(context: Context): Boolean {
        return getPrefs(context).getBoolean("intro_shown", false)
    }

    fun markIntroShown(context: Context) {
        getPrefs(context).edit().putBoolean("intro_shown", true).apply()
    }
}
