package com.karen.flymetool.hook.feature.launcher

import android.animation.ObjectAnimator
import android.graphics.Canvas
import android.util.Property
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import java.util.WeakHashMap

object FolderBlurHook : FeatureHook {

    private const val TAG = "FolderBlur"

    private val maxBlurRadiusMap = WeakHashMap<Any, Int>()

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.meizu.flyme.launcher") return

        val noMaskEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, "folder_icon_no_mask")
        val iconEnabled = !noMaskEnabled &&
            XposedPrefs.isFeatureEnabled(lpparam, packageName, "folder_icon_blur")
        val openEnabled = XposedPrefs.isFeatureEnabled(lpparam, packageName, "folder_open_blur")
        if (!noMaskEnabled && !iconEnabled && !openEnabled) return

        val iconRadius = if (iconEnabled) {
            XposedPrefs.getFeatureValue(lpparam, packageName, "folder_icon_blur", 30)
        } else -1
        val openStrength = if (openEnabled) {
            XposedPrefs.getFeatureValue(lpparam, packageName, "folder_open_blur", 80) / 100f
        } else -1f

        if (noMaskEnabled) hookRemoveFolderMask(lpparam)

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(lpparam, iconRadius, openStrength)
            else -> hookLegacy(lpparam, iconRadius, openStrength)
        }
    }

    private fun hookFlyme12(lpparam: XC_LoadPackage.LoadPackageParam, iconRadius: Int, openStrength: Float) {
        if (iconRadius >= 0) hookIconBlurRadius12(lpparam, iconRadius)
        if (openStrength >= 0f) hookOpenBlurStrength12(lpparam, openStrength)
    }

    private fun hookLegacy(lpparam: XC_LoadPackage.LoadPackageParam, iconRadius: Int, openStrength: Float) {
        if (iconRadius >= 0) hookIconBlurRadiusLegacy(lpparam, iconRadius)
        if (openStrength >= 0f) hookOpenBlurStrengthLegacy(lpparam, openStrength)
    }

    // 去掉文件夹图标底色遮罩：跳过纯色绘制，并禁用 FolderIcon 专用 BackgroundBlurUtils(View, boolean)
    private fun hookRemoveFolderMask(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val previewBg = XposedHelpers.findClass(
                "com.android.launcher3.folder.PreviewBackground",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                previewBg,
                "drawBackground",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                }
            )

            val blurUtils = XposedHelpers.findClass(
                "com.meizu.flyme.launcher.utils.BackgroundBlurUtils",
                lpparam.classLoader
            )
            fun disableFolderBlur(instance: Any) {
                try {
                    XposedHelpers.setBooleanField(instance, "mBlurEnable", false)
                    XposedHelpers.callMethod(instance, "removeBlurDrawable")
                } catch (_: Throwable) {
                }
            }
            // FolderIcon 专用构造：new BackgroundBlurUtils(this, isBigFolderIcon())
            XposedHelpers.findAndHookConstructor(
                blurUtils,
                android.view.View::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        disableFolderBlur(param.thisObject)
                    }
                }
            )
            // 主题/图标包切换时可能重新打开模糊（仅 FolderIcon，避免影响小组件等）
            XposedHelpers.findAndHookMethod(
                blurUtils,
                "updateBlurShow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = XposedHelpers.getObjectField(param.thisObject, "mView") ?: return
                        if (!view.javaClass.name.contains("FolderIcon")) return
                        disableFolderBlur(param.thisObject)
                    }
                }
            )
            Logger.i(TAG, "去掉文件夹遮罩已启用")
        } catch (e: Throwable) {
            Logger.e(TAG, "去掉文件夹遮罩Hook失败", e)
        }
    }

    // Flyme 12: setBlurRadius 只在创建时调一次，后续帧直接读 mBlurRadius 字段
    // 必须 afterHook 里把字段也改掉
    private fun hookIconBlurRadius12(lpparam: XC_LoadPackage.LoadPackageParam, radius: Int) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.meizu.flyme.launcher.utils.BackgroundBlurDrawable",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(clazz, "setBlurRadius", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedHelpers.setIntField(param.thisObject, "mBlurRadius", radius)
                    }
                }
            )
            Logger.i(TAG, "文件夹模糊半径(Flyme12): $radius")
        } catch (e: Throwable) {
            Logger.e(TAG, "文件夹模糊半径Hook失败(Flyme12)", e)
        }
    }

    // Flyme 12: 背景模糊强度最终由 BaseDepthController.applyDepthAndBlur 里的 mMaxBlurRadius 决定
    // folderDepth 动画到 0.8 时通常已被 clamp 到最大，直接改动画目标值看不出来
    // 改为在 folderDepth > 0 期间临时缩放 mMaxBlurRadius
    private fun hookOpenBlurStrength12(lpparam: XC_LoadPackage.LoadPackageParam, strength: Float) {
        try {
            val controllerClass = XposedHelpers.findClass(
                "com.android.quickstep.util.BaseDepthController",
                lpparam.classLoader
            )
            val launcherClass = XposedHelpers.findClass(
                "com.android.launcher3.Launcher",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookConstructor(
                controllerClass,
                launcherClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val controller = param.thisObject
                        maxBlurRadiusMap[controller] = XposedHelpers.getIntField(controller, "mMaxBlurRadius")
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                controllerClass,
                "applyDepthAndBlur",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val controller = param.thisObject
                        val folderDepth = XposedHelpers.callMethod(
                            XposedHelpers.getObjectField(controller, "folderDepth"),
                            "getValue"
                        ) as? Float ?: return
                        if (folderDepth <= 0f) return

                        val original = maxBlurRadiusMap[controller] ?: return
                        val scale = strength / 0.8f
                        XposedHelpers.setIntField(controller, "mMaxBlurRadius", (original * scale).toInt())
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val controller = param.thisObject
                        val original = maxBlurRadiusMap[controller] ?: return
                        XposedHelpers.setIntField(controller, "mMaxBlurRadius", original)
                    }
                }
            )
            Logger.i(TAG, "文件夹展开模糊强度(Flyme12): $strength")
        } catch (e: Throwable) {
            Logger.e(TAG, "文件夹展开模糊强度Hook失败(Flyme12)", e)
        }
    }

    // 旧版: beforeHookedMethod 改参数即可
    private fun hookIconBlurRadiusLegacy(lpparam: XC_LoadPackage.LoadPackageParam, radius: Int) {
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
            Logger.i(TAG, "文件夹模糊半径: $radius")
        } catch (e: Throwable) {
            Logger.e(TAG, "文件夹模糊半径Hook失败", e)
        }
    }

    // 旧版: Property 名 "blur"
    private fun hookOpenBlurStrengthLegacy(lpparam: XC_LoadPackage.LoadPackageParam, strength: Float) {
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
