package com.karen.flymetool.hook.feature.flymeupdate

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import io.github.libxposed.api.XposedInterface

object DisableUpdateCheckHook : FeatureHook {

    private const val TAG = "DisableUpdateCheck"
    private const val TARGET_PACKAGE = "com.meizu.flyme.update"

    private val BLOCKED_URL_PATTERNS = listOf(
        "sysupgrade",
        "sysupgradeex",
        "v4/firmware"
    )

    private const val FIRMWARE_CACHE_KEY = "key_check_new_upgrade_firmware_cache"

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("disable_update_check")) return
        if (ctx.packageName != TARGET_PACKAGE) return

        hookBasicRequestDeliverResponse(ctx)
        hookFirmwareCache(ctx.api)
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
                val request = chain.getThisObject() ?: return@hookMethodOn chain.proceed()

                val url = try {
                    Reflect.callMethod(ctx.api, request, "getUrl") as? String
                } catch (_: Throwable) {
                    null
                } ?: return@hookMethodOn chain.proceed()

                if (!isUpdateCheckUrl(url)) return@hookMethodOn chain.proceed()

                Logger.i(TAG, "劫持更新检查响应: $url")

                val response = chain.getArg(0) ?: return@hookMethodOn chain.proceed()

                try {
                    val code = Reflect.callMethod(ctx.api, response, "getCode") as? Int
                    if (code == 200) {
                        Reflect.callMethod(ctx.api, response, "setValue", null as Any?)
                        Logger.i(TAG, "已清空响应值 -> 无更新")
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "劫持响应值失败", e)
                }

                return@hookMethodOn chain.proceed()
            }

            Logger.i(TAG, "已挂载 BasicRequest.deliverResponse")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 BasicRequest.deliverResponse 失败", e)
        }
    }

    private fun isUpdateCheckUrl(url: String): Boolean {
        return BLOCKED_URL_PATTERNS.any { url.contains(it, ignoreCase = true) }
    }

    private fun hookFirmwareCache(api: XposedInterface) {
        try {
            val spImplClass = Class.forName("android.app.SharedPreferencesImpl")

            Reflect.hookMethodOn(
                api,
                spImplClass,
                "getString",
                String::class.java,
                String::class.java,
            ) { chain ->
                val result = chain.proceed()

                val key = chain.getArg(0) as? String ?: return@hookMethodOn result
                if (key == FIRMWARE_CACHE_KEY) {
                    Logger.d(TAG) { "已清除固件升级缓存" }
                    return@hookMethodOn ""
                }

                return@hookMethodOn result
            }

            Logger.i(TAG, "已挂载固件缓存")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载固件缓存失败", e)
        }
    }
}