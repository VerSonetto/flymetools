package com.karen.flymetool.hook.feature.suggestion

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CustomBrowserHook : FeatureHook {

    private const val TAG = "CustomBrowser"
    private const val PACKAGE_NAME = "com.meizu.suggestion"

    private const val DEFAULT_BROWSER_PACKAGE = "com.android.browser"
    private const val ICON_URI_PREFIX = "assistant.icon.app://"
    private const val ICON_URI_DEFAULT = "assistant.icon.app://com.android.browser"

    private var customBrowserPackage: String = ""
    private var shareChooserResId = 0

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "custom_browser")) return
        if (lpparam.packageName != PACKAGE_NAME) return

        customBrowserPackage = XposedPrefs.getFeatureString(lpparam, packageName, "custom_browser", "")
        Logger.i(TAG, "已加载自定义浏览器包: $customBrowserPackage")

        hookStartActivity()
        if (FlymeVersionUtils.isFlyme12()) {
            resolveResourceIds(lpparam.classLoader)
            hookShareIcon()
        }
        hookUriParse()
    }

    private fun resolveResourceIds(classLoader: ClassLoader) {
        try {
            val drawableClass = XposedHelpers.findClass("com.meizu.suggestion.R\$drawable", classLoader)
            shareChooserResId = XposedHelpers.getStaticIntField(drawableClass, "ic_share_chooser")
            Logger.i(TAG, "已解析 ic_share_chooser: $shareChooserResId")
        } catch (e: Throwable) {
            Logger.e(TAG, "解析 ic_share_chooser 失败", e)
        }
    }

    private fun hookStartActivity() {
        val hookCallback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (customBrowserPackage.isBlank()) return

                    val intent = param.args[0] as? Intent ?: return

                    if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme in listOf("http", "https")) {
                        val pkg = intent.`package` ?: intent.component?.packageName ?: ""
                        if (pkg == DEFAULT_BROWSER_PACKAGE || intent.component?.className?.contains("com.android.browser") == true) {
                            intent.component = null
                            intent.setPackage(customBrowserPackage)
                            Logger.i(TAG, "已重定向浏览器: $DEFAULT_BROWSER_PACKAGE -> $customBrowserPackage")
                        }
                    } else if (FlymeVersionUtils.isFlyme12() && intent.action == Intent.ACTION_CHOOSER) {
                        val share = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        if (share.action == Intent.ACTION_SEND && "text/plain" == share.type) {
                            val url = share.getStringExtra(Intent.EXTRA_TEXT) ?: return
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                param.args[0] = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    setPackage(customBrowserPackage)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                Logger.i(TAG, "已重定向分享 URL -> $customBrowserPackage")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "startActivity Hook 异常", e)
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
                        Logger.i(TAG, "已挂载 ContextWrapper.startActivity（$paramCount 参数）")
                    }
                }
            }

            for (method in Activity::class.java.declaredMethods) {
                if (method.name == "startActivity") {
                    val paramCount = method.parameterTypes.size
                    if ((paramCount == 1 && method.parameterTypes[0] == Intent::class.java) ||
                        (paramCount == 2 && method.parameterTypes[0] == Intent::class.java && method.parameterTypes[1] == Bundle::class.java)) {
                        XposedBridge.hookMethod(method, hookCallback)
                        Logger.i(TAG, "已挂载 Activity.startActivity（$paramCount 参数）")
                    }
                }
            }

        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 startActivity 失败", e)
        }
    }

    private fun hookShareIcon() {
        if (shareChooserResId == 0) return
        try {
            XposedBridge.hookAllMethods(ImageView::class.java, "setImageResource", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (customBrowserPackage.isBlank()) return
                        val resId = param.args[0] as? Int ?: return
                        if (resId != shareChooserResId) return

                        val iv = param.thisObject as? ImageView ?: return
                        val ctx = iv.context ?: return
                        if (ctx.packageName != PACKAGE_NAME) return

                        val size = ctx.resources.getDimensionPixelSize(
                            ctx.resources.getIdentifier("float_ball_width", "dimen", PACKAGE_NAME)
                        )
                        XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("com.meizu.suggestion.util.ImageUtil", ctx.classLoader),
                            "displayCircleImage", iv,
                            Uri.parse("assistant.icon.app://$customBrowserPackage"),
                            size, size
                        )
                        Logger.i(TAG, "已替换分享图标为 $customBrowserPackage")
                    } catch (e: Throwable) {
                        Logger.e(TAG, "替换分享图标异常", e)
                    }
                }
            })
            Logger.i(TAG, "已挂载 ImageView.setImageResource（图标替换）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 setImageResource 失败", e)
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
                                    Logger.i(TAG, "已替换图标 URI: $uriString -> $newUriString")
                                }

                            } catch (e: Throwable) {
                                Logger.e(TAG, "Uri.parse Hook 异常", e)
                            }
                        }
                    })

                    Logger.i(TAG, "已挂载 Uri.parse（图标替换）")
                    return
                }
            }

            Logger.w(TAG, "未找到 Uri.parse 方法")

        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Uri.parse 失败", e)
        }
    }
}
