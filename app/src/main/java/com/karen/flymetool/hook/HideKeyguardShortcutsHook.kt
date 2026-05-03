package com.karen.flymetool.hook

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object HideKeyguardShortcutsHook {

    private const val KEYGUARD_BOTTOM_AREA_VIEW = "com.flyme.systemui.affordance.MZKeyguardBottomAreaView"
    private const val HOOK_NAME = "HideKeyguardShortcut"

    private var hideFlashlight: Boolean = false
    private var hideCamera: Boolean = false

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, hideFlashlight: Boolean, hideCamera: Boolean) {
        if (lpparam.packageName != "com.android.systemui") return
        this.hideFlashlight = hideFlashlight
        this.hideCamera = hideCamera

        hookUpdateLeftClickVisibility(lpparam)
        hookUpdateRightClickVisibility(lpparam)
        Logger.i(HOOK_NAME, "Loaded, hideFlashlight=$hideFlashlight, hideCamera=$hideCamera")
    }

    private fun hookUpdateLeftClickVisibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!hideFlashlight) return
        try {
            val clazz = XposedHelpers.findClass(KEYGUARD_BOTTOM_AREA_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "updateLeftClickVisibility",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val leftClickAffordanceView = XposedHelpers.getObjectField(param.thisObject, "mLeftClickAffordanceView")
                        if (leftClickAffordanceView != null) {
                            val view = leftClickAffordanceView as? View
                            view?.visibility = View.GONE
                            param.result = null
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked updateLeftClickVisibility")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook updateLeftClickVisibility failed", e)
        }
    }

    private fun hookUpdateRightClickVisibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!hideCamera) return
        try {
            val clazz = XposedHelpers.findClass(KEYGUARD_BOTTOM_AREA_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "updateRightVisibility",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rightClickAffordanceView = XposedHelpers.getObjectField(param.thisObject, "mRightClickAffordanceView")
                        if (rightClickAffordanceView != null) {
                            val view = rightClickAffordanceView as? View
                            view?.visibility = View.GONE
                            param.result = null
                        }
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked updateRightVisibility")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook updateRightVisibility failed", e)
        }
    }
}
