package com.karen.flymetool.hook.feature.flymeupdate

import com.karen.flymetool.hook.base.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object DisableUpdateCheckHook {

    private const val TAG = "DisableUpdateCheck"
    private const val TARGET_PACKAGE = "com.meizu.flyme.update"

    private val BLOCKED_URL_PATTERNS = listOf(
        "sysupgrade",
        "sysupgradeex"
    )

    private const val FIRMWARE_CACHE_KEY = "key_check_new_upgrade_firmware_cache"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        hookBasicRequestDeliverResponse(lpparam)
        hookFirmwareCache(lpparam)
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

                        Logger.i(TAG, "Hijacking update check response: $url")

                        val response = param.args[0] ?: return

                        try {
                            val code = XposedHelpers.callMethod(response, "getCode") as? Int
                            if (code == 200) {
                                XposedHelpers.callMethod(response, "setValue", null as Any?)
                                Logger.i(TAG, "Cleared response value -> no update")
                            }
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Failed to hijack response value", e)
                        }
                    }
                }
            )

            Logger.i(TAG, "BasicRequest.deliverResponse hook installed")
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to hook BasicRequest.deliverResponse", e)
        }
    }

    private fun isUpdateCheckUrl(url: String): Boolean {
        return BLOCKED_URL_PATTERNS.any { url.contains(it, ignoreCase = true) }
    }

    private fun hookFirmwareCache(lpparam: XC_LoadPackage.LoadPackageParam) {
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
                            Logger.d(TAG, "Cleared firmware upgrade cache")
                        }
                    }
                }
            )

            Logger.i(TAG, "Firmware cache hook installed")
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to hook firmware cache", e)
        }
    }
}
