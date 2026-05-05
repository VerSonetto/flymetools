package com.karen.flymetool.hook.feature.customizecenter

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger

/**
 * 字体免费下载 Hook
 * 通过 Hook 字符串操作替换路径实现
 */
object ForceFreeFontHook {

    private const val TAG = "ForceFreeFont"
    private const val PACKAGE_NAME = "com.meizu.customizecenter"
    private const val TARGET_PATH = "/fonts/public/download"
    private const val REPLACEMENT_PATH = "/fonts/public/download/trial_url"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PACKAGE_NAME) return

        hookStringConcat(lpparam)
        hookStringBuilder(lpparam)
        hookFontLicenseCheck(lpparam)
        hookImeiPermission(lpparam)
    }

    /**
     * Hook String.concat 方法
     * 当拼接 URL 时检测并替换路径
     */
    private fun hookStringConcat(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                String::class.java,
                "concat",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val thisStr = param.thisObject as String
                        val argStr = param.args[0] as String

                        // 检测字体下载路径
                        if (thisStr.contains(TARGET_PATH) && !thisStr.contains("trial_url")) {
                            val newStr = thisStr.replace(TARGET_PATH, REPLACEMENT_PATH)
                            param.args[0] = argStr
                            // 无法直接修改 thisObject，需要在结果中处理
                        }

                        if (argStr.contains(TARGET_PATH) && !argStr.contains("trial_url")) {
                            param.args[0] = argStr.replace(TARGET_PATH, REPLACEMENT_PATH)
                            Logger.d(TAG, "String.concat 参数已替换")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? String ?: return
                        if (result.contains(TARGET_PATH) && !result.contains("trial_url")) {
                            param.result = result.replace(TARGET_PATH, REPLACEMENT_PATH)
                            Logger.d(TAG, "String.concat 结果已替换")
                        }
                    }
                }
            )
            Logger.i(TAG, "String.concat Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "String.concat Hook失败", e)
        }
    }

    /**
     * Hook StringBuilder.toString
     * URL 通常通过 StringBuilder 构建
     */
    private fun hookStringBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                StringBuilder::class.java,
                "toString",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? String ?: return
                        if (result.contains(TARGET_PATH) && !result.contains("trial_url")) {
                            param.result = result.replace(TARGET_PATH, REPLACEMENT_PATH)
                            Logger.d(TAG, "StringBuilder.toString 结果已替换")
                        }
                    }
                }
            )
            Logger.i(TAG, "StringBuilder.toString Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "StringBuilder Hook失败", e)
        }

        try {
            // Hook StringBuffer.toString
            XposedHelpers.findAndHookMethod(
                StringBuffer::class.java,
                "toString",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? String ?: return
                        if (result.contains(TARGET_PATH) && !result.contains("trial_url")) {
                            param.result = result.replace(TARGET_PATH, REPLACEMENT_PATH)
                            Logger.d(TAG, "StringBuffer.toString 结果已替换")
                        }
                    }
                }
            )
            Logger.i(TAG, "StringBuffer.toString Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "StringBuffer Hook失败", e)
        }
    }

    /**
     * Hook 字体许可检查
     */
    private fun hookFontLicenseCheck(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 通过类特征定位：类名包含 LicenseManager
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        val clazz = param.result as? Class<*> ?: return

                        // 检查类名是否包含 LicenseManager（未混淆的部分）
                        if (className.contains("LicenseManager", ignoreCase = true)) {
                            for (method in clazz.declaredMethods) {
                                if (method.returnType == Boolean::class.javaPrimitiveType &&
                                    method.parameterTypes.size == 1) {

                                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                        override fun afterHookedMethod(param: MethodHookParam) {
                                            if (param.result == false) {
                                                param.result = true
                                                Logger.d(TAG, "许可检查通过 in ${clazz.simpleName}")
                                            }
                                        }
                                    })
                                    Logger.i(TAG, "Hooked license check in ${clazz.name}")
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "许可检查Hook失败", e)
        }
    }

    /**
     * Hook IMEI 权限检查
     */
    private fun hookImeiPermission(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllMethods(
                ContextWrapper::class.java,
                "checkSelfPermission",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == Manifest.permission.READ_PHONE_STATE) {
                            if (param.result as Int != PackageManager.PERMISSION_GRANTED) {
                                param.result = PackageManager.PERMISSION_GRANTED
                                Logger.d(TAG, "READ_PHONE_STATE 权限已授予")
                            }
                        }
                    }
                }
            )
            Logger.i(TAG, "IMEI权限Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "IMEI权限Hook失败", e)
        }
    }
}
