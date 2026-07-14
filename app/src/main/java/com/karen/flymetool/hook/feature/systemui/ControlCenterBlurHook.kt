package com.karen.flymetool.hook.feature.systemui

import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Method

object ControlCenterBlurHook : FeatureHook {

    private const val FEATURE_KEY = "control_center_blur_intensity"
    private const val HOOK_NAME = "ControlCenterBlur"
    private const val FLYME_BLUR_UTILS_CLASS = "com.flyme.systemui.utils.MzBlurUtils"
    private const val SHADE_DEPTH_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.NotificationShadeDepthController"
    private const val ROOT_CONTAINER_ID_NAME = "mz_root_container"
    private const val KEYGUARD_BACKGROUND_FRAME_ID_NAME = "keyguard_background_frame"

    private var cachedShadeRoot = WeakReference<View>(null)
    private var cachedKeyguardBackgroundFrame = WeakReference<View>(null)

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
        hookLockscreenContentBlur(lpparam, intensity)
        hookUnlockedWindowBlur(lpparam, intensity)
    }

    /** 锁屏壁纸位于 SystemUI 窗口内部，控制中心通过根容器 RenderEffect 对其模糊。 */
    private fun hookLockscreenContentBlur(
        lpparam: XC_LoadPackage.LoadPackageParam,
        intensity: Int
    ) {
        try {
            val blurUtilsClass = XposedHelpers.findClass(
                FLYME_BLUR_UTILS_CLASS,
                lpparam.classLoader
            )
            val targetMethod = blurUtilsClass.declaredMethods.singleOrNull { method ->
                !method.isSynthetic &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            View::class.java,
                            Boolean::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType
                        )
                    )
            } ?: throw NoSuchMethodException(
                "未找到唯一的 (View, Boolean, Float) 模糊方法"
            )

            XposedBridge.hookMethod(targetMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args[0] as? View ?: return
                    if (!isControlCenterRoot(view)) return

                    val shouldApply = param.args[1] as? Boolean ?: return
                    if (!shouldApply || intensity == 100) return

                    if (intensity == 0) {
                        param.args[1] = false
                        param.args[2] = 0f
                    } else {
                        val originalRadius = (param.args[2] as? Number)?.toFloat() ?: return
                        param.args[2] = originalRadius * intensity / 100f
                    }
                }
            })

            Logger.i(HOOK_NAME, "锁屏控制中心内容模糊 Hook 完成: $intensity%")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "锁屏控制中心内容模糊 Hook 失败", e)
        }
    }

    /** 非锁屏场景使用窗口级背景模糊，缩放深度控制器最终计算出的半径。 */
    private fun hookUnlockedWindowBlur(
        lpparam: XC_LoadPackage.LoadPackageParam,
        intensity: Int
    ) {
        try {
            val depthControllerClass = XposedHelpers.findClass(
                SHADE_DEPTH_CONTROLLER_CLASS,
                lpparam.classLoader
            )
            val rootAccessor = depthControllerClass.declaredMethods.singleOrNull { method ->
                !method.isSynthetic &&
                    method.parameterTypes.isEmpty() &&
                    View::class.java.isAssignableFrom(method.returnType)
            } ?: throw NoSuchMethodException("未找到唯一的无参数 View 根容器访问方法")
            val blurResultMethod = depthControllerClass.declaredMethods.singleOrNull { method ->
                !method.isSynthetic &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType.name == "kotlin.Pair"
            } ?: throw NoSuchMethodException("未找到唯一的无参数 Pair 模糊计算方法")

            rootAccessor.isAccessible = true
            XposedBridge.hookMethod(blurResultMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (intensity == 100) return
                    if (isKeyguardBackgroundVisible(param.thisObject, rootAccessor)) return

                    val result = param.result ?: return
                    val radius = (XposedHelpers.callMethod(result, "getFirst") as? Number)
                        ?.toInt() ?: return
                    val zoomOut = XposedHelpers.callMethod(result, "getSecond") ?: return
                    val scaledRadius = (radius * intensity / 100f).toInt()
                    param.result = XposedHelpers.newInstance(
                        blurResultMethod.returnType,
                        scaledRadius,
                        zoomOut
                    )
                }
            })

            Logger.i(HOOK_NAME, "非锁屏控制中心窗口模糊 Hook 完成: $intensity%")
        } catch (e: Throwable) {
            Logger.e(HOOK_NAME, "非锁屏控制中心窗口模糊 Hook 失败", e)
        }
    }

    private fun isControlCenterRoot(view: View): Boolean {
        if (view.id == View.NO_ID) return false
        return try {
            view.resources.getResourceEntryName(view.id) == ROOT_CONTAINER_ID_NAME
        } catch (_: Throwable) {
            false
        }
    }

    private fun isKeyguardBackgroundVisible(
        depthController: Any,
        rootAccessor: Method
    ): Boolean {
        return try {
            val root = rootAccessor.invoke(depthController) as? View ?: return false
            var keyguardBackgroundFrame = if (cachedShadeRoot.get() === root) {
                cachedKeyguardBackgroundFrame.get()
            } else {
                null
            }
            if (keyguardBackgroundFrame == null) {
                val frameId = root.resources.getIdentifier(
                    KEYGUARD_BACKGROUND_FRAME_ID_NAME,
                    "id",
                    root.context.packageName
                )
                if (frameId == 0) return false
                keyguardBackgroundFrame = root.findViewById(frameId)
                cachedShadeRoot = WeakReference(root)
                cachedKeyguardBackgroundFrame = WeakReference(keyguardBackgroundFrame)
            }
            keyguardBackgroundFrame.visibility == View.VISIBLE
        } catch (_: Throwable) {
            false
        }
    }
}
