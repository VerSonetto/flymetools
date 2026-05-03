package com.karen.flymetool.hook.feature.systemui

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger

object HideChargingAnimationHook {

    private const val CHARGE_ANIMATION_CONTROLLER = "com.flyme.keyguard.charging.ChargeAnimationController"
    private const val HOOK_NAME = "HideChargingAnim"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, enabled: Boolean) {
        if (!enabled) return
        if (lpparam.packageName != "com.android.systemui") return

        hookStartWireAnimation(lpparam)
        Logger.i(HOOK_NAME, "Loaded")
    }

    private fun hookStartWireAnimation(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(CHARGE_ANIMATION_CONTROLLER, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "startWireAnimation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        Logger.d(HOOK_NAME, "Blocking charging animation")
                        
                        val thisObject = param.thisObject
                        
                        XposedHelpers.setBooleanField(thisObject, "mAnimationStarted", true)
                        
                        val mHandler = XposedHelpers.getObjectField(thisObject, "mHandler") as? android.os.Handler
                        val mRemoveWindow = XposedHelpers.getObjectField(thisObject, "mRemoveWindow") as Runnable?
                        
                        mRemoveWindow?.let { mHandler?.postDelayed(it, 100) }
                        
                        param.result = null
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked ChargeAnimationController.startWireAnimation")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
        }
    }
}
