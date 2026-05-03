package com.karen.flymetool.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object PulldownAreaRatioHook {

    private const val HOOK_NAME = "PulldownAreaRatio"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, controlCenterRatio: Int) {
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val centerControllerClass = XposedHelpers.findClass(
                "com.flyme.systemui.controlcenter.phone.CenterController",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                centerControllerClass,
                "updateResources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thisObject = param.thisObject
                        val context = XposedHelpers.getObjectField(thisObject, "mContext") as android.content.Context
                        val dm = context.resources.displayMetrics
                        val widthPixels = dm.widthPixels

                        val newRegion = when (controlCenterRatio) {
                            25 -> widthPixels * 3 / 4
                            50 -> widthPixels / 2
                            75 -> widthPixels / 4
                            else -> widthPixels * 3 / 4
                        }

                        XposedHelpers.setObjectField(thisObject, "mHandleEventRegion", newRegion)
                        Logger.once(HOOK_NAME, "mHandleEventRegion = $newRegion (ratio=$controlCenterRatio%)")
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked CenterController.updateResources")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook failed", e)
        }
    }
}
