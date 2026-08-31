package com.karen.flymetool.hook.feature.launcher

import android.animation.ObjectAnimator
import android.graphics.Canvas
import android.util.Property
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils
import java.util.WeakHashMap

object FolderBlurHook : FeatureHook {

    private const val TAG = "FolderBlur"

    private val maxBlurRadiusMap = WeakHashMap<Any, Int>()

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.meizu.flyme.launcher") return

        val noMaskEnabled = ctx.featureEnabled("folder_icon_no_mask")
        val iconEnabled = !noMaskEnabled &&
            ctx.featureEnabled("folder_icon_blur")
        val openEnabled = ctx.featureEnabled("folder_open_blur")
        if (!noMaskEnabled && !iconEnabled && !openEnabled) return

        val iconRadius = if (iconEnabled) {
            ctx.featureValue("folder_icon_blur", 30)
        } else -1
        val openStrength = if (openEnabled) {
            ctx.featureValue("folder_open_blur", 80) / 100f
        } else -1f

        if (noMaskEnabled) hookRemoveFolderMask(ctx)

        when {
            FlymeVersionUtils.isFlyme12() -> hookFlyme12(ctx, iconRadius, openStrength)
            else -> hookLegacy(ctx, iconRadius, openStrength)
        }
    }

    private fun hookFlyme12(ctx: HookContext, iconRadius: Int, openStrength: Float) {
        if (iconRadius >= 0) hookIconBlurRadius12(ctx, iconRadius)
        if (openStrength >= 0f) hookOpenBlurStrength12(ctx, openStrength)
    }

    private fun hookLegacy(ctx: HookContext, iconRadius: Int, openStrength: Float) {
        if (iconRadius >= 0) hookIconBlurRadiusLegacy(ctx, iconRadius)
        if (openStrength >= 0f) hookOpenBlurStrengthLegacy(ctx, openStrength)
    }

    // 去掉文件夹图标底色遮罩：跳过纯色绘制，并禁用 FolderIcon 专用 BackgroundBlurUtils(View, boolean)
    private fun hookRemoveFolderMask(ctx: HookContext) {
        try {
            val previewBg = Reflect.findClass(
                "com.android.launcher3.folder.PreviewBackground",
                ctx.classLoader
            )
            Reflect.hookMethodOn(
                ctx.api,
                previewBg,
                "drawBackground",
                Canvas::class.java,
            ) { chain ->
                null
            }

            val blurUtils = Reflect.findClass(
                "com.meizu.flyme.launcher.utils.BackgroundBlurUtils",
                ctx.classLoader
            )
            fun disableFolderBlur(ctx: HookContext, instance: Any) {
                try {
                    Reflect.setBooleanField(instance, "mBlurEnable", false)
                    Reflect.callMethod(ctx.api, instance, "removeBlurDrawable")
                } catch (_: Throwable) {
                }
            }
            // FolderIcon 专用构造：new BackgroundBlurUtils(this, isBigFolderIcon())
            Reflect.hookConstructorOn(
                ctx.api,
                blurUtils,
                android.view.View::class.java,
                Boolean::class.javaPrimitiveType,
            ) { chain ->
                val result = chain.proceed()
                disableFolderBlur(ctx, chain.getThisObject())
                result
            }
            // 主题/图标包切换时可能重新打开模糊（仅 FolderIcon，避免影响小组件等）
            Reflect.hookMethodOn(
                ctx.api,
                blurUtils,
                "updateBlurShow",
            ) { chain ->
                val result = chain.proceed()
                val view = Reflect.getObjectField(chain.getThisObject(), "mView") ?: return@hookMethodOn result
                if (!view.javaClass.name.contains("FolderIcon")) return@hookMethodOn result
                disableFolderBlur(ctx, chain.getThisObject())
                result
            }
            Logger.i(TAG, "去掉文件夹遮罩已启用")
        } catch (e: Throwable) {
            Logger.e(TAG, "去掉文件夹遮罩 Hook 失败", e)
        }
    }

    // Flyme 12: setBlurRadius 只在创建时调一次，后续帧直接读 mBlurRadius 字段
    // 必须 afterHook 里把字段也改掉
    private fun hookIconBlurRadius12(ctx: HookContext, radius: Int) {
        try {
            val clazz = Reflect.findClass(
                "com.meizu.flyme.launcher.utils.BackgroundBlurDrawable",
                ctx.classLoader
            )
            Reflect.hookMethodOn(ctx.api, clazz, "setBlurRadius", Int::class.javaPrimitiveType,
            ) { chain ->
                val result = chain.proceed()
                Reflect.setIntField(chain.getThisObject(), "mBlurRadius", radius)
                result
            }
            Logger.i(TAG, "文件夹模糊半径(Flyme12): $radius")
        } catch (e: Throwable) {
            Logger.e(TAG, "文件夹模糊半径 Hook 失败(Flyme12)", e)
        }
    }

    // Flyme 12: 背景模糊强度最终由 BaseDepthController.applyDepthAndBlur 里的 mMaxBlurRadius 决定
    // folderDepth 动画到 0.8 时通常已被 clamp 到最大，直接改动画目标值看不出来
    // 改为在 folderDepth > 0 期间临时缩放 mMaxBlurRadius
    private fun hookOpenBlurStrength12(ctx: HookContext, strength: Float) {
        try {
            val controllerClass = Reflect.findClass(
                "com.android.quickstep.util.BaseDepthController",
                ctx.classLoader
            )
            val launcherClass = Reflect.findClass(
                "com.android.launcher3.Launcher",
                ctx.classLoader
            )

            Reflect.hookConstructorOn(
                ctx.api,
                controllerClass,
                launcherClass,
            ) { chain ->
                val result = chain.proceed()
                val controller = chain.getThisObject()
                maxBlurRadiusMap[controller] = Reflect.getIntField(controller, "mMaxBlurRadius")
                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                controllerClass,
                "applyDepthAndBlur",
            ) { chain ->
                val controller = chain.getThisObject()
                val folderDepth = Reflect.callMethod(
                    ctx.api,
                    Reflect.getObjectField(controller, "folderDepth"),
                    "getValue"
                ) as? Float ?: return@hookMethodOn chain.proceed()
                if (folderDepth <= 0f) return@hookMethodOn chain.proceed()

                val original = maxBlurRadiusMap[controller] ?: return@hookMethodOn chain.proceed()
                val scale = strength / 0.8f
                Reflect.setIntField(controller, "mMaxBlurRadius", (original * scale).toInt())

                val result = chain.proceed()
                Reflect.setIntField(controller, "mMaxBlurRadius", original)
                result
            }
            Logger.i(TAG, "文件夹展开模糊强度(Flyme12): $strength")
        } catch (e: Throwable) {
            Logger.e(TAG, "文件夹展开模糊强度 Hook 失败(Flyme12)", e)
        }
    }

    // 旧版: beforeHookedMethod 改参数即可
    private fun hookIconBlurRadiusLegacy(ctx: HookContext, radius: Int) {
        try {
            val clazz = Reflect.findClass(
                "com.meizu.flyme.launcher.utils.BackgroundBlurDrawable",
                ctx.classLoader
            )
            Reflect.hookMethodOn(ctx.api, clazz, "setBlurRadius", Int::class.javaPrimitiveType,
            ) { chain ->
                chain.proceed(arrayOf<Any>(radius))
            }
            Logger.i(TAG, "文件夹模糊半径: $radius")
        } catch (e: Throwable) {
            Logger.e(TAG, "文件夹模糊半径 Hook 失败", e)
        }
    }

    // 旧版: Property 名 "blur"
    private fun hookOpenBlurStrengthLegacy(ctx: HookContext, strength: Float) {
        try {
            Reflect.hookMethodOn(
                ctx.api,
                ObjectAnimator::class.java, "ofFloat",
                Any::class.java, Property::class.java, FloatArray::class.java,
            ) { chain ->
                val target = chain.getArg(0) ?: return@hookMethodOn chain.proceed()
                if (target.javaClass.name != "com.android.launcher3.statehandlers.DepthController") return@hookMethodOn chain.proceed()

                val prop = chain.getArg(1) as? Property<*, *> ?: return@hookMethodOn chain.proceed()
                if (prop.name != "blur") return@hookMethodOn chain.proceed()

                val values = chain.getArg(2) as FloatArray
                if (values.size == 1 && values[0] in 0.1f..1.0f) {
                    values[0] = strength
                }
                chain.proceed()
            }
            Logger.i(TAG, "文件夹展开模糊强度: $strength")
        } catch (e: Throwable) {
            Logger.e(TAG, "文件夹展开模糊强度 Hook 失败", e)
        }
    }
}