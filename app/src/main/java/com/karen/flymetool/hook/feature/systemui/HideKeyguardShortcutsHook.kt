package com.karen.flymetool.hook.feature.systemui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object HideKeyguardShortcutsHook : FeatureHook {

    private const val KEYGUARD_BOTTOM_AREA_VIEW = "com.flyme.systemui.affordance.MZKeyguardBottomAreaView"
    private const val TAG = "HideKeyguardShortcut"

    private var hideFlashlight: Boolean = false
    private var hideCamera: Boolean = false

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return

        val flashlight = XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_keyguard_flashlight")
        val camera = XposedPrefs.isFeatureEnabled(lpparam, packageName, "hide_keyguard_camera")
        if (!flashlight && !camera) return

        hideFlashlight = flashlight
        hideCamera = camera

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(lpparam)
            FlymeVersionUtils.isFlyme11() -> hookFlyme11(lpparam)
            FlymeVersionUtils.isFlyme10() -> hookFlyme10(lpparam)
            else -> hookFlyme10(lpparam)
        }
        Logger.i(TAG, "已加载, hideFlashlight=$hideFlashlight, hideCamera=$hideCamera")
    }

    private fun hookFlyme12(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(KEYGUARD_BOTTOM_AREA_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "updateLeftRightClickVisibility",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return

                        if (hideFlashlight) {
                            val leftView = XposedHelpers.getObjectField(view, "mLeftClickAffordanceView")
                            (leftView as? View)?.visibility = View.GONE
                        }

                        if (hideCamera) {
                            val rightView = XposedHelpers.getObjectField(view, "mRightClickAffordanceView")
                            (rightView as? View)?.visibility = View.GONE
                        }

                        param.result = null
                    }
                }
            )

            Logger.i(TAG, "已挂载 updateLeftRightClickVisibility（Flyme 12）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Flyme 12 失败", e)
        }
    }

    private fun hookFlyme11(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(KEYGUARD_BOTTOM_AREA_VIEW, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                clazz,
                "updateLeftRightClickVisibility",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return

                        if (hideFlashlight) {
                            val leftView = XposedHelpers.getObjectField(view, "mLeftClickAffordanceView")
                            (leftView as? View)?.visibility = View.GONE
                        }

                        if (hideCamera) {
                            val rightView = XposedHelpers.getObjectField(view, "mRightClickAffordanceView")
                            (rightView as? View)?.visibility = View.GONE
                        }

                        param.result = null
                    }
                }
            )

            Logger.i(TAG, "已挂载 updateLeftRightClickVisibility（Flyme 11）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Flyme 11 失败", e)
        }
    }

    private fun hookFlyme10(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookUpdateLeftClickVisibility(lpparam)
        hookUpdateRightClickVisibility(lpparam)
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

            Logger.i(TAG, "已挂载 updateLeftClickVisibility")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 updateLeftClickVisibility 失败", e)
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

            Logger.i(TAG, "已挂载 updateRightVisibility")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 updateRightVisibility 失败", e)
        }
    }
}
