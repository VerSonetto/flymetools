package com.karen.flymetool.hook.feature.systemui

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.ProgressBar
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 控制中心组件背景不透明度（Flyme 12）。
 *
 * 反编译特征：
 * - 磁贴背景：`FlymeCustomQSTileView` / `QSTileViewImpl` 通过
 *   `qs_tile_*_bg_color` 等资源 setTint，走 `Resources.getColor`。
 * - 连接区：`MzQSPanel#setWrapperForUiModel` 对
 *   `qs_connectivity_wrapper_bg` 使用 `qs_tile_*_bg_color` setTint。
 * - 亮度 / 音量条：`qs_slider_progress_drawable.xml` 的 background 层
 *   直接引用 `@color/qs_slider_bg`，在 Drawable 解析时不会经过
 *   `Resources.getColor`；但 `BrightnessSliderView#initBrightnessViewComponents`
 *   初始化后可以拿到该 `GradientDrawable`，直接对其当前颜色做 alpha 缩放。
 *
 * 实现分两层：
 * 1. `Resources.getColor(int, Theme)`：统一处理磁贴 / 连接区等通过 getColor 上色的背景。
 * 2. `BrightnessSliderView#initBrightnessViewComponents`：处理滑条 XML 直引颜色的初始背景。
 *
 * 100% 表示保持系统原始不透明度，0% 表示完全透明。
 */
object ControlCenterOpacityHook : FeatureHook {

    private const val FEATURE_KEY = "control_center_bg_opacity"
    private const val TAG = "ControlCenterOpacity"

    private const val BRIGHTNESS_SLIDER_VIEW =
        "com.android.systemui.settings.brightness.BrightnessSliderView"

    /** 控制中心圆角组件背景色资源名（日/夜/低视觉质量/按压态都覆盖） */
    private val TARGET_COLOR_NAMES = setOf(
        // 普通磁贴
        "qs_tile_active_bg_color",
        "qs_tile_active_bg_color_for_night",
        "qs_tile_inactive_bg_color",
        "qs_tile_inactive_bg_color_for_night",
        "qs_tile_inactive_bg_color_for_low_visual_quality",
        "qs_tile_unavailable_bg_color",
        "qs_tile_unavailable_bg_color_for_night",
        // 连接区 / 容器
        "qs_tile_container_bg_color_for_night",
        "qs_connectivity_wrapper_bg",
        // 亮度 / 音量滑条（changeSliderBgColor / setWrapperForUiModel 的 getColor 路径）
        "qs_slider_bg",
        "qs_slider_bg_for_night",
        "qs_slider_bg_for_press",
        "qs_slider_bg_for_press_for_night",
        // 方形磁贴
        "qs_square_tile_active_bg_color",
        "qs_square_tile_active_bg_color_for_bright",
        "qs_square_tile_active_bg_color_polestar",
        "qs_square_tile_inactive_bg_color"
    )

    private val lock = Any()
    private val targetColorIds = HashSet<Int>()

    @Volatile
    private var colorIdsResolved = false

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        val opacity = XposedPrefs.getFeatureValue(
            lpparam,
            packageName,
            FEATURE_KEY,
            100
        ).coerceIn(0, 100)
        if (opacity == 100) {
            Logger.i(TAG, "不透明度为 100%，保持系统原始效果，跳过挂载")
            return
        }

        val scale = opacity / 100f
        hookResourcesGetColor(scale)
        hookBrightnessSliderInit(lpparam, scale)

        Logger.i(TAG, "控制中心组件背景不透明度 Hook 完成: ${opacity}%")
    }

    /**
     * 只挂载双参 `Resources.getColor(int, Theme)`。
     * `Context.getColor(int)` 与单参 `Resources.getColor(int)` 最终都会
     * 进入该方法，避免同时挂载两个重载导致 alpha 被重复缩放。
     */
    private fun hookResourcesGetColor(scale: Float) {
        try {
            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val resources = param.thisObject as? Resources ?: return
                        val resId = param.args[0] as? Int ?: return
                        if (!isTargetColor(resources, resId)) return

                        val original = param.result as? Int ?: return
                        val adjusted = adjustColorAlpha(original, scale)
                        if (adjusted != original) {
                            param.result = adjusted
                            val name = try {
                                resources.getResourceEntryName(resId)
                            } catch (_: Throwable) {
                                resId.toString()
                            }
                            Logger.once(
                                TAG,
                                "color_$name",
                                "$name alpha ${Color.alpha(original)}→${Color.alpha(adjusted)}"
                            )
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 Resources.getColor(int, Theme)")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Resources.getColor(int, Theme) 失败", e)
        }
    }

    /**
     * 滑条背景在 XML 里直引 `@color/qs_slider_bg`，初始化时不会经过
     * Resources.getColor。这里在 BrightnessSliderView 初始化后直接对
     * background 层 GradientDrawable 的当前颜色做一次 alpha 缩放。
     */
    private fun hookBrightnessSliderInit(
        lpparam: XC_LoadPackage.LoadPackageParam,
        scale: Float
    ) {
        try {
            val clazz = XposedHelpers.findClass(BRIGHTNESS_SLIDER_VIEW, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "initBrightnessViewComponents",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val slider = XposedHelpers.getObjectField(
                                param.thisObject,
                                "mSlider"
                            ) as? ProgressBar ?: return
                            val layer = slider.progressDrawable as? LayerDrawable ?: return
                            val background = layer.findDrawableByLayerId(
                                android.R.id.background
                            ) as? GradientDrawable ?: return

                            val original = background.getColor()?.defaultColor ?: return
                            val adjusted = adjustColorAlpha(original, scale)
                            if (adjusted != original) {
                                background.setColor(adjusted)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 BrightnessSliderView.initBrightnessViewComponents")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 BrightnessSliderView.initBrightnessViewComponents 失败", e)
        }
    }

    private fun isTargetColor(resources: Resources, resId: Int): Boolean {
        if (resId == 0) return false
        if (!colorIdsResolved) {
            synchronized(lock) {
                if (!colorIdsResolved) {
                    for (name in TARGET_COLOR_NAMES) {
                        var id = resources.getIdentifier(name, "color", "com.android.systemui")
                        if (id == 0) {
                            id = resources.getIdentifier(name, "color", "com.android.systemui.res")
                        }
                        if (id != 0) {
                            targetColorIds.add(id)
                        }
                    }
                    colorIdsResolved = true
                }
            }
        }
        return targetColorIds.contains(resId)
    }

    private fun adjustColorAlpha(color: Int, scale: Float): Int {
        val alpha = (Color.alpha(color) * scale).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
