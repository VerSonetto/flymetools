package com.karen.flymetool.hook.feature.flymeupdate

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object ForceFullPackageHook : FeatureHook {

    private const val TAG = "ForceFullPackage"
    private var targetMaskId: String? = null

    private val CHECK_URLS = listOf("/v4/firmware/check", "/sysupgrade/v2.0/check")

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "force_full_package")) return
        if (lpparam.packageName != "com.meizu.flyme.update") return

        hookGenerateNewSysParam(lpparam)
        hookDeliverResponse(lpparam)
    }

    private fun hookGenerateNewSysParam(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.meizu.flyme.update.network.RequestManager",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "generateNewSysParam",
                String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val maskId = targetMaskId ?: return
                        val json = param.result as? String ?: return
                        val obj = org.json.JSONObject(json)
                        val sysVer = obj.optString("sysVer", "")
                        if (sysVer.isEmpty()) return

                        obj.put("sysVer", maskId)
                        obj.put("version", maskId)
                        param.result = obj.toString()
                        Logger.i(TAG, "已伪造 sysVer: $sysVer -> $maskId")
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "hookGenerateNewSysParam 失败", e)
        }
    }

    private fun hookDeliverResponse(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.meizu.flyme.update.network.BasicRequest",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "deliverResponse",
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val request = param.thisObject ?: return
                        val url = try {
                            XposedHelpers.callMethod(request, "getUrl") as? String
                        } catch (_: Throwable) { null } ?: return
                        if (!CHECK_URLS.any { url.contains(it) }) return

                        val response = param.args[0] ?: return
                        val code = try {
                            XposedHelpers.callMethod(response, "getCode") as? Int ?: 0
                        } catch (_: Throwable) { 0 }
                        if (code != 200) return

                        val value = try {
                            XposedHelpers.callMethod(response, "getValue")
                        } catch (_: Throwable) { null } ?: return

                        // 首次检查：从新版本信息里抓目标 mask id
                        if (targetMaskId == null) {
                            val newObj = try {
                                XposedHelpers.callMethod(value, "getJSONObject", "new")
                            } catch (_: Throwable) { null }
                            if (newObj != null) {
                                targetMaskId = try {
                                    XposedHelpers.callMethod(newObj, "getString", "latestVersion") as? String
                                } catch (_: Throwable) { null }
                                if (targetMaskId != null) {
                                    Logger.i(TAG, "已捕获目标 mask id: $targetMaskId")
                                }
                            }
                            return
                        }

                        // 伪造响应：基于当前版本构造 fake new
                        val newObj = try {
                            XposedHelpers.callMethod(value, "getJSONObject", "new")
                        } catch (_: Throwable) { null }

                        val hasRealUpdate = newObj != null && try {
                            XposedHelpers.callMethod(newObj, "getBoolean", "existsUpdate") as? Boolean ?: false
                        } catch (_: Throwable) { false }

                        if (!hasRealUpdate) {
                            val curObj = try {
                                XposedHelpers.callMethod(value, "getJSONObject", "cur")
                            } catch (_: Throwable) { null } ?: return

                            val curStr = XposedHelpers.callMethod(curObj, "toString") as? String ?: return
                            val jsonClz = XposedHelpers.findClass(
                                "com.alibaba.fastjson.JSON",
                                lpparam.classLoader
                            )
                            val fakeNew = XposedHelpers.callStaticMethod(jsonClz, "parseObject", curStr)

                            XposedHelpers.callMethod(fakeNew, "put", "existsUpdate", true as Any)
                            XposedHelpers.callMethod(fakeNew, "put", "needUpdate", true as Any)
                            XposedHelpers.callMethod(fakeNew, "put", "packageType", 0 as Any)
                            XposedHelpers.callMethod(value, "put", "new", fakeNew)
                            Logger.i(TAG, "已基于当前创建伪造 new")
                        } else {
                            val pkgType = try {
                                XposedHelpers.callMethod(newObj, "getIntValue", "packageType") as? Int ?: 0
                            } catch (_: Throwable) { 0 }
                            if (pkgType == 1) {
                                XposedHelpers.callMethod(newObj, "put", "packageType", 0 as Any)
                                Logger.i(TAG, "已强制 packageType 1 -> 0")
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "hookDeliverResponse 失败", e)
        }
    }
}
