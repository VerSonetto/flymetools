package com.karen.flymetool.hook.feature.systemui

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

/**
 * 控制中心背景模糊强度（Flyme 12）。
 *
 * 反编译链路：
 * - [CenterController] / [NotificationPanelViewController].setBlurRadius(r)
 *   → blurRadiusFactor = r / max，并带动 blurScaleFactor 弹簧到同一比例
 * - blurFraction = Pair(blurRadiusFactor, blurScaleFactor)
 * - 非锁屏窗口模糊：NotificationShadeDepthController → BlurUtils.applyBlurMZ(
 *     radius, blurScaleFactor)，其中 scale 会换算成 background blur inset
 * - 锁屏内容模糊：ControlCenterBlurInteractor.createBlurState(factor)
 *   → MzRootContainerBlurBinder → MzBlurUtils.setFlymeBlurEffect(root, r)
 * - 遮罩 dim：showBackgroundBlurDimLayer(blurFraction.first)
 *
 * 旧实现只缩放 computeBlurAndZoomOut 的 radius，未同步 blurScaleFactor，
 * 低强度时 inset 仍按满强度缩放背景，与真实壁纸错位，状态栏/底部按钮出现重影。
 * 改为在 setBlurRadius 源头按比例缩放，半径、背景缩放、dim、锁屏 RenderEffect 一并生效。
 */
object ControlCenterBlurHook : FeatureHook {

    private const val FEATURE_KEY = "control_center_blur_intensity"
    private const val TAG = "ControlCenterBlur"

    private const val CENTER_CONTROLLER_CLASS =
        "com.flyme.systemui.controlcenter.phone.CenterController"
    private const val NOTIFICATION_PANEL_CLASS =
        "com.android.systemui.shade.NotificationPanelViewController"

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!ctx.featureEnabled(FEATURE_KEY)) return

        val intensity = ctx.featureValue(
            FEATURE_KEY,
            100
        ).coerceIn(0, 100)
        if (intensity == 100) {
            Logger.i(TAG, "模糊强度为 100%，跳过挂载")
            return
        }

        val scale = intensity / 100f
        hookSetBlurRadius(ctx, CENTER_CONTROLLER_CLASS, scale, "控制中心")
        hookSetBlurRadius(ctx, NOTIFICATION_PANEL_CLASS, scale, "通知面板")
    }

    /**
     * 按特征定位 setBlurRadius(float)：单 float 入参、void 返回。
     * 控制中心与经典通知下拉共用同一因子链路。
     */
    private fun hookSetBlurRadius(
        ctx: HookContext,
        className: String,
        scale: Float,
        label: String
    ) {
        try {
            val clazz = Reflect.findClass(className, ctx.classLoader)
            val target = clazz.declaredMethods.singleOrNull { method ->
                !method.isSynthetic &&
                    method.name == "setBlurRadius" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Float::class.javaPrimitiveType)
                    )
            } ?: throw NoSuchMethodException(
                "未找到唯一的 setBlurRadius(float): $className"
            )

            Reflect.hookMethod(ctx.api, target) { chain ->
                val array = chain.getArgs().toMutableList()
                val original = (array[0] as? Number)?.toFloat() ?: return@hookMethod chain.proceed()
                if (original <= 0f) return@hookMethod chain.proceed()
                array[0] = original * scale
                chain.proceed(array.toTypedArray())
            }

            Logger.i(TAG, "${label}模糊强度 Hook 完成: ${(scale * 100).toInt()}%")
        } catch (e: Throwable) {
            Logger.e(TAG, "${label}模糊强度 Hook 失败", e, "class" to className)
        }
    }
}