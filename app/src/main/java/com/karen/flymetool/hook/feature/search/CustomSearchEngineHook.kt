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
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
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

    private var lpparam: XC_LoadPackage.LoadPackageParam? = null
    private var classLoader: ClassLoader? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (packageName != PACKAGE_NAME) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        this.lpparam = lpparam
        this.classLoader = lpparam.classLoader
        refreshEngines()

        hookListPreference()
        hookWebView()
        hookStartActivity()

        Logger.i(
            TAG,
            "自定义搜索引擎已加载：内置 ${SearchEngines.BUILT_IN.size} 个，自定义 ${userEngines.size} 个"
        )
    }

    private fun refreshEngines() {
        val lp = lpparam ?: return
        val json = XposedPrefs.getFeatureString(lp, PACKAGE_NAME, FEATURE_KEY, "")
        userEngines.clear()
        userEngines += CustomSearchEngine.decode(json)
    }

    private fun allEngines(): List<CustomSearchEngine> {
        return SearchEngines.BUILT_IN + userEngines
    }

    // ---------------------------------------------------------------------
    // 注入“设置 → 搜索引擎”列表 + 处理自定义引擎选择
    // ---------------------------------------------------------------------

    private fun hookListPreference() {
        try {
            XposedBridge.hookAllMethods(ListPreference::class.java, "setEntries", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val pref = param.thisObject as? ListPreference ?: return
                        if (pref.key != ENGINE_PREF_KEY) return
                        val original = param.args.getOrNull(0) as? Array<*> ?: return

                        refreshEngines()
                        val engines = allEngines()
                        if (engines.isEmpty()) return

                        val total = original.size + engines.size
                        val newEntries = arrayOfNulls<CharSequence>(total)
                        System.arraycopy(original, 0, newEntries, 0, original.size)
                        engines.forEachIndexed { index, engine ->
                            newEntries[original.size + index] = engine.name
                        }
                        param.args[0] = newEntries
                    } catch (e: Throwable) {
                        Logger.e(TAG, "注入 setEntries 失败", e)
                    }
                }
            })

            XposedBridge.hookAllMethods(ListPreference::class.java, "setEntryValues", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val pref = param.thisObject as? ListPreference ?: return
                        if (pref.key != ENGINE_PREF_KEY) return
                        val original = param.args.getOrNull(0) as? Array<*> ?: return

                        refreshEngines()
                        val engines = allEngines()
                        if (engines.isEmpty()) return

                        val total = original.size + engines.size
                        val newValues = arrayOfNulls<CharSequence>(total)
                        System.arraycopy(original, 0, newValues, 0, original.size)
                        engines.forEachIndexed { index, engine ->
                            newValues[original.size + index] = engine.id.toString()
                        }
                        param.args[0] = newValues
                    } catch (e: Throwable) {
                        Logger.e(TAG, "注入 setEntryValues 失败", e)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val pref = param.thisObject as? ListPreference ?: return
                        if (pref.key != ENGINE_PREF_KEY) return

                        val selectedId = readSelectedEngineId() ?: return
                        val engine = allEngines().firstOrNull { it.id == selectedId } ?: return
                        pref.value = engine.id.toString()
                        pref.summary = engine.name
                    } catch (e: Throwable) {
                        Logger.e(TAG, "恢复自定义引擎选中状态失败", e)
                    }
                }
            })

            // 用户点击自定义引擎时，拦截 Preference.callChangeListener：
            // 自己保存选择并返回 true，避免 Flyme 搜索内部只认识服务端引擎的
            // OnPreferenceChangeListener 拿到自定义 id 后 m14597R -> -1 导致崩溃。
            XposedBridge.hookAllMethods(Preference::class.java, "callChangeListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val pref = param.thisObject as? ListPreference ?: return
                        if (pref.key != ENGINE_PREF_KEY) return

                        val newValue = param.args.getOrNull(0) as? String ?: return
                        val id = newValue.toIntOrNull() ?: return
                        val engine = allEngines().firstOrNull { it.id == id } ?: return

                        saveSelectedEngine(newValue)
                        pref.summary = engine.name
                        // 跳过真正的 OnPreferenceChangeListener，同时让 ListPreference 认为修改被接受
                        param.result = true
                    } catch (e: Throwable) {
                        Logger.e(TAG, "自定义引擎选择处理失败", e)
                    }
                }
            })

            Logger.i(TAG, "已挂载 ListPreference 注入与自定义引擎选择")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 ListPreference 注入失败", e)
        }
    }

    // ---------------------------------------------------------------------
    // 重写搜索 URL
    // ---------------------------------------------------------------------

    private fun hookWebView() {
        try {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val url = param.args.getOrNull(0) as? String ?: return
                        rewriteSearchUrl(url)?.let { newUrl ->
                            param.args[0] = newUrl
                        }
                    } catch (e: Throwable) {
                        Logger.e(TAG, "WebView.loadUrl 重写异常", e)
                    }
                }
            }

            XposedBridge.hookAllMethods(WebView::class.java, "loadUrl", hook)

            Logger.i(TAG, "已挂载 WebView.loadUrl")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 WebView.loadUrl 失败", e)
        }
    }

    private fun hookStartActivity() {
        try {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val intent = param.args.getOrNull(0) as? Intent ?: return
                        if (intent.action != Intent.ACTION_VIEW) return
                        val url = intent.data?.toString() ?: return
                        rewriteSearchUrl(url)?.let { newUrl ->
                            intent.data = Uri.parse(newUrl)
                            Logger.i(TAG, "已重写外部浏览器搜索 URL -> $newUrl")
                        }
                    } catch (e: Throwable) {
                        Logger.e(TAG, "startActivity 重写异常", e)
                    }
                }
            }

            for (method in ContextWrapper::class.java.declaredMethods) {
                if (method.name == "startActivity") {
                    val paramCount = method.parameterTypes.size
                    if ((paramCount == 1 && method.parameterTypes[0] == Intent::class.java) ||
                        (paramCount == 2 && method.parameterTypes[0] == Intent::class.java &&
                            method.parameterTypes[1] == android.os.Bundle::class.java)
                    ) {
                        XposedBridge.hookMethod(method, hook)
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
                        XposedBridge.hookMethod(method, hook)
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
        val cl = classLoader ?: return null
        return try {
            val mmkv = getSearchPreferencesMmkv(cl) ?: return null
            val userSet = XposedHelpers.callMethod(mmkv, "getBoolean", USER_SET_ENGINE_KEY, false) as? Boolean
                ?: return null
            if (!userSet) return null

            val idStr = XposedHelpers.callMethod(mmkv, "getString", KEY_SEARCH_ENGINE_KEY, "") as? String
                ?: return null
            idStr.toIntOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveSelectedEngine(idStr: String) {
        val cl = classLoader ?: return
        try {
            val mmkv = getSearchPreferencesMmkv(cl) ?: return
            XposedHelpers.callMethod(mmkv, "putBoolean", USER_SET_ENGINE_KEY, true)
            XposedHelpers.callMethod(mmkv, "putString", KEY_SEARCH_ENGINE_KEY, idStr)
            Logger.i(TAG, "已保存自定义搜索引擎选择: $idStr")
        } catch (e: Throwable) {
            Logger.e(TAG, "保存自定义搜索引擎选择失败", e)
        }
    }

    private fun getSearchPreferencesMmkv(classLoader: ClassLoader): Any? {
        return try {
            val mmkvClass = XposedHelpers.findClass("com.tencent.mmkv.MMKV", classLoader)
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
