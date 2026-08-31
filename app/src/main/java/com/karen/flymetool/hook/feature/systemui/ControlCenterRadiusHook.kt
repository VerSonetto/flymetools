package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils

/**
 * 控制中心组件圆角。
 *
 * 特征定位（Flyme 12）：
 * - 资源名 `qs_corner_radius`（默认 22dp）：磁贴 / 连接区 / 滑条统一读取
 * - `CustomSmoothCornerDrawable#setRadius`：普通 QS 磁贴平滑圆角背景
 * - `SystemUICommonUtils#setViewSmoothCorner`：亮度 / 音量滑条 Outline
 * - `SmoothCornerPathGenerator#genSmoothCornerPath`：大圆角时需回退原生 RoundRect，
 *   否则四边中点会出现贝塞尔接缝（用户可见的「外框连接」）
 */
object ControlCenterRadiusHook : FeatureHook {

    private const val FEATURE_KEY = "control_center_radius"
    private const val TAG = "ControlCenterRadius"
    private const val TARGET_DIMEN = "qs_corner_radius"
    private const val CUSTOM_SMOOTH_CORNER_DRAWABLE =
        "com.android.systemui.qs.CustomSmoothCornerDrawable"
    private const val SYSTEM_UI_COMMON_UTILS =
        "com.flyme.systemui.utils.SystemUICommonUtils"
    private const val SMOOTH_CORNER_PATH_GENERATOR =
        "com.meizu.common.util.SmoothCornerPathGenerator"
    private const val FLYME_CUSTOM_QS_TILE_VIEW =
        "com.flyme.systemui.qs.tileimpl.FlymeCustomQSTileView"
    private const val QS_TILE_VIEW_IMPL =
        "com.android.systemui.qs.tileimpl.QSTileViewImpl"

    /** 目标资源 ID，-1 未解析。SystemUI 资源表进程内固定，首次解析后缓存全局有效 */
    private var targetDimenResId = -1

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!ctx.featureEnabled(FEATURE_KEY)) return

        val radiusDp = ctx.featureValue(FEATURE_KEY, 22)
            .coerceIn(0, 50)
        val radiusPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            radiusDp.toFloat(),
            Resources.getSystem().displayMetrics
        )

        hookResourcesDimension(ctx, radiusPx)
        hookSmoothCornerPathFallback(ctx)
        hookCustomSmoothCornerDrawable(ctx, radiusPx)
        hookSetViewSmoothCorner(ctx, radiusPx)
        hookTileBackgroundCreation(ctx, radiusPx)

        Logger.i(TAG, "控制中心组件圆角 Hook 完成: ${radiusDp}dp")
    }

    private fun hookResourcesDimension(ctx: HookContext, radiusPx: Float) {
        try {
            val radiusPxInt = radiusPx.toInt()

            Reflect.hookMethodOn(
                ctx.api,
                Resources::class.java,
                "getDimensionPixelSize",
                Int::class.javaPrimitiveType,
            ) { chain ->
                val result = chain.proceed()
                if (isTargetDimen(chain.getThisObject() as Resources, chain.getArg(0) as Int)) {
                    Logger.once(TAG, "dim_px", "qs_corner_radius = ${radiusPxInt}px")
                    return@hookMethodOn radiusPxInt
                }
                result
            }

            Reflect.hookMethodOn(
                ctx.api,
                Resources::class.java,
                "getDimension",
                Int::class.javaPrimitiveType,
            ) { chain ->
                val result = chain.proceed()
                if (isTargetDimen(chain.getThisObject() as Resources, chain.getArg(0) as Int)) {
                    return@hookMethodOn radiusPx
                }
                result
            }

            Logger.i(TAG, "已挂载 Resources.getDimension*")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Resources.getDimension* 失败", e)
        }
    }

    private fun isTargetDimen(resources: Resources, resId: Int): Boolean {
        if (resId == 0) return false
        if (targetDimenResId == -1) {
            targetDimenResId = resources.getIdentifier(TARGET_DIMEN, null, "com.android.systemui")
        }
        return resId == targetDimenResId
    }

    /**
     * CustomSmoothCornerDrawable 在大圆角时仍走平滑贝塞尔路径，四边中点会露出方形外框接缝。
     * 系统原生逻辑：半径超过 smoothness 上限时应改用 RoundRect（useNativeRoundCorner=true）。
     */
    private fun hookSmoothCornerPathFallback(ctx: HookContext) {
        try {
            val clazz = Reflect.findClass(SMOOTH_CORNER_PATH_GENERATOR, ctx.classLoader)
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "genSmoothCornerPath",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ) { chain ->
                val args = chain.getArgs().toTypedArray()
                if (args[6] as Boolean) return@hookMethodOn chain.proceed()

                val left = args[0] as Float
                val top = args[1] as Float
                val right = args[2] as Float
                val bottom = args[3] as Float
                val smoothness = args[4] as Float
                val radius = args[5] as Float
                val width = right - left
                val height = bottom - top
                if (width <= 0f || height <= 0f) return@hookMethodOn chain.proceed()

                val instance = Reflect.getStaticObjectField(clazz, "INSTANCE")
                val limit = Reflect.callMethod(
                    ctx.api,
                    instance,
                    "getSmoothnessRadiusLimit",
                    width,
                    height,
                    smoothness
                ) as Float

                if (radius > limit) {
                    args[6] = true
                    Logger.once(TAG, "path_fallback", "大圆角回退 RoundRect: r=$radius limit=$limit")
                }
                chain.proceed(args)
            }
            Logger.i(TAG, "已挂载 SmoothCornerPathGenerator.genSmoothCornerPath")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 SmoothCornerPathGenerator 失败", e)
        }
    }

    private fun hookCustomSmoothCornerDrawable(
        ctx: HookContext,
        radiusPx: Float
    ) {
        try {
            val clazz = Reflect.findClass(
                CUSTOM_SMOOTH_CORNER_DRAWABLE,
                ctx.classLoader
            )
            Reflect.hookMethodOn(
                ctx.api,
                clazz,
                "setRadius",
                Float::class.javaPrimitiveType,
            ) { chain ->
                val args = chain.getArgs().toTypedArray()
                args[0] = radiusPx
                Logger.once(TAG, "smooth_set", "CustomSmoothCornerDrawable.setRadius")
                chain.proceed(args)
            }
            Logger.i(TAG, "已挂载 CustomSmoothCornerDrawable.setRadius")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 CustomSmoothCornerDrawable 失败", e)
        }
    }

    private fun hookSetViewSmoothCorner(
        ctx: HookContext,
        radiusPx: Float
    ) {
        try {
            val clazz = Reflect.findClass(SYSTEM_UI_COMMON_UTILS, ctx.classLoader)
            val method = clazz.declaredMethods.singleOrNull { m ->
                !m.isSynthetic &&
                    m.name == "setViewSmoothCorner" &&
                    m.parameterTypes.contentEquals(
                        arrayOf(View::class.java, Float::class.javaPrimitiveType)
                    )
            } ?: throw NoSuchMethodException("未找到 setViewSmoothCorner(View, float)")

            Reflect.hookMethod(ctx.api, method) { chain ->
                val args = chain.getArgs().toTypedArray()
                args[1] = radiusPx
                Logger.once(TAG, "smooth_view", "setViewSmoothCorner 覆盖圆角")
                chain.proceed(args)
            }

            Logger.i(TAG, "已挂载 SystemUICommonUtils.setViewSmoothCorner")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 setViewSmoothCorner 失败", e)
        }
    }

    /**
     * FlymeCustomQSTileView（设备中心等胶囊磁贴）仍用 shape XML 作为 Ripple 层；
     * QSTileViewImpl 的 Ripple mask 层也可能与 CustomSmoothCornerDrawable 不同步。
     * 创建背景时统一替换为 CustomSmoothCornerDrawable。
     */
    private fun hookTileBackgroundCreation(
        ctx: HookContext,
        radiusPx: Float
    ) {
        val block: (io.github.libxposed.api.XposedInterface.Chain) -> Any? = block@ { chain ->
            val result = chain.proceed()
            val ripple = result as? RippleDrawable
            if (ripple != null) {
                val tileView = chain.getThisObject() as View
                val backgroundDrawable = replaceRippleTileLayers(
                    ctx,
                    ripple,
                    tileView,
                    radiusPx
                ) ?: return@block result
                Reflect.setObjectField(chain.getThisObject(), "backgroundDrawable", backgroundDrawable)
            }
            result
        }

        try {
            Reflect.hookMethodOn(
                ctx.api,
                Reflect.findClass(FLYME_CUSTOM_QS_TILE_VIEW, ctx.classLoader),
                "createTileBackground",
            ) { chain -> block(chain) }
            Logger.i(TAG, "已挂载 FlymeCustomQSTileView.createTileBackground")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 FlymeCustomQSTileView.createTileBackground 失败", e)
        }

        try {
            Reflect.hookMethodOn(
                ctx.api,
                Reflect.findClass(QS_TILE_VIEW_IMPL, ctx.classLoader),
                "createTileBackground",
            ) { chain -> block(chain) }
            Logger.i(TAG, "已挂载 QSTileViewImpl.createTileBackground")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 QSTileViewImpl.createTileBackground 失败", e)
        }
    }

    private fun replaceRippleTileLayers(
        ctx: HookContext,
        ripple: RippleDrawable,
        tileView: View,
        radiusPx: Float
    ): Drawable? {
        return try {
            val backgroundId = tileView.resources.getIdentifier(
                "background",
                "id",
                "com.android.systemui"
            )
            if (backgroundId == 0) return null

            val background = newSmoothCornerDrawable(ctx, radiusPx)
            val mask = background.constantState?.newDrawable()?.mutate() ?: background.mutate()
            ripple.setDrawableByLayerId(backgroundId, background)
            ripple.setDrawableByLayerId(android.R.id.mask, mask)
            background
        } catch (e: Throwable) {
            Logger.e(TAG, "替换 Ripple 磁贴圆角层失败", e)
            null
        }
    }

    private fun newSmoothCornerDrawable(ctx: HookContext, radiusPx: Float): Drawable {
        val clazz = Reflect.findClass(CUSTOM_SMOOTH_CORNER_DRAWABLE, ctx.classLoader)
        val drawable = clazz.getConstructor().newInstance() as Drawable
        Reflect.callMethod(ctx.api, drawable, "setRadius", radiusPx)
        return drawable
    }
}