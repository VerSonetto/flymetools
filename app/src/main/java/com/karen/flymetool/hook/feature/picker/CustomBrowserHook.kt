package com.karen.flymetool.hook.feature.picker

import android.content.Intent
import android.net.Uri
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CustomBrowserHook : FeatureHook {

    private const val TAG = "PickerCustomBrowser"
    private const val PACKAGE_NAME = "com.meizu.picker"
    private const val DEFAULT_BROWSER_PACKAGE = "com.android.browser"
    private const val DEFAULT_BROWSER_ACTIVITY = "com.android.browser.BrowserActivity"

    private var customBrowserPackage: String = ""

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "custom_browser")) return
        if (lpparam.packageName != PACKAGE_NAME) return

        customBrowserPackage = XposedPrefs.getFeatureString(lpparam, packageName, "custom_browser", "")
        Logger.i(TAG, "Loaded custom browser package: $customBrowserPackage")

        hookIntentSetClassName(lpparam)
    }

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

                    param.result = intent.setPackage(customBrowserPackage)

                    Logger.i(TAG, "Replaced setClassName($DEFAULT_BROWSER_PACKAGE, $DEFAULT_BROWSER_ACTIVITY) -> setPackage($customBrowserPackage), url: ${intent.data}")
                } catch (e: Throwable) {
                    Logger.e(TAG, "Error in setClassName hook", e)
                }
            }
        })

        Logger.i(TAG, "Hooked Intent.setClassName")
    }

    private fun ensureScheme(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme.isNullOrEmpty()) {
            val fixedUri = Uri.parse("https://$uri")
            intent.data = fixedUri
            Logger.i(TAG, "Fixed missing scheme: $uri -> $fixedUri")
        }
    }
}
