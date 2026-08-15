package com.karen.flymetool.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义搜索引擎配置。
 *
 * [urlTemplate] 中使用 `{0}` 作为搜索词占位符，例如：
 * `https://www.bing.com/search?q={0}`
 */
data class CustomSearchEngine(
    val id: Int,
    val name: String,
    val urlTemplate: String,
    val builtIn: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", urlTemplate)
        put("builtIn", builtIn)
    }

    companion object {
        const val PLACEHOLDER = "{0}"

        fun fromJson(obj: JSONObject): CustomSearchEngine? {
            return try {
                CustomSearchEngine(
                    id = obj.getInt("id"),
                    name = obj.getString("name"),
                    urlTemplate = obj.getString("url"),
                    builtIn = obj.optBoolean("builtIn", false)
                )
            } catch (_: Throwable) {
                null
            }
        }

        fun encode(engines: List<CustomSearchEngine>): String {
            val array = JSONArray()
            engines.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun decode(json: String?): List<CustomSearchEngine> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    fromJson(array.getJSONObject(i))
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}

/**
 * Flyme 搜索自定义搜索引擎的常量与工具。
 */
object SearchEngines {

    /** Flyme 搜索包名 */
    const val PACKAGE_NAME = "com.meizu.net.search"

    /** 功能 key（与 BuiltInFeatures / FeatureConfig 保持一致） */
    const val FEATURE_KEY = "custom_search_engine"

    /** 内置搜索引擎：必应、谷歌 */
    val BUILT_IN: List<CustomSearchEngine> = listOf(
        CustomSearchEngine(
            id = -100,
            name = "必应",
            urlTemplate = "https://www.bing.com/search?q={0}",
            builtIn = true
        ),
        CustomSearchEngine(
            id = -101,
            name = "谷歌",
            urlTemplate = "https://www.google.com/search?q={0}",
            builtIn = true
        )
    )

    /** 内置 + 用户自定义 */
    fun all(userJson: String?): List<CustomSearchEngine> {
        return BUILT_IN + CustomSearchEngine.decode(userJson)
    }

    /** 为用户新增的引擎分配一个不与内置冲突的负 id */
    fun nextUserEngineId(userEngines: List<CustomSearchEngine>): Int {
        val used = userEngines.map { it.id }.toSet()
        var id = -1000
        while (id in used) {
            id--
        }
        return id
    }
}
