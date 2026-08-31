package com.karen.flymetool.hook.feature.flymeupdate

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object ForceFullPackageHook : FeatureHook {

    private const val TAG = "ForceFullPackage"
    private var targetMaskId: String? = null

    private val CHECK_URLS = listOf("/v4/firmware/check", "/sysupgrade/v2.0/check")

    override fun handle(ctx: HookContext) {
        if (!ctx.featureEnabled("force_full_package")) return
        if (ctx.packageName != "com.meizu.flyme.update") return

        hookGenerateNewSysParam(ctx)
        hookDeliverResponse(ctx)
    }

    private fun hookGenerateNewSysParam(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(
                "com.meizu.flyme.update.network.RequestManager",
                ctx.classLoader
            )
            Reflect.hookMethodOn(
                ctx.api, clazz, "generateNewSysParam",
                String::class.java, Boolean::class.javaPrimitiveType,
            ) { chain ->
                val result = chain.proceed()
                val maskId = targetMaskId ?: return@hookMethodOn result
                val json = result as? String ?: return@hookMethodOn result
                val obj = org.json.JSONObject(json)
                val sysVer = obj.optString("sysVer", "")
                if (sysVer.isEmpty()) return@hookMethodOn result

                obj.put("sysVer", maskId)
                obj.put("version", maskId)
                Logger.i(TAG, "已伪造 sysVer: $sysVer -> $maskId")
                return@hookMethodOn obj.toString()
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "hookGenerateNewSysParam 失败", e)
        }
    }

    private fun hookDeliverResponse(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(
                "com.meizu.flyme.update.network.BasicRequest",
                ctx.classLoader
            )
            Reflect.hookMethodOn(
                ctx.api, clazz, "deliverResponse",
                Any::class.java,
            ) { chain ->
                val request = chain.getThisObject() ?: return@hookMethodOn chain.proceed()
                val url = try {
                    Reflect.callMethod(ctx.api, request, "getUrl") as? String
                } catch (_: Throwable) { null } ?: return@hookMethodOn chain.proceed()
                if (!CHECK_URLS.any { url.contains(it) }) return@hookMethodOn chain.proceed()

                val response = chain.getArg(0) ?: return@hookMethodOn chain.proceed()
                val code = try {
                    Reflect.callMethod(ctx.api, response, "getCode") as? Int ?: 0
                } catch (_: Throwable) { 0 }
                if (code != 200) return@hookMethodOn chain.proceed()

                val value = try {
                    Reflect.callMethod(ctx.api, response, "getValue")
                } catch (_: Throwable) { null } ?: return@hookMethodOn chain.proceed()

                // 首次检查：从新版本信息里抓目标 mask id
                if (targetMaskId == null) {
                    val newObj = try {
                        Reflect.callMethod(ctx.api, value, "getJSONObject", "new")
                    } catch (_: Throwable) { null }
                    if (newObj != null) {
                        targetMaskId = try {
                            Reflect.callMethod(ctx.api, newObj, "getString", "latestVersion") as? String
                        } catch (_: Throwable) { null }
                        if (targetMaskId != null) {
                            Logger.i(TAG, "已捕获目标 mask id: $targetMaskId")
                        }
                    }
                    return@hookMethodOn chain.proceed()
                }

                // 伪造响应：基于当前版本构造 fake new
                val newObj = try {
                    Reflect.callMethod(ctx.api, value, "getJSONObject", "new")
                } catch (_: Throwable) { null }

                val hasRealUpdate = newObj != null && try {
                    Reflect.callMethod(ctx.api, newObj, "getBoolean", "existsUpdate") as? Boolean ?: false
                } catch (_: Throwable) { false }

                if (!hasRealUpdate) {
                    val curObj = try {
                        Reflect.callMethod(ctx.api, value, "getJSONObject", "cur")
                    } catch (_: Throwable) { null } ?: return@hookMethodOn chain.proceed()

                    val curStr = Reflect.callMethod(ctx.api, curObj, "toString") as? String ?: return@hookMethodOn chain.proceed()
                    val jsonClz = Reflect.findClass(
                        "com.alibaba.fastjson.JSON",
                        ctx.classLoader
                    )
                    val fakeNew = Reflect.callStaticMethod(ctx.api, jsonClz, "parseObject", curStr)

                    Reflect.callMethod(ctx.api, fakeNew, "put", "existsUpdate", true as Any)
                    Reflect.callMethod(ctx.api, fakeNew, "put", "needUpdate", true as Any)
                    Reflect.callMethod(ctx.api, fakeNew, "put", "packageType", 0 as Any)
                    Reflect.callMethod(ctx.api, value, "put", "new", fakeNew)
                    Logger.i(TAG, "已基于当前创建伪造 new")
                } else {
                    val pkgType = try {
                        Reflect.callMethod(ctx.api, newObj, "getIntValue", "packageType") as? Int ?: 0
                    } catch (_: Throwable) { 0 }
                    if (pkgType == 1) {
                        Reflect.callMethod(ctx.api, newObj, "put", "packageType", 0 as Any)
                        Logger.i(TAG, "已强制 packageType 1 -> 0")
                    }
                }

                return@hookMethodOn chain.proceed()
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "hookDeliverResponse 失败", e)
        }
    }
}