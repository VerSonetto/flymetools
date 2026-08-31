package com.karen.flymetool.hook.feature.flymeupdate

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import io.github.libxposed.api.XposedInterface

object CaptureUpdateLinkHook : FeatureHook {

    private const val TAG = "CaptureUpdateLink"
    private const val TARGET_PACKAGE = "com.meizu.flyme.update"
    private const val PROVIDER_AUTHORITY = "com.karen.flymetool.captured_update_provider"

    private val CHECK_URL_PATTERNS = listOf(
        "sysupgrade/v2.0/check",
        "v4/firmware/check"
    )

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("capture_update_link")) return
        if (ctx.featureEnabled("disable_update_check")) return
        if (ctx.packageName != TARGET_PACKAGE) return

        hookBasicRequestDeliverResponse(ctx)
    }

    private fun hookBasicRequestDeliverResponse(ctx: HookContext) {
        try {
            val basicRequestClass = Reflect.findClass(
                "com.meizu.flyme.update.network.BasicRequest",
                ctx.classLoader
            )

            Reflect.hookMethodOn(
                ctx.api,
                basicRequestClass,
                "deliverResponse",
                Any::class.java,
            ) { chain ->
                val result = chain.proceed()

                val request = chain.getThisObject() ?: return@hookMethodOn result

                val url = try {
                    Reflect.callMethod(ctx.api, request, "getUrl") as? String
                } catch (_: Throwable) {
                    null
                } ?: return@hookMethodOn result

                if (!CHECK_URL_PATTERNS.any { url.contains(it, ignoreCase = true) }) return@hookMethodOn result

                Logger.i(TAG, "检测到更新检查响应: $url")

                val response = chain.getArg(0) ?: return@hookMethodOn result

                try {
                    val code = Reflect.callMethod(ctx.api, response, "getCode") as? Int
                    if (code != 200) return@hookMethodOn result

                    val value = Reflect.callMethod(ctx.api, response, "getValue") ?: return@hookMethodOn result

                    val newFirmware = try {
                        Reflect.callMethod(ctx.api, value, "getJSONObject", "new")
                    } catch (_: Throwable) {
                        null
                    }

                    val updateUrl = if (newFirmware != null) {
                        Reflect.callMethod(ctx.api, newFirmware, "getString", "updateUrl") as? String
                    } else {
                        Reflect.callMethod(ctx.api, value, "getString", "updateUrl") as? String
                    }

                    if (updateUrl.isNullOrEmpty()) {
                        Logger.d(TAG) { "响应中未找到更新 URL" }
                        return@hookMethodOn result
                    }

                    val firmwareObj = newFirmware ?: value

                    val latestVersion = Reflect.callMethod(ctx.api, firmwareObj, "getString", "latestVersion") as? String ?: ""
                    val fileSize = Reflect.callMethod(ctx.api, firmwareObj, "getString", "fileSize") as? String ?: ""
                    val systemVersion = Reflect.callMethod(ctx.api, firmwareObj, "getString", "systemVersion") as? String ?: ""
                    val verType = Reflect.callMethod(ctx.api, firmwareObj, "getString", "verType") as? String ?: ""
                    val packageType = try {
                        Reflect.callMethod(ctx.api, firmwareObj, "getIntValue", "packageType") as? Int ?: 0
                    } catch (_: Throwable) {
                        0
                    }

                    Logger.i(TAG, "已捕获更新链接: $updateUrl")
                    Logger.i(TAG, "版本: $latestVersion, 大小: $fileSize, verType: $verType, packageType: $packageType")

                    saveUpdateInfo(ctx.api, updateUrl, latestVersion, fileSize, systemVersion, verType, packageType)
                } catch (e: Throwable) {
                    Logger.e(TAG, "提取更新信息失败", e)
                }

                return@hookMethodOn result
            }

            Logger.i(TAG, "已挂载 BasicRequest.deliverResponse")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 BasicRequest.deliverResponse 失败", e)
        }
    }

    private fun saveUpdateInfo(
        api: XposedInterface,
        updateUrl: String,
        latestVersion: String,
        fileSize: String,
        systemVersion: String,
        verType: String,
        packageType: Int
    ) {
        try {
            val activityThread = Reflect.callStaticMethod(
                api,
                Reflect.findClass("android.app.ActivityThread", null),
                "currentActivityThread"
            )
            val context = Reflect.callMethod(api, activityThread, "getApplication") as? Context ?: return

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