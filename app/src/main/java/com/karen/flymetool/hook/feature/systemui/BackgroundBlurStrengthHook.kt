package com.karen.flymetool.hook.feature.systemui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object BackgroundBlurStrengthHook : FeatureHook {

    private const val TAG = "BgBlurStrength"
    private const val KEY = "background_blur_strength"
    private const val DEFAULT_STRENGTH = 50

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, KEY)) return

        val strength = XposedPrefs.getFeatureValue(lpparam, packageName, KEY, DEFAULT_STRENGTH)
        val factor = strength.coerceIn(0, 100) / 100f

        hookBlurUtilsRatio(lpparam, factor)

        when {
            FlymeVersionUtils.isFlyme12() -> hookMzBlurEffect(lpparam, factor)
            FlymeVersionUtils.isFlyme10() -> hookCenterControllerBlur(lpparam, factor)
            else -> hookMzBlurEffect(lpparam, factor)
        }
    }

    private fun hookBlurUtilsRatio(lpparam: XC_LoadPackage.LoadPackageParam, factor: Float) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.systemui.statusbar.BlurUtils",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(clazz, "blurRadiusOfRatio", Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? Float ?: return
                        if (result == 0f) return
                        param.result = result * factor
                    }
                }
            )
            Logger.i(TAG, "BlurUtils.blurRadiusOfRatio scaled by $factor")
        } catch (e: Throwable) {
            Logger.e(TAG, "BlurUtils hook failed", e)
        }
    }

    private fun hookCenterControllerBlur(lpparam: XC_LoadPackage.LoadPackageParam, factor: Float) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.flyme.systemui.controlcenter.phone.CenterController",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(clazz, "setNotificationPanelRenderEffect",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = XposedHelpers.getStaticIntField(clazz, "BLUR_RADIUS_MAX")
                        val scaled = (original * factor).toInt().coerceAtLeast(0)
                        XposedHelpers.setStaticIntField(clazz, "BLUR_RADIUS_MAX", scaled)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val original = XposedHelpers.getStaticIntField(clazz, "BLUR_RADIUS_MAX")
                        val restored = (original / factor).toInt()
                        XposedHelpers.setStaticIntField(clazz, "BLUR_RADIUS_MAX", restored)
                    }
                }
            )
            Logger.i(TAG, "CenterController blur scaled by $factor")
        } catch (e: Throwable) {
            Logger.e(TAG, "CenterController hook failed", e)
        }
    }

    private fun hookMzBlurEffect(lpparam: XC_LoadPackage.LoadPackageParam, factor: Float) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.flyme.systemui.utils.MzBlurUtils",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(clazz, "setFlymeBlurEffect",
                View::class.java, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val radius = param.args[2] as? Float ?: return
                        if (radius <= 0f) return
                        param.args[2] = radius * factor
                    }
                }
            )
            Logger.i(TAG, "MzBlurUtils effect scaled by $factor")
        } catch (e: Throwable) {
            Logger.e(TAG, "MzBlurUtils hook failed", e)
        }
    }
}
