package com.karen.flymetool.hook.feature.customizecenter

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object ForceFreeThemeHook : FeatureHook {

    private const val TAG = "ForceFreeTheme"
    private const val PACKAGE_NAME = "com.meizu.customizecenter"
    private const val TARGET_CLASS = "com.meizu.customizecenter.model.info.home.CustomizerInfo"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "force_free_theme")) return
        if (lpparam.packageName != PACKAGE_NAME) return

        hookPriceToZero(lpparam)
        hookImeiForDownload(lpparam)
        hookDownloadUrlToTrial(lpparam)
        hookLicenseCheck(lpparam)
    }

    private fun hookPriceToZero(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(TARGET_CLASS, lpparam.classLoader)

            var hookedCount = 0
            for (method in clazz.declaredMethods) {
                if (method.returnType == Double::class.javaPrimitiveType && method.parameterTypes.isEmpty()) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = 0.0
                        }
                    })
                    hookedCount++
                }
            }

            Logger.i(TAG, "价格Hook完成，共 hook $hookedCount 个方法")
        } catch (e: Throwable) {
            Logger.e(TAG, "价格Hook失败", e)
        }
    }

    private fun hookImeiForDownload(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllMethods(
                ContextWrapper::class.java,
                "checkSelfPermission",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == Manifest.permission.READ_PHONE_STATE) {
                            if (param.result as Int != PackageManager.PERMISSION_GRANTED) {
                                param.result = PackageManager.PERMISSION_GRANTED
                                Logger.d(TAG) { "READ_PHONE_STATE 权限已授予" }
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

    private fun hookDownloadUrlToTrial(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllMethods(
                Class.forName("android.app.SharedPreferencesImpl"),
                "getString",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? String ?: return
                        if (result == "/themes/public/download") {
                            param.result = "/themes/public/download/trial_url"
                            Logger.d(TAG) { "SharedPreferences URL 替换: $result -> /themes/public/download/trial_url" }
                        }
                    }
                }
            )

            Logger.i(TAG, "下载URL替换Hook完成")
        } catch (e: Throwable) {
            Logger.e(TAG, "下载URL替换Hook失败", e)
        }
    }

    private fun hookLicenseCheck(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val baseClazz = XposedHelpers.findClass(
                "com.meizu.customizecenter.manager.managermoduls.base.BaseLicenseManager",
                lpparam.classLoader
            )

            for (method in baseClazz.declaredMethods) {
                if (method.returnType == Boolean::class.javaPrimitiveType
                    && method.parameterTypes.size == 1
                    && method.parameterTypes[0].name.contains("BaseLicenseManager")) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == false) {
                                param.result = true
                            }
                        }
                    })
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
