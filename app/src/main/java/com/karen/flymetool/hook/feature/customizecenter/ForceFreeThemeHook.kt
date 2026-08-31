package com.karen.flymetool.hook.feature.customizecenter

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object ForceFreeThemeHook : FeatureHook {

    private const val TAG = "ForceFreeTheme"
    private const val PACKAGE_NAME = "com.meizu.customizecenter"
    private const val TARGET_CLASS = "com.meizu.customizecenter.model.info.home.CustomizerInfo"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("force_free_theme")) return
        if (ctx.packageName != PACKAGE_NAME) return

        hookPriceToZero(ctx)
        hookImeiForDownload(ctx)
        hookDownloadUrlToTrial(ctx)
        hookLicenseCheck(ctx)
    }

    private fun hookPriceToZero(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(TARGET_CLASS, ctx.classLoader)

            var hookedCount = 0
            for (method in clazz.declaredMethods) {
                if (method.returnType == Double::class.javaPrimitiveType && method.parameterTypes.isEmpty()) {
                    Reflect.hookMethod(ctx.api, method) { chain ->
                        chain.proceed()
                        0.0
                    }
                    hookedCount++
                }
            }

            Logger.i(TAG, "价格Hook完成，共 hook $hookedCount 个方法")
        } catch (e: Throwable) {
            Logger.e(TAG, "价格Hook失败", e)
        }
    }

    private fun hookImeiForDownload(ctx: HookContext) {
        try {
            Reflect.hookAllMethods(ctx.api, ContextWrapper::class.java, "checkSelfPermission") { chain ->
                val result = chain.proceed()
                if (chain.getArg(0) == Manifest.permission.READ_PHONE_STATE) {
                    if (result as Int != PackageManager.PERMISSION_GRANTED) {
                        Logger.d(TAG) { "READ_PHONE_STATE 权限已授予" }
                        return@hookAllMethods PackageManager.PERMISSION_GRANTED
                    }
                }
                return@hookAllMethods result
            }

            Logger.i(TAG, "IMEI权限Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "IMEI权限Hook失败", e)
        }
    }

    private fun hookDownloadUrlToTrial(ctx: HookContext) {
        try {
            Reflect.hookAllMethods(
                ctx.api,
                Class.forName("android.app.SharedPreferencesImpl"),
                "getString"
            ) { chain ->
                val result = chain.proceed()
                val resultStr = result as? String ?: return@hookAllMethods result
                if (resultStr == "/themes/public/download") {
                    Logger.d(TAG) { "SharedPreferences URL 替换: $resultStr -> /themes/public/download/trial_url" }
                    return@hookAllMethods "/themes/public/download/trial_url"
                }
                return@hookAllMethods result
            }

            Logger.i(TAG, "下载URL替换Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "下载URL替换Hook失败", e)
        }
    }

    private fun hookLicenseCheck(ctx: HookContext) {
        try {
            val baseClazz = Reflect.findClass(
                "com.meizu.customizecenter.manager.managermoduls.base.BaseLicenseManager",
                ctx.classLoader
            )

            for (method in baseClazz.declaredMethods) {
                if (method.returnType == Boolean::class.javaPrimitiveType
                    && method.parameterTypes.size == 1
                    && method.parameterTypes[0].name.contains("BaseLicenseManager")) {
                    Reflect.hookMethod(ctx.api, method) { chain ->
                        val result = chain.proceed()
                        if (result == false) {
                            return@hookMethod true
                        }
                        return@hookMethod result
                    }
                    Logger.i(TAG, "许可检查Hook完成: ${method.name}")
                    return
                }
            }

            Logger.w(TAG, "未找到许可检查方法")
        } catch (e: Throwable) {
            Logger.e(TAG, "许可检查Hook失败", e)
        }
    }
}