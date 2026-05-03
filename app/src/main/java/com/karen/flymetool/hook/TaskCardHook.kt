package com.karen.flymetool.hook

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object TaskCardHook {

    private const val TAG = "TaskCard"
    private const val TASK_CORNER_RADIUS_CLASS = "com.android.quickstep.util.TaskCornerRadius"
    private const val BASE_DEPTH_CONTROLLER_CLASS = "com.android.quickstep.util.BaseDepthController"

    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, radiusDp: Int, blurIntensity: Int) {
        if (lpparam.packageName != "com.meizu.flyme.launcher") return

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
            val clazz = XposedHelpers.findClass(BASE_DEPTH_CONTROLLER_CLASS, lpparam.classLoader)

            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    XposedHelpers.setIntField(param.thisObject, "mMaxBlurRadius", intensity)
                    Logger.i(TAG, "背景模糊强度Hook完成: mMaxBlurRadius -> $intensity")
                }
            })
        } catch (e: Throwable) {
            Logger.e(TAG, "背景模糊强度Hook失败", e)
        }
    }
}
