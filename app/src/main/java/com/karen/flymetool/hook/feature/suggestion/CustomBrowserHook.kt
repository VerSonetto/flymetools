package com.karen.flymetool.hook.feature.suggestion

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import io.github.libxposed.api.XposedInterface.Chain
import com.karen.flymetool.util.FlymeVersionUtils

object CustomBrowserHook : FeatureHook {

    private const val TAG = "CustomBrowser"
    private const val PACKAGE_NAME = "com.meizu.suggestion"

    private const val DEFAULT_BROWSER_PACKAGE = "com.android.browser"
    private const val ICON_URI_PREFIX = "assistant.icon.app://"
    private const val ICON_URI_DEFAULT = "assistant.icon.app://com.android.browser"

    private var customBrowserPackage: String = ""
    private var shareChooserResId = 0

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("custom_browser")) return
        if (ctx.packageName != PACKAGE_NAME) return

        customBrowserPackage = ctx.featureString("custom_browser", "")
        Logger.i(TAG, "已加载自定义浏览器包: $customBrowserPackage")

        hookStartActivity(ctx)
        if (FlymeVersionUtils.isFlyme12()) {
            resolveResourceIds(ctx.classLoader)
            hookShareIcon(ctx)
        }
        hookUriParse(ctx)
    }

    private fun resolveResourceIds(classLoader: ClassLoader) {
        try {
            val drawableClass = Reflect.findClass("com.meizu.suggestion.R\$drawable", classLoader)
            shareChooserResId = Reflect.getStaticIntField(drawableClass, "ic_share_chooser")
            Logger.i(TAG, "已解析 ic_share_chooser: $shareChooserResId")
        } catch (e: Throwable) {
            Logger.e(TAG, "解析 ic_share_chooser 失败", e)
        }
    }

    private fun hookStartActivity(ctx: HookContext) {
        val hookCallback: (Chain) -> Any? = hook@ { chain ->
            try {
                if (customBrowserPackage.isBlank()) return@hook chain.proceed()

                val intent = chain.getArg(0) as? Intent ?: return@hook chain.proceed()

                if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme in listOf("http", "https")) {
                    val pkg = intent.`package` ?: intent.component?.packageName ?: ""
                    if (pkg == DEFAULT_BROWSER_PACKAGE || intent.component?.className?.contains("com.android.browser") == true) {
                        intent.component = null
                        intent.setPackage(customBrowserPackage)
                        Logger.i(TAG, "已重定向浏览器: $DEFAULT_BROWSER_PACKAGE -> $customBrowserPackage")
                    }
                } else if (FlymeVersionUtils.isFlyme12() && intent.action == Intent.ACTION_CHOOSER) {
                    val share = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return@hook chain.proceed()
                    if (share.action == Intent.ACTION_SEND && "text/plain" == share.type) {
                        val url = share.getStringExtra(Intent.EXTRA_TEXT) ?: return@hook chain.proceed()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val newIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                setPackage(customBrowserPackage)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            Logger.i(TAG, "已重定向分享 URL -> $customBrowserPackage")
                            val newArgs = chain.getArgs().toMutableList().apply { set(0, newIntent) }.toTypedArray()
                            return@hook chain.proceed(newArgs)
                        }
                    }
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "startActivity Hook 异常", e)
            }
            chain.proceed()
        }

        try {
            for (method in ContextWrapper::class.java.declaredMethods) {
                if (method.name == "startActivity") {
                    val paramCount = method.parameterTypes.size
                    if ((paramCount == 1 && method.parameterTypes[0] == Intent::class.java) ||
                        (paramCount == 2 && method.parameterTypes[0] == Intent::class.java && method.parameterTypes[1] == Bundle::class.java)) {
                        Reflect.hookMethod(ctx.api, method, hookCallback)
                        Logger.i(TAG, "已挂载 ContextWrapper.startActivity（$paramCount 参数）")
                    }
                }
            }

            for (method in Activity::class.java.declaredMethods) {
                if (method.name == "startActivity") {
                    val paramCount = method.parameterTypes.size
                    if ((paramCount == 1 && method.parameterTypes[0] == Intent::class.java) ||
                        (paramCount == 2 && method.parameterTypes[0] == Intent::class.java && method.parameterTypes[1] == Bundle::class.java)) {
                        Reflect.hookMethod(ctx.api, method, hookCallback)
                        Logger.i(TAG, "已挂载 Activity.startActivity（$paramCount 参数）")
                    }
                }
            }

        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 startActivity 失败", e)
        }
    }

    private fun hookShareIcon(ctx: HookContext) {
        if (shareChooserResId == 0) return
        try {
            Reflect.hookAllMethods(ctx.api, ImageView::class.java, "setImageResource") { chain ->
                val result = chain.proceed()
                try {
                    if (customBrowserPackage.isBlank()) return@hookAllMethods result
                    val resId = chain.getArg(0) as? Int ?: return@hookAllMethods result
                    if (resId != shareChooserResId) return@hookAllMethods result

                    val iv = chain.getThisObject() as? ImageView ?: return@hookAllMethods result
                    val imageCtx = iv.context ?: return@hookAllMethods result
                    if (imageCtx.packageName != PACKAGE_NAME) return@hookAllMethods result

                    val size = imageCtx.resources.getDimensionPixelSize(
                        imageCtx.resources.getIdentifier("float_ball_width", "dimen", PACKAGE_NAME)
                    )
                    Reflect.callStaticMethod(
                        ctx.api,
                        Reflect.findClass("com.meizu.suggestion.util.ImageUtil", imageCtx.classLoader),
                        "displayCircleImage", iv,
                        Uri.parse("assistant.icon.app://$customBrowserPackage"),
                        size, size
                    )
                    Logger.i(TAG, "已替换分享图标为 $customBrowserPackage")
                } catch (e: Throwable) {
                    Logger.e(TAG, "替换分享图标异常", e)
                }
                return@hookAllMethods result
            }
            Logger.i(TAG, "已挂载 ImageView.setImageResource（图标替换）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 setImageResource 失败", e)
        }
    }

    private fun hookUriParse(ctx: HookContext) {
        try {
            for (method in Uri::class.java.declaredMethods) {
                if (method.name == "parse" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == String::class.java) {

                    Reflect.hookMethod(ctx.api, method) { chain ->
                        val result = chain.proceed()
                        try {
                            if (customBrowserPackage.isBlank()) return@hookMethod result

                            val uriString = chain.getArg(0) as? String ?: return@hookMethod result

                            if (uriString == ICON_URI_DEFAULT) {
                                val newUriString = "$ICON_URI_PREFIX$customBrowserPackage"
                                Logger.i(TAG, "已替换图标 URI: $uriString -> $newUriString")
                                return@hookMethod Uri.parse(newUriString)
                            }

                        } catch (e: Throwable) {
                            Logger.e(TAG, "Uri.parse Hook 异常", e)
                        }
                        return@hookMethod result
                    }

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