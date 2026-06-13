package com.karen.flymetool.data

import android.content.Context
import android.content.pm.PackageManager
import com.karen.flymetool.util.FlymeVersionUtils
import org.json.JSONObject

object AppData {

    private const val ASSET_FILE = "features.json"

    @Volatile
    private var cached: CachedData? = null

    private data class CachedData(
        val apps: List<ScopedApp>,
        val features: Map<String, List<HookFeature>>
    )

    private fun load(context: Context): CachedData {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            val parsed = parse(text)
            cached = parsed
            return parsed
        }
    }

    private fun parse(text: String): CachedData {
        val root = JSONObject(text)
        val appsJson = root.getJSONArray("apps")

        val apps = mutableListOf<ScopedApp>()
        val features = mutableMapOf<String, List<HookFeature>>()

        for (i in 0 until appsJson.length()) {
            val appJson = appsJson.getJSONObject(i)
            val pkg = appJson.getString("package")
            val name = appJson.getString("name")
            apps.add(ScopedApp(pkg, name))

            val featuresJson = appJson.optJSONArray("features") ?: continue
            val list = mutableListOf<HookFeature>()
            for (j in 0 until featuresJson.length()) {
                val f = featuresJson.getJSONObject(j)
                list.add(
                    HookFeature(
                        key = f.getString("key"),
                        label = f.getString("label"),
                        description = f.optString("description", ""),
                        dependsOn = f.optString("dependsOn").takeIf { it.isNotEmpty() },
                        visibleUnless = f.optString("visibleUnless").takeIf { it.isNotEmpty() },
                        group = f.optString("group").takeIf { it.isNotEmpty() }
                    )
                )
            }
            features[pkg] = list
        }

        return CachedData(apps, features)
    }

    fun getScopedApps(context: Context): List<ScopedApp> {
        val pm = context.packageManager
        return load(context).apps.filter { app ->
            if (app.packageName == "com.meizu.share" && !FlymeVersionUtils.isFlyme10()) {
                return@filter false
            }
            true
        }.map { app ->
            try {
                val info = pm.getApplicationInfo(app.packageName, 0)
                val label = pm.getApplicationLabel(info).toString()
                app.copy(name = label)
            } catch (e: PackageManager.NameNotFoundException) {
                app
            }
        }
    }

    fun getFeatures(packageName: String): List<HookFeature> {
        return cached?.features?.get(packageName) ?: emptyList()
    }

    fun getFeatureGroups(packageName: String): List<String> {
        return cached?.features?.get(packageName)
            ?.mapNotNull { it.group }
            ?.distinct()
            ?: emptyList()
    }

    fun getFeaturesByGroup(packageName: String, group: String): List<HookFeature> {
        return cached?.features?.get(packageName)
            ?.filter { it.group == group }
            ?: emptyList()
    }

    fun getUngroupedFeatures(packageName: String): List<HookFeature> {
        return cached?.features?.get(packageName)
            ?.filter { it.group == null }
            ?: emptyList()
    }
}
