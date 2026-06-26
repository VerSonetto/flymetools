package com.karen.flymetool.hook.feature.launcher

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs

object TaskCardHook : FeatureHook {

    private const val TAG = "TaskCard"
    private const val TASK_CORNER_RADIUS_CLASS = "com.android.quickstep.util.TaskCornerRadius"
    private const val BASE_DEPTH_CONTROLLER_CLASS = "com.android.quickstep.util.BaseDepthController"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.meizu.flyme.launcher") return

        val radiusEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, "task_card_radius")
        val blurEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, "task_blur_intensity")
        if (!radiusEnabled && !blurEnabled) return

        val radiusDp = if (radiusEnabled) {
            XposedPrefs.getFeatureValue(lpparam, packageName, "task_card_radius", 24)
        } else -1
        val blurIntensity = if (blurEnabled) {
            XposedPrefs.getFeatureValue(lpparam, packageName, "task_blur_intensity", 50)
        } else -1

        mount(lpparam, radiusDp, blurIntensity)
    }

    private fun mount(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int, blurIntensity: Int) {
        hookTaskCornerRadius(lpparam, radiusDp)
        if (blurIntensity >= 0) {
            hookBlurIntensity(lpparam, blurIntensity)
        }
    }

    private fun hookTaskCornerRadius(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int) {
        if (radiusDp < 0) return
        try {
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp.toFloat(),
                Resources.getSystem().displayMetrics
            )

            val clazz = XposedHelpers.findClass(TASK_CORNER_RADIUS_CLASS, lpparam.classLoader)

            for (method in clazz.declaredMethods) {
                if (method.returnType == Float::class.javaPrimitiveType
                    && method.parameterTypes.size == 1
                    && method.parameterTypes[0] == Context::class.java) {

                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = radiusPx
                        }
                    })
                    Logger.i(TAG, "任务卡片圆角Hook完成: ${method.name} -> ${radiusPx}px")
                    return
                }
            }

            Logger.w(TAG, "未找到任务卡片圆角方法")
        } catch (e: Throwable) {
            Logger.e(TAG, "任务卡片圆角Hook失败", e)
        }
    }

    private fun hookBlurIntensity(lpparam: XC_LoadPackage.LoadPackageParam, intensity: Int) {
        try {
            val bdcClass = XposedHelpers.findClass(BASE_DEPTH_CONTROLLER_CLASS, lpparam.classLoader)
            val transClass = XposedHelpers.findClass("android.view.SurfaceControl\$Transaction", lpparam.classLoader)
            val scClass = XposedHelpers.findClass("android.view.SurfaceControl", lpparam.classLoader)

            // 从 BaseDepthController 实例读取原始 mMaxBlurRadius 作为缩放基准
            var maxBlur = 180
            XposedBridge.hookAllConstructors(bdcClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    maxBlur = XposedHelpers.getIntField(param.thisObject, "mMaxBlurRadius")
                }
            })

            XposedHelpers.findAndHookMethod(transClass, "setBackgroundBlurRadius",
                scClass, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val radius = param.args[1] as? Int ?: return
                        if (radius <= 0) return
                        val scale = intensity.toFloat() / maxBlur.toFloat()
                        param.args[1] = (radius * scale).toInt().coerceAtLeast(0)
                    }
                })
            Logger.i(TAG, "背景模糊强度Hook完成: $intensity (基准=$maxBlur)")
        } catch (e: Throwable) {
            Logger.e(TAG, "背景模糊强度Hook失败", e)
        }
    }
}
