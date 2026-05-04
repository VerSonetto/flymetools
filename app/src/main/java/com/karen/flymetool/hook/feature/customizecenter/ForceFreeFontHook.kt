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
 * 通过拦截 HTTP 请求替换 URL 实现
 */
object ForceFreeFontHook {

    private const val TAG = "ForceFreeFont"
    private const val PACKAGE_NAME = "com.meizu.customizecenter"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PACKAGE_NAME) return

        hookOkHttpUrl(lpparam)
        hookRetrofitUrl(lpparam)
        hookFontLicenseCheck(lpparam)
        hookImeiPermission(lpparam)
    }

    /**
     * Hook OkHttp 请求 URL
     */
    private fun hookOkHttpUrl(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook OkHttp Request.Builder.url(String)
            val requestBuilderClass = XposedHelpers.findClassIfExists(
                "okhttp3.Request\$Builder",
                lpparam.classLoader
            )

            if (requestBuilderClass != null) {
                XposedHelpers.findAndHookMethod(
                    requestBuilderClass,
                    "url",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val url = param.args[0] as? String ?: return
                            if (url.contains("/fonts/public/download") && !url.contains("/trial_url")) {
                                val newUrl = url.replace("/fonts/public/download", "/fonts/public/download/trial_url")
                                param.args[0] = newUrl
                                Logger.d(TAG, "OkHttp URL已替换: $url -> $newUrl")
                            }
                        }
                    }
                )
                Logger.i(TAG, "OkHttp Request.Builder.url Hook完成")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "OkHttp Hook失败", e)
        }

        try {
            // Hook OkHttp HttpUrl.Builder
            val httpUrlBuilderClass = XposedHelpers.findClassIfExists(
                "okhttp3.HttpUrl\$Builder",
                lpparam.classLoader
            )

            if (httpUrlBuilderClass != null) {
                // Hook addPathSegment 或类似方法
                XposedBridge.hookAllMethods(
                    httpUrlBuilderClass,
                    "addPathSegment",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val segment = param.args[0] as? String ?: return
                            if (segment == "download" && !param.thisObject.toString().contains("trial_url")) {
                                // 检查当前路径是否包含 fonts/public
                                val builder = param.thisObject.toString()
                                if (builder.contains("fonts") && builder.contains("public")) {
                                    Logger.d(TAG, "检测到字体下载路径构建")
                                }
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "HttpUrl.Builder Hook失败", e)
        }
    }

    /**
     * Hook Retrofit 请求 URL
     */
    private fun hookRetrofitUrl(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook retrofit2.RequestBuilder
            val requestBuilderClass = XposedHelpers.findClassIfExists(
                "retrofit2.RequestBuilder",
                lpparam.classLoader
            )

            if (requestBuilderClass != null) {
                for (method in requestBuilderClass.declaredMethods) {
                    if (method.parameterTypes.any { it == String::class.java }) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                for (i in param.args.indices) {
                                    val arg = param.args[i] as? String ?: continue
                                    if (arg.contains("/fonts/public/download") && !arg.contains("/trial_url")) {
                                        param.args[i] = arg.replace("/fonts/public/download", "/fonts/public/download/trial_url")
                                        Logger.d(TAG, "Retrofit参数已替换: $arg")
                                    }
                                }
                            }
                        })
                    }
                }
                Logger.i(TAG, "Retrofit RequestBuilder Hook完成")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "Retrofit Hook失败", e)
        }

        try {
            // Hook ServiceMethod 或其他 Retrofit 内部类
            val serviceMethodClass = XposedHelpers.findClassIfExists(
                "retrofit2.ServiceMethod",
                lpparam.classLoader
            )

            if (serviceMethodClass != null) {
                for (method in serviceMethodClass.declaredMethods) {
                    if (method.returnType == String::class.java ||
                        method.parameterTypes.any { it == String::class.java }) {
                        
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val result = param.result as? String ?: return
                                if (result.contains("/fonts/public/download") && !result.contains("/trial_url")) {
                                    param.result = result.replace("/fonts/public/download", "/fonts/public/download/trial_url")
                                    Logger.d(TAG, "ServiceMethod返回值已替换: $result")
                                }
                            }

                            override fun beforeHookedMethod(param: MethodHookParam) {
                                for (i in param.args.indices) {
                                    val arg = param.args[i] as? String ?: continue
                                    if (arg.contains("/fonts/public/download") && !arg.contains("/trial_url")) {
                                        param.args[i] = arg.replace("/fonts/public/download", "/fonts/public/download/trial_url")
                                        Logger.d(TAG, "ServiceMethod参数已替换: $arg")
                                    }
                                }
                            }
                        })
                    }
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "ServiceMethod Hook失败", e)
        }
    }

    /**
     * Hook 字体许可检查
     */
    private fun hookFontLicenseCheck(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val baseLicenseManagerClass = XposedHelpers.findClass(
                "com.meizu.customizecenter.manager.managermoduls.base.BaseLicenseManager",
                lpparam.classLoader
            )

            for (method in baseLicenseManagerClass.declaredMethods) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].name.contains("BaseLicenseManager")) {

                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == false) {
                                param.result = true
                                Logger.d(TAG, "字体许可检查通过")
                            }
                        }
                    })
                    Logger.i(TAG, "字体许可检查Hook完成: ${method.name}")
                    return
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "字体许可检查Hook失败", e)
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
