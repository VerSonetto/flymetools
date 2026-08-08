package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

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

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        val radiusDp = XposedPrefs.getFeatureValue(lpparam, packageName, FEATURE_KEY, 22)
            .coerceIn(0, 50)
        val radiusPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            radiusDp.toFloat(),
            Resources.getSystem().displayMetrics
        )

        hookResourcesDimension(radiusPx)
        hookSmoothCornerPathFallback(lpparam)
        hookCustomSmoothCornerDrawable(lpparam, radiusPx)
        hookSetViewSmoothCorner(lpparam, radiusPx)
        hookTileBackgroundCreation(lpparam, radiusPx)

        Logger.i(TAG, "控制中心组件圆角 Hook 完成: ${radiusDp}dp")
    }

    private fun hookResourcesDimension(radiusPx: Float) {
        try {
            val radiusPxInt = radiusPx.toInt()

            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getDimensionPixelSize",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isTargetDimen(param.thisObject as Resources, param.args[0] as Int)) {
                            param.result = radiusPxInt
                            Logger.once(TAG, "dim_px", "qs_corner_radius = ${radiusPxInt}px")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getDimension",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isTargetDimen(param.thisObject as Resources, param.args[0] as Int)) {
                            param.result = radiusPx
                        }
                    }
                }
            )

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
    private fun hookSmoothCornerPathFallback(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(SMOOTH_CORNER_PATH_GENERATOR, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "genSmoothCornerPath",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[6] as Boolean) return

                        val left = param.args[0] as Float
                        val top = param.args[1] as Float
                        val right = param.args[2] as Float
                        val bottom = param.args[3] as Float
                        val smoothness = param.args[4] as Float
                        val radius = param.args[5] as Float
                        val width = right - left
                        val height = bottom - top
                        if (width <= 0f || height <= 0f) return

                        val instance = XposedHelpers.getStaticObjectField(clazz, "INSTANCE")
                        val limit = XposedHelpers.callMethod(
                            instance,
                            "getSmoothnessRadiusLimit",
                            width,
                            height,
                            smoothness
                        ) as Float

                        if (radius > limit) {
                            param.args[6] = true
                            Logger.once(TAG, "path_fallback", "大圆角回退 RoundRect: r=$radius limit=$limit")
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 SmoothCornerPathGenerator.genSmoothCornerPath")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 SmoothCornerPathGenerator 失败", e)
        }
    }

    private fun hookCustomSmoothCornerDrawable(
        lpparam: XC_LoadPackage.LoadPackageParam,
        radiusPx: Float
    ) {
        try {
            val clazz = XposedHelpers.findClass(
                CUSTOM_SMOOTH_CORNER_DRAWABLE,
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz,
                "setRadius",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = radiusPx
                        Logger.once(TAG, "smooth_set", "CustomSmoothCornerDrawable.setRadius")
                    }
                }
            )
            Logger.i(TAG, "已挂载 CustomSmoothCornerDrawable.setRadius")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 CustomSmoothCornerDrawable 失败", e)
        }
    }

    private fun hookSetViewSmoothCorner(
        lpparam: XC_LoadPackage.LoadPackageParam,
        radiusPx: Float
    ) {
        try {
            val clazz = XposedHelpers.findClass(SYSTEM_UI_COMMON_UTILS, lpparam.classLoader)
            val method = clazz.declaredMethods.singleOrNull { m ->
                !m.isSynthetic &&
                    m.name == "setViewSmoothCorner" &&
                    m.parameterTypes.contentEquals(
                        arrayOf(View::class.java, Float::class.javaPrimitiveType)
                    )
            } ?: throw NoSuchMethodException("未找到 setViewSmoothCorner(View, float)")

            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[1] = radiusPx
                    Logger.once(TAG, "smooth_view", "setViewSmoothCorner 覆盖圆角")
                }
            })

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
        lpparam: XC_LoadPackage.LoadPackageParam,
        radiusPx: Float
    ) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ripple = param.result as? RippleDrawable ?: return
                val tileView = param.thisObject as View
                val backgroundDrawable = replaceRippleTileLayers(
                    ripple,
                    tileView,
                    lpparam.classLoader,
                    radiusPx
                ) ?: return
                XposedHelpers.setObjectField(param.thisObject, "backgroundDrawable", backgroundDrawable)
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass(FLYME_CUSTOM_QS_TILE_VIEW, lpparam.classLoader),
                "createTileBackground",
                hook
            )
            Logger.i(TAG, "已挂载 FlymeCustomQSTileView.createTileBackground")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 FlymeCustomQSTileView.createTileBackground 失败", e)
        }

        try {
            XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass(QS_TILE_VIEW_IMPL, lpparam.classLoader),
                "createTileBackground",
                hook
            )
            Logger.i(TAG, "已挂载 QSTileViewImpl.createTileBackground")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 QSTileViewImpl.createTileBackground 失败", e)
        }
    }

    private fun replaceRippleTileLayers(
        ripple: RippleDrawable,
        tileView: View,
        classLoader: ClassLoader,
        radiusPx: Float
    ): Drawable? {
        return try {
            val backgroundId = tileView.resources.getIdentifier(
                "background",
                "id",
                "com.android.systemui"
            )
            if (backgroundId == 0) return null

            val background = newSmoothCornerDrawable(classLoader, radiusPx)
            val mask = background.constantState?.newDrawable()?.mutate() ?: background.mutate()
            ripple.setDrawableByLayerId(backgroundId, background)
            ripple.setDrawableByLayerId(android.R.id.mask, mask)
            background
        } catch (e: Throwable) {
            Logger.e(TAG, "替换 Ripple 磁贴圆角层失败", e)
            null
        }
    }

    private fun newSmoothCornerDrawable(classLoader: ClassLoader, radiusPx: Float): Drawable {
        val clazz = XposedHelpers.findClass(CUSTOM_SMOOTH_CORNER_DRAWABLE, classLoader)
        val drawable = clazz.getConstructor().newInstance() as Drawable
        XposedHelpers.callMethod(drawable, "setRadius", radiusPx)
        return drawable
    }
}
