package com.karen.flymetool.hook.feature.search

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.preference.ListPreference
import android.preference.Preference
import android.webkit.WebView
import com.karen.flymetool.data.CustomSearchEngine
import com.karen.flymetool.data.SearchEngines
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Modifier

/**
 * 自定义搜索引擎。
 *
 * 将 FlymeTool 中配置的搜索引擎注入 Flyme 搜索的“设置 → 搜索引擎”列表，
 * 并在 WebView / 外部浏览器加载搜索 URL 时按当前选中的自定义引擎重写 URL。
 */
object CustomSearchEngineHook : FeatureHook {

    private const val TAG = "CustomSearchEngine"
    private const val PACKAGE_NAME = "com.meizu.net.search"
    private const val FEATURE_KEY = "custom_search_engine"

    private const val ENGINE_PREF_KEY = "key_search_engine"
    private const val USER_SET_ENGINE_KEY = "user_set_engine"
    private const val KEY_SEARCH_ENGINE_KEY = "key_search_engine"
    private const val MMKV_PREFS_ID = "com.meizu.net.search_preferences"

    private val userEngines = mutableListOf<CustomSearchEngine>()

    private var context: HookContext? = null

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != PACKAGE_NAME) return
        if (!ctx.featureEnabled(FEATURE_KEY)) return

        this.context = ctx
        refreshEngines()

        hookListPreference(ctx)
        hookWebView(ctx)
        hookStartActivity(ctx)

        Logger.i(
            TAG,
            "自定义搜索引擎已加载：内置 ${SearchEngines.BUILT_IN.size} 个，自定义 ${userEngines.size} 个"
        )
    }

    private fun refreshEngines() {
        val ctx = context ?: return
        val json = ctx.featureString(FEATURE_KEY, "")
        userEngines.clear()
        userEngines += CustomSearchEngine.decode(json)
    }

    private fun allEngines(): List<CustomSearchEngine> {
        return SearchEngines.BUILT_IN + userEngines
    }

    // ---------------------------------------------------------------------
    // 注入“设置 → 搜索引擎”列表 + 处理自定义引擎选择
    // ---------------------------------------------------------------------

    private fun hookListPreference(ctx: HookContext) {
        try {
            Reflect.hookAllMethods(ctx.api, ListPreference::class.java, "setEntries") { chain ->
                try {
                    val pref = chain.getThisObject() as? ListPreference ?: return@hookAllMethods chain.proceed()
                    if (pref.key != ENGINE_PREF_KEY) return@hookAllMethods chain.proceed()
                    val original = chain.getArgs().getOrNull(0) as? Array<*> ?: return@hookAllMethods chain.proceed()

                    refreshEngines()
                    val engines = allEngines()
                    if (engines.isEmpty()) return@hookAllMethods chain.proceed()

                    val total = original.size + engines.size
                    val newEntries = arrayOfNulls<CharSequence>(total)
                    System.arraycopy(original, 0, newEntries, 0, original.size)
                    engines.forEachIndexed { index, engine ->
                        newEntries[original.size + index] = engine.name
                    }
                    val newArgs = chain.getArgs().toMutableList().apply { set(0, newEntries) }.toTypedArray()
                    return@hookAllMethods chain.proceed(newArgs)
                } catch (e: Throwable) {
                    Logger.e(TAG, "注入 setEntries 失败", e)
                }
                chain.proceed()
            }

            Reflect.hookAllMethods(ctx.api, ListPreference::class.java, "setEntryValues") { chain ->
                // ---- before ----
                var replacement: Array<CharSequence?>? = null
                try {
                    val pref = chain.getThisObject() as? ListPreference
                    val original = chain.getArgs().getOrNull(0) as? Array<*>
                    if (pref != null && pref.key == ENGINE_PREF_KEY && original != null) {
                        refreshEngines()
                        val engines = allEngines()
                        if (engines.isNotEmpty()) {
                            val total = original.size + engines.size
                            val newValues = arrayOfNulls<CharSequence>(total)
                            System.arraycopy(original, 0, newValues, 0, original.size)
                            engines.forEachIndexed { index, engine ->
                                newValues[original.size + index] = engine.id.toString()
                            }
                            replacement = newValues
                        }
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "注入 setEntryValues 失败", e)
                }

                val result = if (replacement != null) {
                    val newArgs = chain.getArgs().toMutableList().apply { set(0, replacement) }.toTypedArray()
                    chain.proceed(newArgs)
                } else {
                    chain.proceed()
                }

                // ---- after ----
                try {
                    val pref = chain.getThisObject() as? ListPreference ?: return@hookAllMethods result
                    if (pref.key != ENGINE_PREF_KEY) return@hookAllMethods result

                    val selectedId = readSelectedEngineId() ?: return@hookAllMethods result
                    val engine = allEngines().firstOrNull { it.id == selectedId } ?: return@hookAllMethods result
                    pref.value = engine.id.toString()
                    pref.summary = engine.name
                } catch (e: Throwable) {
                    Logger.e(TAG, "恢复自定义引擎选中状态失败", e)
                }

                return@hookAllMethods result
            }

            // 用户点击自定义引擎时，拦截 Preference.callChangeListener：
            // 自己保存选择并返回 true，避免 Flyme 搜索内部只认识服务端引擎的
            // OnPreferenceChangeListener 拿到自定义 id 后 m14597R -> -1 导致崩溃。
            Reflect.hookAllMethods(ctx.api, Preference::class.java, "callChangeListener") { chain ->
                try {
                    val pref = chain.getThisObject() as? ListPreference ?: return@hookAllMethods chain.proceed()
                    if (pref.key != ENGINE_PREF_KEY) return@hookAllMethods chain.proceed()

                    val newValue = chain.getArgs().getOrNull(0) as? String ?: return@hookAllMethods chain.proceed()
                    val id = newValue.toIntOrNull() ?: return@hookAllMethods chain.proceed()
                    val engine = allEngines().firstOrNull { it.id == id } ?: return@hookAllMethods chain.proceed()

                    saveSelectedEngine(newValue)
                    pref.summary = engine.name
                    // 跳过真正的 OnPreferenceChangeListener，同时让 ListPreference 认为修改被接受
                    return@hookAllMethods true
                } catch (e: Throwable) {
                    Logger.e(TAG, "自定义引擎选择处理失败", e)
                }
                chain.proceed()
            }

            Logger.i(TAG, "已挂载 ListPreference 注入与自定义引擎选择")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 ListPreference 注入失败", e)
        }
    }

    // ---------------------------------------------------------------------
    // 重写搜索 URL
    // ---------------------------------------------------------------------

    private fun hookWebView(ctx: HookContext) {
        try {
            val hook: (Chain) -> Any? = hook@ { chain ->
                try {
                    val url = chain.getArgs().getOrNull(0) as? String ?: return@hook chain.proceed()
                    rewriteSearchUrl(url)?.let { newUrl ->
                        val newArgs = chain.getArgs().toMutableList().apply { set(0, newUrl) }.toTypedArray()
                        return@hook chain.proceed(newArgs)
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "WebView.loadUrl 重写异常", e)
                }
                chain.proceed()
            }

            Reflect.hookAllMethods(ctx.api, WebView::class.java, "loadUrl") { chain -> hook(chain) }

            Logger.i(TAG, "已挂载 WebView.loadUrl")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 WebView.loadUrl 失败", e)
        }
    }

    private fun hookStartActivity(ctx: HookContext) {
        try {
            val hook: (Chain) -> Any? = hook@ { chain ->
                try {
                    val intent = chain.getArgs().getOrNull(0) as? Intent ?: return@hook chain.proceed()
                    if (intent.action != Intent.ACTION_VIEW) return@hook chain.proceed()
                    val url = intent.data?.toString() ?: return@hook chain.proceed()
                    rewriteSearchUrl(url)?.let { newUrl ->
                        intent.data = Uri.parse(newUrl)
                        Logger.i(TAG, "已重写外部浏览器搜索 URL -> $newUrl")
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "startActivity 重写异常", e)
                }
                chain.proceed()
            }

            for (method in ContextWrapper::class.java.declaredMethods) {
                if (method.name == "startActivity") {
                    val paramCount = method.parameterTypes.size
                    if ((paramCount == 1 && method.parameterTypes[0] == Intent::class.java) ||
                        (paramCount == 2 && method.parameterTypes[0] == Intent::class.java &&
                            method.parameterTypes[1] == android.os.Bundle::class.java)
                    ) {
                        Reflect.hookMethod(ctx.api, method, hook)
                    }
                }
            }

            for (method in Activity::class.java.declaredMethods) {
                if (method.name == "startActivity") {
                    val paramCount = method.parameterTypes.size
                    if ((paramCount == 1 && method.parameterTypes[0] == Intent::class.java) ||
                        (paramCount == 2 && method.parameterTypes[0] == Intent::class.java &&
                            method.parameterTypes[1] == android.os.Bundle::class.java)
                    ) {
                        Reflect.hookMethod(ctx.api, method, hook)
                    }
                }
            }

            Logger.i(TAG, "已挂载 startActivity 搜索 URL 重写")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 startActivity 搜索 URL 重写失败", e)
        }
    }

    /**
     * 如果当前 Flyme 搜索选中的是自定义引擎，且 [url] 是已知搜索引擎结果页，
     * 返回重写后的自定义搜索 URL；否则返回 null。
     */
    private fun rewriteSearchUrl(url: String): String? {
        if (!isKnownSearchUrl(url)) return null

        val selectedId = readSelectedEngineId() ?: return null
        val engine = allEngines().firstOrNull { it.id == selectedId } ?: return null
        val keyword = extractKeyword(url) ?: return null
        if (keyword.isBlank()) return null

        return engine.urlTemplate.replace(CustomSearchEngine.PLACEHOLDER, Uri.encode(keyword))
    }

    private fun isKnownSearchUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("baidu.com/s") ||
            lower.contains("so.com/s") ||
            lower.contains("sm.cn/s") ||
            lower.contains("bing.com/search") ||
            (lower.contains("google.") && lower.contains("/search"))
    }

    private fun extractKeyword(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val q = uri.getQueryParameter("q")
            if (!q.isNullOrEmpty()) {
                q
            } else {
                val word = uri.getQueryParameter("word")
                if (!word.isNullOrEmpty()) word else uri.getQueryParameter("wd")
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------------
    // 读取 / 保存 Flyme 搜索当前选中的引擎
    // ---------------------------------------------------------------------

    /**
     * 读取 Flyme 搜索当前选中的引擎 id。
     * 只有用户手动选择过引擎（user_set_engine=true）才读取 key_search_engine。
     */
    private fun readSelectedEngineId(): Int? {
        val ctx = context ?: return null
        return try {
            val mmkv = getSearchPreferencesMmkv(ctx.classLoader) ?: return null
            val userSet = Reflect.callMethod(ctx.api, mmkv, "getBoolean", USER_SET_ENGINE_KEY, false) as? Boolean
                ?: return null
            if (!userSet) return null

            val idStr = Reflect.callMethod(ctx.api, mmkv, "getString", KEY_SEARCH_ENGINE_KEY, "") as? String
                ?: return null
            idStr.toIntOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveSelectedEngine(idStr: String) {
        val ctx = context ?: return
        try {
            val mmkv = getSearchPreferencesMmkv(ctx.classLoader) ?: return
            Reflect.callMethod(ctx.api, mmkv, "putBoolean", USER_SET_ENGINE_KEY, true)
            Reflect.callMethod(ctx.api, mmkv, "putString", KEY_SEARCH_ENGINE_KEY, idStr)
            Logger.i(TAG, "已保存自定义搜索引擎选择: $idStr")
        } catch (e: Throwable) {
            Logger.e(TAG, "保存自定义搜索引擎选择失败", e)
        }
    }

    private fun getSearchPreferencesMmkv(classLoader: ClassLoader): Any? {
        return try {
            val mmkvClass = Reflect.findClass("com.tencent.mmkv.MMKV", classLoader)
            val factory = mmkvClass.declaredMethods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    method.returnType == mmkvClass
            } ?: return null
            factory.isAccessible = true
            factory.invoke(null, MMKV_PREFS_ID, 0)
        } catch (_: Throwable) {
            null
        }
    }
}