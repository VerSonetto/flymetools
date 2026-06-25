package com.karen.flymetool.data

import android.content.Context
import android.content.pm.PackageManager
import com.karen.flymetool.util.FlymeVersionUtils
import org.json.JSONObject

object AppData {

    private const val ASSET_FILE = "features.json"

    @Volatile
    private var cachedApps: List<ScopedApp>? = null

    private fun loadApps(context: Context): List<ScopedApp> {
        cachedApps?.let { return it }
        synchronized(this) {
            cachedApps?.let { return it }
            val text = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val appsJson = root.getJSONArray("apps")
            cachedApps = (0 until appsJson.length()).map { i ->
                val app = appsJson.getJSONObject(i)
                ScopedApp(app.getString("package"), app.getString("name"))
            }
            return cachedApps!!
        }
    }

    fun getScopedApps(context: Context): List<ScopedApp> {
        val pm = context.packageManager
        return loadApps(context).filter { app ->
            FlymeVersionUtils.isScopeAvailable(app.packageName)
        }.map { app ->
            try {
                val info = pm.getApplicationInfo(app.packageName, 0)
                val label = pm.getApplicationLabel(info).toString()
                app.copy(name = label)
            } catch (_: PackageManager.NameNotFoundException) {
                app
            }
        }
    }

    fun getFeatures(packageName: String): List<HookFeature> {
        val current = FlymeVersionUtils.getFullVersion()
        return builtInFeatures[packageName]
            ?.filter { f -> f.minVersion == null || current.startsWith(f.minVersion) }
            ?: emptyList()
    }

    fun getFeatureGroups(packageName: String): List<String> {
        return getFeatures(packageName)
            .mapNotNull { it.group }
            .distinct()
    }

    fun getFeaturesByGroup(packageName: String, group: String): List<HookFeature> {
        return getFeatures(packageName).filter { it.group == group }
    }

    fun getUngroupedFeatures(packageName: String): List<HookFeature> {
        return getFeatures(packageName).filter { it.group == null }
    }
}
