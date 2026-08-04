package com.karen.flymetool.hook.feature.flymeupdate

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CaptureUpdateLinkHook : FeatureHook {

    private const val TAG = "CaptureUpdateLink"
    private const val TARGET_PACKAGE = "com.meizu.flyme.update"
    private const val PROVIDER_AUTHORITY = "com.karen.flymetool.captured_update_provider"

    private val CHECK_URL_PATTERNS = listOf(
        "sysupgrade/v2.0/check",
        "v4/firmware/check"
    )

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "capture_update_link")) return
        if (XposedPrefs.isFeatureEnabled(lpparam, packageName, "disable_update_check")) return
        if (lpparam.packageName != TARGET_PACKAGE) return

        hookBasicRequestDeliverResponse(lpparam)
    }

    private fun hookBasicRequestDeliverResponse(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val basicRequestClass = XposedHelpers.findClass(
                "com.meizu.flyme.update.network.BasicRequest",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                basicRequestClass,
                "deliverResponse",
                Any::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val request = param.thisObject ?: return

                        val url = try {
                            XposedHelpers.callMethod(request, "getUrl") as? String
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        if (!CHECK_URL_PATTERNS.any { url.contains(it, ignoreCase = true) }) return

                        Logger.i(TAG, "检测到更新检查响应: $url")

                        val response = param.args[0] ?: return

                        try {
                            val code = XposedHelpers.callMethod(response, "getCode") as? Int
                            if (code != 200) return

                            val value = XposedHelpers.callMethod(response, "getValue") ?: return

                            val newFirmware = try {
                                XposedHelpers.callMethod(value, "getJSONObject", "new")
                            } catch (_: Throwable) {
                                null
                            }

                            val updateUrl = if (newFirmware != null) {
                                XposedHelpers.callMethod(newFirmware, "getString", "updateUrl") as? String
                            } else {
                                XposedHelpers.callMethod(value, "getString", "updateUrl") as? String
                            }

                            if (updateUrl.isNullOrEmpty()) {
                                Logger.d(TAG) { "响应中未找到更新 URL" }
                                return
                            }

                            val firmwareObj = newFirmware ?: value

                            val latestVersion = XposedHelpers.callMethod(firmwareObj, "getString", "latestVersion") as? String ?: ""
                            val fileSize = XposedHelpers.callMethod(firmwareObj, "getString", "fileSize") as? String ?: ""
                            val systemVersion = XposedHelpers.callMethod(firmwareObj, "getString", "systemVersion") as? String ?: ""
                            val verType = XposedHelpers.callMethod(firmwareObj, "getString", "verType") as? String ?: ""
                            val packageType = try {
                                XposedHelpers.callMethod(firmwareObj, "getIntValue", "packageType") as? Int ?: 0
                            } catch (_: Throwable) {
                                0
                            }

                            Logger.i(TAG, "已捕获更新链接: $updateUrl")
                            Logger.i(TAG, "版本: $latestVersion, 大小: $fileSize, verType: $verType, packageType: $packageType")

                            saveUpdateInfo(updateUrl, latestVersion, fileSize, systemVersion, verType, packageType)
                        } catch (e: Throwable) {
                            Logger.e(TAG, "提取更新信息失败", e)
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载 BasicRequest.deliverResponse")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 BasicRequest.deliverResponse 失败", e)
        }
    }

    private fun saveUpdateInfo(
        updateUrl: String,
        latestVersion: String,
        fileSize: String,
        systemVersion: String,
        verType: String,
        packageType: Int
    ) {
        try {
            val activityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread"
            )
            val context = XposedHelpers.callMethod(activityThread, "getApplication") as? Context ?: return

            val values = ContentValues().apply {
                put("updateUrl", updateUrl)
                put("latestVersion", latestVersion)
                put("fileSize", fileSize)
                put("systemVersion", systemVersion)
                put("verType", verType)
                put("packageType", packageType)
                put("timestamp", System.currentTimeMillis())
            }

            val uri = Uri.parse("content://$PROVIDER_AUTHORITY/update")
            val result = context.contentResolver.insert(uri, values)

            if (result != null) {
                Logger.i(TAG, "已通过 ContentProvider 保存更新信息")
            } else {
                Logger.e(TAG, "ContentProvider insert 返回 null")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "通过 ContentProvider 保存更新信息失败", e)
        }
    }
}
