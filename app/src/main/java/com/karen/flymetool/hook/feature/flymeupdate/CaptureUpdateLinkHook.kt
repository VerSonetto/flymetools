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

    private const val NORMAL_CHECK_URL_PATTERN = "sysupgrade/v2.0/check"

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

                        if (!url.contains(NORMAL_CHECK_URL_PATTERN, ignoreCase = true)) return

                        Logger.i(TAG, "Detected update check response: $url")

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
                                Logger.d(TAG, "No update URL found in response")
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

                            Logger.i(TAG, "Captured update link: $updateUrl")
                            Logger.i(TAG, "Version: $latestVersion, Size: $fileSize, verType: $verType, packageType: $packageType")

                            saveUpdateInfo(updateUrl, latestVersion, fileSize, systemVersion, verType, packageType)
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Failed to extract update info", e)
                        }
                    }
                }
            )

            Logger.i(TAG, "BasicRequest.deliverResponse hook installed")
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to hook BasicRequest.deliverResponse", e)
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
                Logger.i(TAG, "Update info saved via ContentProvider")
            } else {
                Logger.e(TAG, "ContentProvider insert returned null")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to save update info via ContentProvider", e)
        }
    }
}
