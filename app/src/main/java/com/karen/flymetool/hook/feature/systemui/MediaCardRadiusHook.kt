package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils

object MediaCardRadiusHook : FeatureHook {

    private const val HOOK_NAME = "MediaCardRadius"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, "media_card_radius")) return
        if (lpparam.packageName != "com.android.systemui") return

        val radiusDp = XposedPrefs.getFeatureValue(lpparam, packageName, "media_card_radius", 14)

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(lpparam, radiusDp)
            FlymeVersionUtils.isFlyme11() -> hookFlyme11(lpparam, radiusDp)
            FlymeVersionUtils.isFlyme10() -> hookFlyme10(lpparam, radiusDp)
        }
    }

    private fun hookFlyme10(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int) {
        hookLayout(lpparam, radiusDp, "com.flyme.systemui.media.MediaCarouseTransitionLayout")
    }

    private fun hookFlyme11(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int) {
        hookLayout(lpparam, radiusDp, "com.flyme.systemui.media.MediaCarouseTransitionLayout")
    }

    private fun hookFlyme12(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int) {
        hookLayout(lpparam, radiusDp, "com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout")
    }

    private fun hookLayout(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int, classPath: String) {
        try {
            val clazz = XposedHelpers.findClass(classPath, lpparam.classLoader)
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp.toFloat(),
                Resources.getSystem().displayMetrics
            )

            XposedHelpers.findAndHookConstructor(
                clazz,
                android.content.Context::class.java,
                android.util.AttributeSet::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        applyRadius(view, radiusPx)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                clazz,
                "onConfigurationChanged",
                android.content.res.Configuration::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        applyRadius(view, radiusPx)
                    }
                }
            )

            Logger.i(HOOK_NAME, "Hooked $classPath with radius=${radiusDp}dp")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "Hook $classPath failed", e)
        }
    }

    private fun applyRadius(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }
}
