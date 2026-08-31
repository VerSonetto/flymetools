package com.karen.flymetool.hook.feature.customizecenter

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

object ForceFreeFontHook : FeatureHook {

    private const val TAG = "ForceFreeFont"
    private const val PACKAGE_NAME = "com.meizu.customizecenter"
    private const val TARGET_PATH = "/fonts/public/download"
    private const val REPLACEMENT_PATH = "/fonts/public/download/trial_url"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("force_free_font")) return
        if (ctx.packageName != PACKAGE_NAME) return

        hookStringConcat(ctx)
        hookStringBuilder(ctx)
        // Flyme 12+ 字体LicenseManager混淆后类名不带LicenseManager，
        // ClassLoader.loadClass 关键词匹配无法命中。许可检查由
        // ForceFreeThemeHook 的 BaseLicenseManager.c() 统一处理
        if (!FlymeVersionUtils.isFlyme12()) {
            hookFontLicenseCheck(ctx)
        }
        hookImeiPermission(ctx)
    }

    private fun hookStringConcat(ctx: HookContext) {
        try {
            Reflect.hookMethodOn(ctx.api, String::class.java, "concat", String::class.java) { chain ->
                // ---- before ----
                val thisStr = chain.getThisObject() as? String ?: return@hookMethodOn chain.proceed()
                val argStr = chain.getArg(0) as? String ?: return@hookMethodOn chain.proceed()

                // 原版首段：thisStr 命中时对 args[0] 赋原值（无副作用），仅保留判断语义
                var replacementArg: String? = null
                if (thisStr.contains(TARGET_PATH) && !thisStr.contains("trial_url")) {
                    // param.args[0] = argStr（无实际变化）
                }

                if (argStr.contains(TARGET_PATH) && !argStr.contains("trial_url")) {
                    replacementArg = argStr.replace(TARGET_PATH, REPLACEMENT_PATH)
                    Logger.d(TAG) { "String.concat 参数已替换" }
                }

                val result = if (replacementArg != null) {
                    val newArgs = chain.getArgs().toMutableList().apply { set(0, replacementArg) }.toTypedArray()
                    chain.proceed(newArgs)
                } else {
                    chain.proceed()
                }

                // ---- after ----
                val resultStr = result as? String ?: return@hookMethodOn result
                if (resultStr.contains(TARGET_PATH) && !resultStr.contains("trial_url")) {
                    Logger.d(TAG) { "String.concat 结果已替换" }
                    return@hookMethodOn resultStr.replace(TARGET_PATH, REPLACEMENT_PATH)
                }
                return@hookMethodOn result
            }
            Logger.i(TAG, "String.concat Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "String.concat Hook失败", e)
        }
    }

    private fun hookStringBuilder(ctx: HookContext) {
        try {
            Reflect.hookMethodOn(ctx.api, StringBuilder::class.java, "toString") { chain ->
                val result = chain.proceed()
                val resultStr = result as? String ?: return@hookMethodOn result
                if (resultStr.contains(TARGET_PATH) && !resultStr.contains("trial_url")) {
                    Logger.d(TAG) { "StringBuilder.toString 结果已替换" }
                    return@hookMethodOn resultStr.replace(TARGET_PATH, REPLACEMENT_PATH)
                }
                return@hookMethodOn result
            }
            Logger.i(TAG, "StringBuilder.toString Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "StringBuilder Hook失败", e)
        }

        try {
            Reflect.hookMethodOn(ctx.api, StringBuffer::class.java, "toString") { chain ->
                val result = chain.proceed()
                val resultStr = result as? String ?: return@hookMethodOn result
                if (resultStr.contains(TARGET_PATH) && !resultStr.contains("trial_url")) {
                    Logger.d(TAG) { "StringBuffer.toString 结果已替换" }
                    return@hookMethodOn resultStr.replace(TARGET_PATH, REPLACEMENT_PATH)
                }
                return@hookMethodOn result
            }
            Logger.i(TAG, "StringBuffer.toString Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "StringBuffer Hook失败", e)
        }
    }

    private fun hookFontLicenseCheck(ctx: HookContext) {
        try {
            Reflect.hookMethodOn(ctx.api, ClassLoader::class.java, "loadClass", String::class.java) { chain ->
                val result = chain.proceed()
                val className = chain.getArg(0) as? String ?: return@hookMethodOn result
                val clazz = result as? Class<*> ?: return@hookMethodOn result

                if (className.contains("LicenseManager", ignoreCase = true)) {
                    for (method in clazz.declaredMethods) {
                        if (method.returnType == Boolean::class.javaPrimitiveType &&
                            method.parameterTypes.size == 1) {

                            Reflect.hookMethod(ctx.api, method) { innerChain ->
                                val innerResult = innerChain.proceed()
                                if (innerResult == false) {
                                    Logger.d(TAG) { "许可检查通过 in ${clazz.simpleName}" }
                                    return@hookMethod true
                                }
                                return@hookMethod innerResult
                            }
                            Logger.i(TAG, "已挂载许可检查 in ${clazz.name}")
                        }
                    }
                }
                return@hookMethodOn result
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "许可检查Hook失败", e)
        }
    }

    private fun hookImeiPermission(ctx: HookContext) {
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
}