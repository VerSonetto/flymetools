package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object ConnectionRateLowSpeedHideHook : FeatureHook {

    private const val CONNECTION_RATE_VIEW = "com.flyme.statusbar.connectionRateView.ConnectionRateView"
    private const val TAG = "ConnectionRateLowSpeedHide"

    private var thresholdKbPerS: Int = 10

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "connection_rate_low_speed_hide")) return
        if (lpparam.packageName != "com.android.systemui") return

        val threshold = XposedPrefs.getFeatureValue(lpparam, packageName, "connection_rate_low_speed_hide", 10)
        mount(lpparam, threshold)
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam, threshold: Int) {
        thresholdKbPerS = threshold
        hookOnConnectionRateChange(lpparam)
        Logger.i(TAG, "已加载，阈值=${thresholdKbPerS}KB/s")
    }

    private fun hookOnConnectionRateChange(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CONNECTION_RATE_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "onConnectionRateChange",
                Boolean::class.java,
                Double::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val show = param.args[0] as Boolean
                        val rate = param.args[1] as Double

                        if (!show) return

                        val shouldHide = rate < thresholdKbPerS
                        if (shouldHide) {
                            param.args[0] = false
                            Logger.d(TAG) { "隐藏低速率: rate=${rate.toInt()}KB/s < 阈值=${thresholdKbPerS}KB/s" }
                        }
                    }
                }
            )

            Logger.i(TAG, "已挂载 ConnectionRateView.onConnectionRateChange")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }
}
