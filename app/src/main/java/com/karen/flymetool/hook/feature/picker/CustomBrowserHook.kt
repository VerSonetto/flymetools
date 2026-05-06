package com.karen.flymetool.hook.feature.picker

import android.content.Intent
import android.net.Uri
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CustomBrowserHook {

    private const val TAG = "PickerCustomBrowser"
    private const val PACKAGE_NAME = "com.meizu.picker"
    private const val DEFAULT_BROWSER_PACKAGE = "com.android.browser"
    private const val DEFAULT_BROWSER_ACTIVITY = "com.android.browser.BrowserActivity"

    private var customBrowserPackage: String = ""

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PACKAGE_NAME) return

        loadCustomBrowserPackage(lpparam)
        hookIntentSetClassName(lpparam)
    }

    private fun loadCustomBrowserPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            customBrowserPackage = XposedPrefs.getFeatureString(lpparam, PACKAGE_NAME, "custom_browser", "")
            Logger.i(TAG, "Loaded custom browser package: $customBrowserPackage")
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to load custom browser package", e)
        }
    }

    /**
     * Hook Intent.setClassName(String, String)
     *
     * 识屏打开链接时硬编码了 intent.setClassName("com.android.browser", "BrowserActivity")，
     * 这导致无论系统默认浏览器是什么都会打开魅族自带浏览器。
     *
     * 移植 Aicy 建议的逻辑：只设 package 不设 component，
     * 让系统按默认浏览器路由到用户选择的浏览器。
     *
     * 额外处理：识屏传入的 URL 有时缺少 scheme（如 1815647920.share.123865.com/...），
     * 魅族自带浏览器能处理这种情况，但第三方浏览器通常不行，所以需要补上 https://。
     */
    private fun hookIntentSetClassName(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (customBrowserPackage.isBlank()) {
            Logger.i(TAG, "No custom browser set, skipping hook")
            return
        }

        val intentClass = XposedHelpers.findClass("android.content.Intent", lpparam.classLoader)

        XposedBridge.hookAllMethods(intentClass, "setClassName", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val packageName = param.args[0] as? String ?: return
                    val className = param.args[1] as? String ?: return

                    if (packageName != DEFAULT_BROWSER_PACKAGE) return
                    if (className != DEFAULT_BROWSER_ACTIVITY) return

                    val intent = param.thisObject as Intent
                    ensureScheme(intent)

                    // 不走 setClassName，改为只设 package，和 Aicy 建议逻辑一致
                    param.result = intent.setPackage(customBrowserPackage)

                    Logger.i(TAG, "Replaced setClassName($DEFAULT_BROWSER_PACKAGE, $DEFAULT_BROWSER_ACTIVITY) -> setPackage($customBrowserPackage), url: ${intent.data}")
                } catch (e: Throwable) {
                    Logger.e(TAG, "Error in setClassName hook", e)
                }
            }
        })

        Logger.i(TAG, "Hooked Intent.setClassName")
    }

    /**
     * 确保 Intent 的 data URI 带有 scheme。
     * 识屏有时传入无 scheme 的 URL（如 xxx.share.123865.com/...），
     * 魅族自带浏览器通过 setClassName 硬编码启动可以处理，
     * 但第三方浏览器依赖 scheme 来注册 intent-filter，缺 scheme 就无法匹配。
     */
    private fun ensureScheme(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme.isNullOrEmpty()) {
            val fixedUri = Uri.parse("https://$uri")
            intent.data = fixedUri
            Logger.i(TAG, "Fixed missing scheme: $uri -> $fixedUri")
        }
    }
}
