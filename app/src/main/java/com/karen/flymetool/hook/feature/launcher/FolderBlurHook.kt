package com.karen.flymetool.hook.feature.launcher

import android.animation.ObjectAnimator
import android.util.Property
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.Logger

object FolderBlurHook {

    private const val TAG = "FolderBlur"

    private var iconBlurRadius = 30
    private var openBlurStrength = 0.8f

    fun handleLoadPackage(
        lpparam: XC_LoadPackage.LoadPackageParam,
        iconRadius: Int,
        openStrength: Float
    ) {
        iconBlurRadius = iconRadius
        openBlurStrength = openStrength

        if (iconRadius >= 0) {
            hookIconBlurRadius(lpparam, iconRadius)
        }
        if (openStrength >= 0f) {
            hookOpenBlurStrength(lpparam, openStrength)
        }
    }

    private fun hookIconBlurRadius(lpparam: XC_LoadPackage.LoadPackageParam, radius: Int) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.meizu.flyme.launcher.utils.BackgroundBlurDrawable",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(clazz, "setBlurRadius", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = radius
                    }
                }
            )
            Logger.i(TAG, "图标毛玻璃半径: $radius")
        } catch (e: Throwable) {
            Logger.e(TAG, "图标毛玻璃半径Hook失败", e)
        }
    }

    private fun hookOpenBlurStrength(lpparam: XC_LoadPackage.LoadPackageParam, strength: Float) {
        try {
            XposedHelpers.findAndHookMethod(
                ObjectAnimator::class.java, "ofFloat",
                Any::class.java, Property::class.java, FloatArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val target = param.args[0] ?: return
                        if (target.javaClass.name != "com.android.launcher3.statehandlers.DepthController") return

                        val prop = param.args[1] as? Property<*, *> ?: return
                        if (prop.name != "blur") return

                        val values = param.args[2] as FloatArray
                        if (values.size == 1 && values[0] in 0.1f..1.0f) {
                            values[0] = strength
                        }
                    }
                }
            )
            Logger.i(TAG, "文件夹展开模糊强度: $strength")
        } catch (e: Throwable) {
            Logger.e(TAG, "文件夹展开模糊强度Hook失败", e)
        }
    }
}
