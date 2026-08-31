package com.karen.flymetool.hook.feature.launcher

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect

object TaskCardHook : FeatureHook {

    private const val TAG = "TaskCard"
    private const val TASK_CORNER_RADIUS_CLASS = "com.android.quickstep.util.TaskCornerRadius"
    private const val BASE_DEPTH_CONTROLLER_CLASS = "com.android.quickstep.util.BaseDepthController"

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.meizu.flyme.launcher") return

        val radiusEnabled = ctx.featureEnabled("task_card_radius")
        val blurEnabled = ctx.featureEnabled("task_blur_intensity")
        if (!radiusEnabled && !blurEnabled) return

        val radiusDp = if (radiusEnabled) {
            ctx.featureValue("task_card_radius", 24)
        } else -1
        val blurIntensity = if (blurEnabled) {
            ctx.featureValue("task_blur_intensity", 50)
        } else -1

        mount(ctx, radiusDp, blurIntensity)
    }

    private fun mount(ctx: HookContext, radiusDp: Int, blurIntensity: Int) {
        hookTaskCornerRadius(ctx, radiusDp)
        if (blurIntensity >= 0) {
            hookBlurIntensity(ctx, blurIntensity)
        }
    }

    private fun hookTaskCornerRadius(ctx: HookContext, radiusDp: Int) {
        if (radiusDp < 0) return
        try {
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                radiusDp.toFloat(),
                Resources.getSystem().displayMetrics
            )

            val clazz = Reflect.findClass(TASK_CORNER_RADIUS_CLASS, ctx.classLoader)

            for (method in clazz.declaredMethods) {
                if (method.returnType == Float::class.javaPrimitiveType
                    && method.parameterTypes.size == 1
                    && method.parameterTypes[0] == Context::class.java) {

                    Reflect.hookMethod(ctx.api, method) { chain ->
                        chain.proceed()
                        radiusPx
                    }
                    Logger.i(TAG, "任务卡片圆角 Hook 完成: ${method.name} -> ${radiusPx}px")
                    return
                }
            }

            Logger.w(TAG, "未找到任务卡片圆角方法")
        } catch (e: Throwable) {
            Logger.e(TAG, "任务卡片圆角 Hook 失败", e)
        }
    }

    private fun hookBlurIntensity(ctx: HookContext, intensity: Int) {
        try {
            val bdcClass = Reflect.findClass(BASE_DEPTH_CONTROLLER_CLASS, ctx.classLoader)
            val transClass = Reflect.findClass("android.view.SurfaceControl\$Transaction", ctx.classLoader)
            val scClass = Reflect.findClass("android.view.SurfaceControl", ctx.classLoader)

            // 从 BaseDepthController 实例读取原始 mMaxBlurRadius 作为缩放基准
            var maxBlur = 180
            Reflect.hookAllConstructors(ctx.api, bdcClass) { chain ->
                val result = chain.proceed()
                maxBlur = Reflect.getIntField(chain.getThisObject(), "mMaxBlurRadius")
                result
            }

            Reflect.hookMethodOn(ctx.api, transClass, "setBackgroundBlurRadius",
                scClass, Int::class.javaPrimitiveType,
            ) { chain ->
                val radius = chain.getArg(1) as? Int ?: return@hookMethodOn chain.proceed()
                if (radius <= 0) return@hookMethodOn chain.proceed()
                val scale = intensity.toFloat() / maxBlur.toFloat()
                val newRadius = (radius * scale).toInt().coerceAtLeast(0)
                chain.proceed(arrayOf(chain.getArg(0), newRadius))
            }
            Logger.i(TAG, "背景模糊强度 Hook 完成: $intensity (基准=$maxBlur)")
        } catch (e: Throwable) {
            Logger.e(TAG, "背景模糊强度 Hook 失败", e)
        }
    }
}