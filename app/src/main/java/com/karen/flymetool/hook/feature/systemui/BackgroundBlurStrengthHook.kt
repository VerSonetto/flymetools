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

        when {
            FlymeVersionUtils.isFlyme12() -> hookApplyBlurMZ(lpparam, factor)
            FlymeVersionUtils.isFlyme10() -> {
                hookBlurUtilsRatio(lpparam, factor)
                hookCenterControllerBlur(lpparam, factor)
            }
            else -> {
                hookBlurUtilsRatio(lpparam, factor)
                hookMzBlurEffect(lpparam, factor)
            }
        }
    }

    /**
     * Flyme 12: hook applyBlurMZ，在最终应用模糊半径到 SurfaceControl 时缩放。
     *
     * blurRadiusOfRatio 的返回值被广泛用于中间计算（shadeDepthRatio、isExpanded 判断等），
     * hook 它会导致窗口状态计算错误引发状态栏重影。
     * applyBlurMZ 是最终调用 withBackgroundBlur 的地方，只在这里缩放 radius，
     * 所有中间计算保持原始值。
     */
    private fun hookApplyBlurMZ(lpparam: XC_LoadPackage.LoadPackageParam, factor: Float) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.systemui.statusbar.BlurUtils",
                lpparam.classLoader
            )
            val viewRootImplClass = XposedHelpers.findClass(
                "android.view.ViewRootImpl", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz, "applyBlurMZ",
                viewRootImplClass, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val radius = param.args[1] as? Int ?: return
                        if (radius <= 0) return
                        param.args[1] = (radius * factor).toInt().coerceAtLeast(0)
                    }
                }
            )
            Logger.i(TAG, "applyBlurMZ radius scaled by $factor")
        } catch (e: Throwable) {
            Logger.e(TAG, "applyBlurMZ hook failed", e)
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
