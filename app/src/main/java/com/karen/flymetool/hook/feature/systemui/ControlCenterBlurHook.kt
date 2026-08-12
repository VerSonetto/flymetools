package com.karen.flymetool.hook.feature.systemui

import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

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

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        val intensity = XposedPrefs.getFeatureValue(
            lpparam,
            packageName,
            FEATURE_KEY,
            100
        ).coerceIn(0, 100)
        if (intensity == 100) {
            Logger.i(TAG, "模糊强度为 100%，跳过挂载")
            return
        }

        val scale = intensity / 100f
        hookSetBlurRadius(lpparam, CENTER_CONTROLLER_CLASS, scale, "控制中心")
        hookSetBlurRadius(lpparam, NOTIFICATION_PANEL_CLASS, scale, "通知面板")
    }

    /**
     * 按特征定位 setBlurRadius(float)：单 float 入参、void 返回。
     * 控制中心与经典通知下拉共用同一因子链路。
     */
    private fun hookSetBlurRadius(
        lpparam: XC_LoadPackage.LoadPackageParam,
        className: String,
        scale: Float,
        label: String
    ) {
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
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

            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = (param.args[0] as? Number)?.toFloat() ?: return
                    if (original <= 0f) return
                    param.args[0] = original * scale
                }
            })

            Logger.i(TAG, "${label}模糊强度 Hook 完成: ${(scale * 100).toInt()}%")
        } catch (e: Throwable) {
            Logger.e(TAG, "${label}模糊强度 Hook 失败", e, "class" to className)
        }
    }
}
