package com.karen.flymetool.hook.feature.suggestion

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CustomBrowserHook : FeatureHook {

    private const val TAG = "CustomBrowser"
    private const val PACKAGE_NAME = "com.meizu.suggestion"

    private const val DEFAULT_BROWSER_PACKAGE = "com.android.browser"
    private const val ICON_URI_PREFIX = "assistant.icon.app://"
    private const val ICON_URI_DEFAULT = "assistant.icon.app://com.android.browser"

    private var customBrowserPackage: String = ""

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "custom_browser")) return
        if (lpparam.packageName != PACKAGE_NAME) return

        customBrowserPackage = XposedPrefs.getFeatureString(lpparam, packageName, "custom_browser", "")
        Logger.i(TAG, "Loaded custom browser package: $customBrowserPackage")

        hookStartActivity()
        hookUriParse()
    }

    private fun hookStartActivity() {
        val hookCallback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (customBrowserPackage.isBlank()) return

                    val intent = param.args[0] as? Intent ?: return

                    if (!isBrowserIntent(intent)) return

                    val targetPackage = intent.`package`
                    val component = intent.component

                    val isDefaultBrowser = targetPackage == DEFAULT_BROWSER_PACKAGE ||
                        component?.packageName == DEFAULT_BROWSER_PACKAGE ||
                        component?.className?.contains("com.android.browser", ignoreCase = true) == true

                    if (isDefaultBrowser) {
                        intent.component = null
                        intent.setPackage(customBrowserPackage)

                        Logger.i(TAG, "Redirected browser: $DEFAULT_BROWSER_PACKAGE -> $customBrowserPackage")
                    }

                } catch (e: Throwable) {
                    Logger.e(TAG, "Error in startActivity hook", e)
                }
            }
        }

        try {
            for (method in ContextWrapper::class.java.declaredMethods) {
                if (method.name == "startActivity") {
                    val paramCount = method.parameterTypes.size
                    if ((paramCount == 1 && method.parameterTypes[0] == Intent::class.java) ||
                        (paramCount == 2 && method.parameterTypes[0] == Intent::class.java && method.parameterTypes[1] == Bundle::class.java)) {
                        XposedBridge.hookMethod(method, hookCallback)
                        Logger.i(TAG, "Hooked ContextWrapper.startActivity ($paramCount params)")
                    }
                }
            }

            for (method in Activity::class.java.declaredMethods) {
                if (method.name == "startActivity") {
                    val paramCount = method.parameterTypes.size
                    if ((paramCount == 1 && method.parameterTypes[0] == Intent::class.java) ||
                        (paramCount == 2 && method.parameterTypes[0] == Intent::class.java && method.parameterTypes[1] == Bundle::class.java)) {
                        XposedBridge.hookMethod(method, hookCallback)
                        Logger.i(TAG, "Hooked Activity.startActivity ($paramCount params)")
                    }
                }
            }

        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to hook startActivity", e)
        }
    }

    private fun hookUriParse() {
        try {
            for (method in Uri::class.java.declaredMethods) {
                if (method.name == "parse" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == String::class.java) {

                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (customBrowserPackage.isBlank()) return

                                val uriString = param.args[0] as? String ?: return

                                if (uriString == ICON_URI_DEFAULT) {
                                    val newUriString = "$ICON_URI_PREFIX$customBrowserPackage"
                                    param.result = Uri.parse(newUriString)
                                    Logger.i(TAG, "Replaced icon uri: $uriString -> $newUriString")
                                }

                            } catch (e: Throwable) {
                                Logger.e(TAG, "Error in Uri.parse hook", e)
                            }
                        }
                    })

                    Logger.i(TAG, "Hooked Uri.parse for icon replacement")
                    return
                }
            }

            Logger.e(TAG, "Uri.parse method not found")

        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to hook Uri.parse", e)
        }
    }

    private fun isBrowserIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false

        val uri = intent.data ?: return false
        val scheme = uri.scheme ?: return false

        return scheme == "http" || scheme == "https"
    }
}
