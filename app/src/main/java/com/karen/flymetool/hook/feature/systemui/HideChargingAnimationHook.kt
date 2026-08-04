package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object HideChargingAnimationHook : FeatureHook {

    private const val CHARGE_ANIMATION_CONTROLLER = "com.flyme.keyguard.charging.ChargeAnimationController"
    private const val TAG = "HideChargingAnimation"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_charging_animation")) return
        if (lpparam.packageName != "com.android.systemui") return

        hookStartWireAnimation(lpparam)
        Logger.i(TAG, "已加载")
    }

    private fun hookStartWireAnimation(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CHARGE_ANIMATION_CONTROLLER, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "startWireAnimation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logger.d(TAG) { "拦截充电动画" }

                        val thisObject = param.thisObject

                        XposedHelpers.setBooleanField(thisObject, "mAnimationStarted", true)

                        val mHandler = XposedHelpers.getObjectField(thisObject, "mHandler") as? android.os.Handler
                        val mRemoveWindow = XposedHelpers.getObjectField(thisObject, "mRemoveWindow") as Runnable?

                        mRemoveWindow?.let { mHandler?.postDelayed(it, 100) }

                        param.result = null
                    }
                }
            )

            Logger.i(TAG, "已挂载 ChargeAnimationController.startWireAnimation")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载失败", e)
        }
    }
}
