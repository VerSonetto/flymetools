package com.karen.flymetool.data

import android.content.Context
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import java.util.concurrent.ConcurrentHashMap

data class FeatureSearchResult(
    val app: ScopedApp,
    val feature: HookFeature,
    val group: String?,
    internal val rank: Int
)

/**
 * 首页功能搜索：按名称、拼音（全拼/首字母）、分组、描述与应用名匹配，
 * 并过滤掉当前版本下被 dependsOn / visibleUnless 隐藏的功能。
 */
object FeatureSearch {

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        setToneType(HanyuPinyinToneType.WITHOUT_TONE)
    }

    private val pinyinCache = ConcurrentHashMap<String, PinyinIndex>()

    private class PinyinIndex(val full: String, val initials: String) {
        fun matches(token: String): Boolean = full.contains(token) || initials.contains(token)
    }

    fun queryTokens(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun search(context: Context, apps: List<ScopedApp>, query: String): List<FeatureSearchResult> {
        val tokens = queryTokens(query)
        if (tokens.isEmpty()) return emptyList()

        val results = mutableListOf<FeatureSearchResult>()
        for (app in apps) {
            for (feature in AppData.getFeatures(app.packageName)) {
                if (!isAvailable(context, app.packageName, feature)) continue
                val rank = matchRank(app, feature, tokens) ?: continue
                results += FeatureSearchResult(app, feature, feature.group, rank)
            }
        }
        // sortedBy 稳定，同分时保持应用顺序与功能定义顺序
        return results.sortedBy { it.rank }
    }

    private fun isAvailable(context: Context, packageName: String, feature: HookFeature): Boolean {
        feature.dependsOn?.let { key ->
            if (!PrefsHelper.isFeatureEnabled(context, packageName, key)) return false
        }
        feature.visibleUnless?.let { key ->
            if (PrefsHelper.isFeatureEnabled(context, packageName, key)) return false
        }
        return true
    }

    /** 所有关键词都要命中同一功能；返回其中最差的一档作为排序权重。 */
    private fun matchRank(app: ScopedApp, feature: HookFeature, tokens: List<String>): Int? {
        val label = feature.label.lowercase()
        val group = feature.group.orEmpty().lowercase()
        val description = feature.description.lowercase()
        val appName = app.name.lowercase()
        val key = feature.key.lowercase()

        var worst = 0
        for (token in tokens) {
            val rank = when {
                label.contains(token) -> 0
                pinyinOf(feature.label).matches(token) -> 1
                group.contains(token) -> 2
                description.contains(token) || appName.contains(token) -> 3
                key.contains(token) -> 4
                else -> return null
            }
            if (rank > worst) worst = rank
        }
        return worst
    }

    private fun pinyinOf(text: String): PinyinIndex {
        return pinyinCache.getOrPut(text) {
            val full = StringBuilder()
            val initials = StringBuilder()
            for (c in text) {
                val syllables = try {
                    PinyinHelper.toHanyuPinyinStringArray(c, pinyinFormat)
                } catch (_: Exception) {
                    null
                }
                if (syllables.isNullOrEmpty()) {
                    if (c.isLetterOrDigit()) {
                        full.append(c.lowercaseChar())
                        initials.append(c.lowercaseChar())
                    }
                } else {
                    full.append(syllables[0])
                    initials.append(syllables[0].first())
                }
            }
            PinyinIndex(full.toString(), initials.toString())
        }
    }
}
