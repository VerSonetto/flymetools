package com.karen.flymetool.hook.feature.systemui

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ProgressBar
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 控制中心组件背景不透明度（Flyme 12）。
 *
 * 反编译特征与历史问题根因：
 * - 磁贴背景：`FlymeCustomQSTileView` / `QSTileViewImpl` 等通过
 *   `qs_tile_*_bg_color` 等资源 setTint，走 `Resources.getColor`。
 * - 连接区：`MzQSPanel#setWrapperForUiModel` 对 `qs_connectivity_wrapper_bg`
 *   使用 `qs_tile_*_bg_color` setTint。
 * - 亮度 / 音量条：`qs_slider_progress_drawable.xml` 的 background 层直引
 *   `@color/qs_slider_bg`。Drawable XML 膨胀时 `TypedArray` 经由
 *   `Resources.getColorStateList` / 资源表解析颜色，**不走** `Resources.getColor`；
 *   且 `ToggleSeekBar#onAttachedToWindow`、`ToggleSeekBar#onConfigurationChanged`、
 *   `MzQSPanel#reInflateBrightnessSlider` / `reInflateVolumeSlider` 都会调用
 *   `setProgressDrawableResource(qs_slider_progress_drawable)`，从 DrawableCache
 *   取全新实例整体替换 Drawable（ConstantState 烘焙的是 XML 原始颜色）——
 *   这是历史上「重启后 / 随机场景透明度被打回原形」的根因。
 *
 * 实现分三层：
 * 1. `Resources.getColor(int, Theme)`：处理磁贴 / 连接区 /
 *    `setWrapperForUiModel` / `changeSliderBgColor` 等代码路径，并缓存原始颜色。
 * 2. `Resources.getColorStateList(int, Theme)`：处理 Drawable XML 膨胀路径，
 *    使 `qs_slider_progress_drawable` 首次膨胀时就把缩放后的颜色烘焙进
 *    DrawableCache 的 ConstantState，之后任何替换/重建取到的实例天然带不透明度。
 * 3. 幂等兜底补丁：在 `BrightnessSliderView#initBrightnessViewComponents`、
 *    `ToggleSeekBar#onAttachedToWindow`、`ToggleSeekBar#onConfigurationChanged`
 *    之后，把 background 层当前颜色与各 `qs_slider_bg*` 原始颜色比对：
 *    未缩放则补成绝对目标值（原始颜色 × 不透明度），已缩放则跳过——
 *    既不会与第 2 层叠加缩放，也保证个别固件 XML 解析路径不同时仍有覆盖。
 *
 * 100% 表示保持系统原始不透明度，0% 表示完全透明。
 */
object ControlCenterOpacityHook : FeatureHook {

    private const val FEATURE_KEY = "control_center_bg_opacity"
    private const val TAG = "ControlCenterOpacity"

    private const val BRIGHTNESS_SLIDER_VIEW =
        "com.android.systemui.settings.brightness.BrightnessSliderView"
    private const val TOGGLE_SEEK_BAR =
        "com.android.systemui.settings.brightness.ToggleSeekBar"

    /** 滑条 background 层可能出现的全部颜色资源名（日/夜/低视觉质量/按压态），用于幂等比对 */
    private val SLIDER_BG_COLOR_NAMES = listOf(
        "qs_slider_bg",
        "qs_slider_bg_for_night",
        "qs_tile_inactive_bg_color_for_low_visual_quality",
        "qs_slider_bg_for_press",
        "qs_slider_bg_for_press_for_night"
    )

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

    /** resId -> 未缩放的原始颜色（getColor/getColorStateList 首次命中时捕获） */
    private val originalColorCache = HashMap<Int, Int>()

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
        hookResourcesGetColorStateList(scale)
        hookSliderBgResetPoints(lpparam, scale)

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
                        cacheOriginalColor(resId, original)
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
     * 挂载双参 `Resources.getColorStateList(int, Theme)`。
     * 滑条背景在 `qs_slider_progress_drawable.xml` 里直引 `@color/qs_slider_bg`，
     * Drawable 膨胀时经 `TypedArray.getColorStateList` 进入该方法——
     * 缩放其返回值后，DrawableCache 烘焙的 ConstantState 即为缩放色，
     * 后续 `setProgressDrawableResource` 的所有替换实例天然携带不透明度。
     */
    private fun hookResourcesGetColorStateList(scale: Float) {
        try {
            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val resources = param.thisObject as? Resources ?: return
                        val resId = param.args[0] as? Int ?: return
                        if (!isTargetColor(resources, resId)) return

                        val original = param.result as? ColorStateList ?: return
                        val originalDefault = original.defaultColor
                        cacheOriginalColor(resId, originalDefault)
                        val adjusted = scaleColorStateList(original, scale)
                        if (adjusted.defaultColor != originalDefault) {
                            param.result = adjusted
                            val name = try {
                                resources.getResourceEntryName(resId)
                            } catch (_: Throwable) {
                                resId.toString()
                            }
                            Logger.once(
                                TAG,
                                "csl_$name",
                                "$name alpha ${Color.alpha(originalDefault)}→${Color.alpha(adjusted.defaultColor)}"
                            )
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 Resources.getColorStateList(int, Theme)")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 Resources.getColorStateList(int, Theme) 失败", e)
        }
    }

    /**
     * 滑条 Drawable 替换点兜底补丁：
     * - `initBrightnessViewComponents`：布局膨胀完成后执行一次；
     * - `ToggleSeekBar#onAttachedToWindow`：每次 attach 都会
     *   `setProgressDrawableResource(qs_slider_progress_drawable)` 整体替换 Drawable；
     * - `ToggleSeekBar#onConfigurationChanged`：任何配置变化同样替换。
     * 补丁本身幂等（比对原始颜色后按绝对目标值写入），多层并存不会叠加缩放。
     */
    private fun hookSliderBgResetPoints(
        lpparam: XC_LoadPackage.LoadPackageParam,
        scale: Float
    ) {
        try {
            val sliderViewClass = XposedHelpers.findClass(BRIGHTNESS_SLIDER_VIEW, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                sliderViewClass,
                "initBrightnessViewComponents",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val slider =
                                XposedHelpers.getObjectField(param.thisObject, "mSlider") as? View
                                    ?: return
                            applySliderBgAlpha(slider, scale)
                        } catch (t: Throwable) {
                            Logger.e(TAG, "滑条初始化补丁失败", t)
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 BrightnessSliderView.initBrightnessViewComponents")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 BrightnessSliderView.initBrightnessViewComponents 失败", e)
        }

        try {
            val seekBarClass = XposedHelpers.findClass(TOGGLE_SEEK_BAR, lpparam.classLoader)
            val patchHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val seekBar = param.thisObject as? View ?: return
                        applySliderBgAlpha(seekBar, scale)
                    } catch (t: Throwable) {
                        Logger.e(TAG, "滑条替换后补丁失败", t)
                    }
                }
            }
            XposedHelpers.findAndHookMethod(seekBarClass, "onAttachedToWindow", patchHook)
            XposedHelpers.findAndHookMethod(
                seekBarClass,
                "onConfigurationChanged",
                android.content.res.Configuration::class.java,
                patchHook
            )
            Logger.i(TAG, "已挂载 ToggleSeekBar.onAttachedToWindow/onConfigurationChanged")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 ToggleSeekBar 替换补丁失败", e)
        }
    }

    /**
     * 幂等补丁：把 background 层颜色规整到「原始颜色 × 不透明度」的绝对目标值。
     * - 当前颜色等于某 `qs_slider_bg*` 原始颜色：说明未经缩放，补成缩放值；
     * - 当前颜色已等于缩放目标值（getColorStateList 层已生效）：跳过；
     * - 无法匹配（第三方主题自定义色等）：不动。
     */
    private fun applySliderBgAlpha(seekBar: View, scale: Float) {
        val layer = (seekBar as? ProgressBar)?.progressDrawable as? LayerDrawable ?: return
        val background = layer.findDrawableByLayerId(android.R.id.background) as? GradientDrawable
            ?: return
        val current = background.color?.defaultColor ?: return

        for (name in SLIDER_BG_COLOR_NAMES) {
            val base = resolveOriginalColor(seekBar.resources, name) ?: continue
            val target = adjustColorAlpha(base, scale)
            if (current == target) {
                return
            }
            if (sameRgb(current, base)) {
                background.setColor(target)
                Logger.once(
                    TAG,
                    "patch_$name",
                    "滑条背景 $name alpha ${Color.alpha(current)}→${Color.alpha(target)}"
                )
                return
            }
        }
    }

    /**
     * 获取某个颜色资源的**未缩放**原始值：
     * 优先读缓存（getColor/getColorStateList 命中时已捕获），
     * 否则用 `XposedBridge.invokeOriginalMethod` 绕过本模块 Hook 现取。
     */
    private fun resolveOriginalColor(resources: Resources, name: String): Int? {
        val id = resolveColorId(resources, name)
        if (id == 0) return null
        synchronized(originalColorCache) {
            originalColorCache[id]?.let { return it }
        }
        val value = try {
            val method = Resources::class.java.getDeclaredMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            )
            XposedBridge.invokeOriginalMethod(method, resources, arrayOf<Any?>(id, null)) as? Int
        } catch (t: Throwable) {
            Logger.once(TAG, "orig_fail_$name", "获取 $name 原始颜色失败，跳过幂等补丁")
            null
        } ?: return null
        cacheOriginalColor(id, value)
        return value
    }

    private fun cacheOriginalColor(resId: Int, color: Int) {
        synchronized(originalColorCache) {
            if (!originalColorCache.containsKey(resId)) {
                originalColorCache[resId] = color
            }
        }
    }

    /** 缩放 ColorStateList 全部状态色；反射失败时退化为仅缩放默认色。 */
    @Suppress("UNCHECKED_CAST")
    private fun scaleColorStateList(list: ColorStateList, scale: Float): ColorStateList {
        return try {
            val specsField = ColorStateList::class.java.getDeclaredField("mStateSpecs")
            val colorsField = ColorStateList::class.java.getDeclaredField("mColors")
            specsField.isAccessible = true
            colorsField.isAccessible = true
            val specs = specsField.get(list) as? Array<IntArray>
            val colors = colorsField.get(list) as? IntArray
            if (specs == null || colors == null || specs.size != colors.size) {
                return ColorStateList.valueOf(adjustColorAlpha(list.defaultColor, scale))
            }
            val scaledColors = IntArray(colors.size) { adjustColorAlpha(colors[it], scale) }
            ColorStateList(specs, scaledColors)
        } catch (t: Throwable) {
            ColorStateList.valueOf(adjustColorAlpha(list.defaultColor, scale))
        }
    }

    private fun isTargetColor(resources: Resources, resId: Int): Boolean {
        if (resId == 0) return false
        if (!colorIdsResolved) {
            synchronized(lock) {
                if (!colorIdsResolved) {
                    for (name in TARGET_COLOR_NAMES) {
                        val id = resolveColorId(resources, name)
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

    private fun resolveColorId(resources: Resources, name: String): Int {
        var id = resources.getIdentifier(name, "color", "com.android.systemui")
        if (id == 0) {
            id = resources.getIdentifier(name, "color", "com.android.systemui.res")
        }
        return id
    }

    private fun sameRgb(colorA: Int, colorB: Int): Boolean {
        return (colorA and 0x00FFFFFF) == (colorB and 0x00FFFFFF)
    }

    private fun adjustColorAlpha(color: Int, scale: Float): Int {
        val alpha = (Color.alpha(color) * scale).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
