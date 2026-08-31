package com.karen.flymetool.hook.feature.picker

import android.content.Intent
import android.net.Uri
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object CustomBrowserHook : FeatureHook {

    private const val TAG = "PickerCustomBrowser"
    private const val PACKAGE_NAME = "com.meizu.picker"
    private const val DEFAULT_BROWSER_PACKAGE = "com.android.browser"
    private const val DEFAULT_BROWSER_ACTIVITY = "com.android.browser.BrowserActivity"

    private var customBrowserPackage: String = ""

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("custom_browser")) return
        if (ctx.packageName != PACKAGE_NAME) return

        customBrowserPackage = ctx.featureString("custom_browser", "")
        Logger.i(TAG, "已加载自定义浏览器包: $customBrowserPackage")

        hookIntentSetClassName(ctx)
    }

    private fun hookIntentSetClassName(ctx: HookContext) {
        if (customBrowserPackage.isBlank()) {
            Logger.i(TAG, "未设置自定义浏览器，跳过 Hook")
            return
        }

        val intentClass = Reflect.findClass("android.content.Intent", ctx.classLoader)

        Reflect.hookAllMethods(ctx.api, intentClass, "setClassName") { chain ->
            try {
                val packageName = chain.getArg(0) as? String ?: return@hookAllMethods chain.proceed()
                val className = chain.getArg(1) as? String ?: return@hookAllMethods chain.proceed()

                if (packageName != DEFAULT_BROWSER_PACKAGE) return@hookAllMethods chain.proceed()
                if (className != DEFAULT_BROWSER_ACTIVITY) return@hookAllMethods chain.proceed()

                val intent = chain.getThisObject() as Intent
                ensureScheme(intent)

                Logger.i(TAG, "已替换 setClassName($DEFAULT_BROWSER_PACKAGE, $DEFAULT_BROWSER_ACTIVITY) -> setPackage($customBrowserPackage), url: ${intent.data}")
                return@hookAllMethods intent.setPackage(customBrowserPackage)
            } catch (e: Throwable) {
                Logger.e(TAG, "setClassName Hook 异常", e)
            }
            chain.proceed()
        }

        Logger.i(TAG, "已挂载 Intent.setClassName")
    }

    private fun ensureScheme(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme.isNullOrEmpty()) {
            val fixedUri = Uri.parse("https://$uri")
            intent.data = fixedUri
            Logger.i(TAG, "已修复缺失 scheme: $uri -> $fixedUri")
        }
    }
}