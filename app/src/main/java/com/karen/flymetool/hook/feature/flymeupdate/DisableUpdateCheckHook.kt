package com.karen.flymetool.hook.feature.flymeupdate

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object DisableUpdateCheckHook : FeatureHook {

    private const val TAG = "DisableUpdateCheck"
    private const val TARGET_PACKAGE = "com.meizu.flyme.update"

    private val BLOCKED_URL_PATTERNS = listOf(
        "sysupgrade",
        "sysupgradeex",
        "v4/firmware"
    )

    private const val FIRMWARE_CACHE_KEY = "key_check_new_upgrade_firmware_cache"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "disable_update_check")) return
        if (lpparam.packageName != TARGET_PACKAGE) return

        hookBasicRequestDeliverResponse(lpparam)
        hookFirmwareCache()
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
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val request = param.thisObject ?: return

                        val url = try {
                            XposedHelpers.callMethod(request, "getUrl") as? String
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        if (!isUpdateCheckUrl(url)) return

                        Logger.i(TAG, "劫持更新检查响应: $url")

                        val response = param.args[0] ?: return

                        try {
                            val code = XposedHelpers.callMethod(response, "getCode") as? Int
                            if (code == 200) {
                                XposedHelpers.callMethod(response, "setValue", null as Any?)
                                Logger.i(TAG, "已清空响应值 -> 无更新")
                            }
                        } catch (e: Throwable) {
                            Logger.e(TAG, "劫持响应值失败", e)
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载 BasicRequest.deliverResponse")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 BasicRequest.deliverResponse 失败", e)
        }
    }

    private fun isUpdateCheckUrl(url: String): Boolean {
        return BLOCKED_URL_PATTERNS.any { url.contains(it, ignoreCase = true) }
    }

    private fun hookFirmwareCache() {
        try {
            val spImplClass = Class.forName("android.app.SharedPreferencesImpl")

            XposedHelpers.findAndHookMethod(
                spImplClass,
                "getString",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        if (key == FIRMWARE_CACHE_KEY) {
                            param.result = ""
                            Logger.d(TAG) { "已清除固件升级缓存" }
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载固件缓存")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载固件缓存失败", e)
        }
    }
}
