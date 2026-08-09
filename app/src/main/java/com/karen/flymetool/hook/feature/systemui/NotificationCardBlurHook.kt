package com.karen.flymetool.hook.feature.systemui

import android.graphics.Color
import android.os.SystemClock
import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 通知 / 媒体卡片模糊强度与遮罩浓度（Flyme 12）。
 *
 * 反编译：
 * - 通知 Live：NotificationBackgroundView.setBlurBgForLive →
 *   MzBlurUtils.setBackgroundBlurDrawable(..., radius=180, color=日/夜)
 * - 通知色入口：setBlurBackground(isStatic, color, clipR)
 * - HUN：LandscapeHeadsUpNotificationView 直调 MzBlurUtils
 * - 媒体 Live：MediaCarouseTransitionLayout.setBlurBgForLive →
 *   同样 MzBlurUtils(..., 180, color=日/夜 0xB2…)
 * - 媒体 Static：setAllForegroundColor + addBlurDrawableTo(this, color, r)
 *
 * 子开关「曲线联动」beautify（默认关）：
 * - 关：线性半径 + 幂映射不透明度
 * - 开：强度曲线、罩色联动、日/夜轻微偏色
 *
 * 不透明度：滑块值经 0.7 次幂映射，同参数比线性更不透明（35 → 约 48%）。
 * 强度：0..200，>100 时半径上限随强度延伸（100 → 240，200 → 480）。
 */
object NotificationCardBlurHook : FeatureHook {

    private const val FEATURE_KEY = "notification_card_blur"
    private const val OPACITY_SUFFIX = "opacity"
    private const val BEAUTIFY_SUFFIX = "beautify"
    private const val TAG = "NotificationCardBlur"

    private const val DEFAULT_INTENSITY = 50
    private const val DEFAULT_OPACITY = 70
    private const val DEFAULT_BEAUTIFY = 0

    private const val INTENSITY_GAIN = 2f
    private const val RADIUS_CURVE_EXP = 0.88f
    private const val COUPLE_SPAN = 18f
    private const val DAY_TINT_MIX = 0.14f
    private const val NIGHT_TINT_MIX = 0.12f

    /** 不透明度幂映射指数：<1 时同参数更不透明（35% → 约 48% 视觉不透明度） */
    private const val OPACITY_EXP = 0.7f

    /** 强度 100 时等效 Live 半径（现状上限） */
    private const val MAX_LIVE_RADIUS = 240
    /** 强度 200 时等效 Live 半径上限（100→200 线性延伸） */
    private const val MAX_LIVE_RADIUS_200 = 480
    private const val MAX_LIVE_RADIUS_STACKED = 180
    private const val PREFS_TTL_MS = 800L

    private const val BACKGROUND_VIEW =
        "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val LANDSCAPE_HUN_VIEW =
        "com.flyme.notification.view.LandscapeHeadsUpNotificationView"
    /** 媒体播放器卡片（通知中心 / 锁屏媒体） */
    private const val MEDIA_CAROUSEL_VIEW =
        "com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout"
    private const val MZ_BLUR_UTILS = "com.flyme.systemui.utils.MzBlurUtils"
    private const val WALLPAPER_BLUR_MANAGER =
        "com.flyme.systemui.wallpaper.WallpaperBlurDrawableManager"

    private var prefsPackage: String = "com.android.systemui"
    private var loadParam: XC_LoadPackage.LoadPackageParam? = null

    private var backgroundViewClass: Class<*>? = null
    private var landscapeHunClass: Class<*>? = null
    private var mediaCarouselClass: Class<*>? = null

    private var prefsCachedAt = 0L
    private var cachedIntensity = DEFAULT_INTENSITY
    private var cachedOpacityBias = DEFAULT_OPACITY
    private var cachedBeautify = false
    private var cachedRadiusMul = 1f

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        prefsPackage = packageName
        loadParam = lpparam
        backgroundViewClass = findClassOrNull(BACKGROUND_VIEW)
        landscapeHunClass = findClassOrNull(LANDSCAPE_HUN_VIEW)
        mediaCarouselClass = findClassOrNull(MEDIA_CAROUSEL_VIEW)
        refreshPrefs(force = true)

        hookSetBlurBackground()
        hookMzBlurUtilsImpl()
        hookMediaStaticForegroundColor()

        Logger.i(
            TAG,
            "已启用：通知/媒体模糊 beautify=$cachedBeautify " +
                "intensity=$cachedIntensity opacity=$cachedOpacityBias " +
                "media=${mediaCarouselClass != null}"
        )
    }

    private fun findClassOrNull(name: String): Class<*>? {
        return try {
            XposedHelpers.findClass(name, loadParam!!.classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookSetBlurBackground() {
        val bgCl = backgroundViewClass ?: return
        try {
            XposedHelpers.findAndHookMethod(
                bgCl,
                "setBlurBackground",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        refreshPrefs(force = false)
                        val original = param.args[1] as? Int ?: return
                        val adjusted = applyMaskColor(original)
                        if (adjusted != original) {
                            param.args[1] = adjusted
                            Logger.once(
                                TAG,
                                "notif_color_${cachedIntensity}_$cachedOpacityBias",
                                "通知罩色 ${Integer.toHexString(original)}→${Integer.toHexString(adjusted)}"
                            )
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 NotificationBackgroundView.setBlurBackground")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 setBlurBackground 失败", e)
        }
    }

    /**
     * 10 参实现：通知背景 / HUN / 媒体卡片 共用。
     * 媒体与 HUN 的 color 都走 args[4]，不经 setBlurBackground。
     */
    private fun hookMzBlurUtilsImpl() {
        try {
            val blurUtilsClass = XposedHelpers.findClass(MZ_BLUR_UTILS, loadParam!!.classLoader)
            val impl = blurUtilsClass.declaredMethods.singleOrNull { m ->
                !m.isSynthetic &&
                    m.name == "setBackgroundBlurDrawable" &&
                    m.returnType == Void.TYPE &&
                    m.parameterTypes.size == 10 &&
                    m.parameterTypes[0] == View::class.java &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[2] == Float::class.javaPrimitiveType &&
                    m.parameterTypes[3] == Float::class.javaPrimitiveType &&
                    m.parameterTypes[4] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[5] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[6] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[7] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[8].name.endsWith("Function0") &&
                    m.parameterTypes[9].name.endsWith("Consumer")
            } ?: throw NoSuchMethodException("未找到 10 参 setBackgroundBlurDrawable 实现")

            XposedBridge.hookMethod(impl, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args[0] as? View ?: return
                    if (!isTargetBlurView(view)) return

                    refreshPrefs(force = false)

                    val originalRadius = param.args[1] as? Int ?: return
                    if (originalRadius > 0) {
                        val scaled = scaleLiveRadius(originalRadius)
                        if (scaled != originalRadius) {
                            param.args[1] = scaled
                            Logger.once(
                                TAG,
                                "radius_${view.javaClass.simpleName}_$cachedIntensity",
                                "radius $originalRadius→$scaled view=${view.javaClass.simpleName}"
                            )
                        }
                    }

                    // 直传 color：HUN、媒体；通知背景色已在 setBlurBackground 处理
                    if (isDirectColorBlurView(view)) {
                        val color = param.args[4] as? Int ?: return
                        val adjusted = applyMaskColor(color)
                        if (adjusted != color) {
                            param.args[4] = adjusted
                        }
                    }
                }
            })
            Logger.i(TAG, "已挂载 MzBlurUtils 10 参（通知+HUN+媒体）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MzBlurUtils 失败", e)
        }
    }

    /**
     * 媒体 Static：WallpaperBlurDrawableManager.setAllForegroundColor(View, int)
     * 与 addBlurDrawableTo(View, int color, float r) 的 color 参数。
     */
    private fun hookMediaStaticForegroundColor() {
        if (mediaCarouselClass == null) return
        try {
            val mgr = XposedHelpers.findClass(WALLPAPER_BLUR_MANAGER, loadParam!!.classLoader)

            XposedHelpers.findAndHookMethod(
                mgr,
                "setAllForegroundColor",
                View::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        if (!isMediaCarousel(view)) return
                        refreshPrefs(force = false)
                        val original = param.args[1] as? Int ?: return
                        val adjusted = applyMaskColor(original)
                        if (adjusted != original) {
                            param.args[1] = adjusted
                            Logger.once(
                                TAG,
                                "media_static_fg_$cachedOpacityBias",
                                "媒体 Static 罩色 ${Integer.toHexString(original)}→${Integer.toHexString(adjusted)}"
                            )
                        }
                    }
                }
            )

            // addBlurDrawableTo(View, int color, float radius) 与 5 参圆角版
            for (m in mgr.declaredMethods) {
                if (m.isSynthetic || m.name != "addBlurDrawableTo") continue
                val pts = m.parameterTypes
                if (pts.isEmpty() || !View::class.java.isAssignableFrom(pts[0])) continue
                if (pts.size < 2 || pts[1] != Int::class.javaPrimitiveType) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        if (!isMediaCarousel(view)) return
                        refreshPrefs(force = false)
                        val original = param.args[1] as? Int ?: return
                        val adjusted = applyMaskColor(original)
                        if (adjusted != original) param.args[1] = adjusted
                    }
                })
            }
            Logger.i(TAG, "已挂载媒体 Static 壁纸模糊罩色")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载媒体 Static 罩色失败", e)
        }
    }

    private fun isTargetBlurView(view: View): Boolean {
        return isNotificationBackground(view) ||
            isLandscapeHun(view) ||
            isMediaCarousel(view)
    }

    /** color 不经 setBlurBackground、直接进 MzBlurUtils / Static manager */
    private fun isDirectColorBlurView(view: View): Boolean {
        return isLandscapeHun(view) || isMediaCarousel(view)
    }

    private fun isNotificationBackground(view: View): Boolean {
        val cl = backgroundViewClass ?: return false
        return cl.isInstance(view)
    }

    private fun isLandscapeHun(view: View): Boolean {
        val cl = landscapeHunClass ?: return false
        return cl.isInstance(view)
    }

    private fun isMediaCarousel(view: View): Boolean {
        val cl = mediaCarouselClass ?: return false
        return cl.isInstance(view)
    }

    private fun refreshPrefs(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - prefsCachedAt < PREFS_TTL_MS) return
        prefsCachedAt = now
        val lp = loadParam ?: return
        cachedIntensity = XposedPrefs.getFeatureValue(
            lp, prefsPackage, FEATURE_KEY, DEFAULT_INTENSITY
        ).coerceIn(0, 200)
        cachedOpacityBias = XposedPrefs.getFeatureExtraValue(
            lp, prefsPackage, FEATURE_KEY, OPACITY_SUFFIX, DEFAULT_OPACITY
        ).coerceIn(0, 100)
        cachedBeautify = XposedPrefs.getFeatureExtraValue(
            lp, prefsPackage, FEATURE_KEY, BEAUTIFY_SUFFIX, DEFAULT_BEAUTIFY
        ) == 1
        cachedRadiusMul = computeRadiusMul(cachedIntensity, cachedBeautify)
    }

    private fun computeRadiusMul(intensityPercent: Int, beautify: Boolean): Float {
        if (intensityPercent <= 0) return 0f
        val t = intensityPercent / 100f
        return if (beautify) {
            t.toDouble().pow(RADIUS_CURVE_EXP.toDouble()).toFloat() * INTENSITY_GAIN
        } else {
            t * INTENSITY_GAIN
        }
    }

    private fun scaleLiveRadius(original: Int): Int {
        if (original <= 0 || cachedRadiusMul <= 0f) return 0
        return (original * cachedRadiusMul).roundToInt().coerceIn(0, liveRadiusCap())
    }

    /**
     * 半径上限：强度 ≤100 时维持原上限（240 / 堆叠 180），
     * >100 时随强度线性延伸至 200 → 480，让 100 的效果不变、200 明显更糊。
     */
    private fun liveRadiusCap(): Int {
        val base = if (IosNotificationStackHook.stackBlurSoftCapActive) {
            MAX_LIVE_RADIUS_STACKED
        } else {
            MAX_LIVE_RADIUS
        }
        val extra = (cachedIntensity - 100).coerceAtLeast(0) *
            (MAX_LIVE_RADIUS_200 - MAX_LIVE_RADIUS) / 100
        return base + extra
    }

    /**
     * 堆叠 softCap 边沿：压/恢复已创建 BackgroundBlurDrawable 半径。
     * rows 可为通知 row；媒体若在列表中也会尝试 row 自身 background。
     */
    fun onStackSoftCapChanged(rows: List<View>, softCap: Boolean) {
        refreshPrefs(force = false)
        // cap 内部已按 stackBlurSoftCapActive 区分（180/240 基准 + 强度>100 延伸）
        val target = scaleLiveRadius(180)
        var n = 0
        for (row in rows) {
            if (applyRadiusToRowBackgrounds(row, target)) n++
        }
        if (n > 0) {
            Logger.d(TAG) { "stackSoftCap=$softCap → radius=$target on $n rows" }
        }
    }

    private fun applyRadiusToRowBackgrounds(row: View, radius: Int): Boolean {
        var touched = false
        for (field in arrayOf("mBackgroundFlyme", "mBackgroundNormal")) {
            try {
                val bg = XposedHelpers.getObjectField(row, field) as? View ?: continue
                if (setBlurRadiusOnViewBackground(bg, radius)) touched = true
            } catch (_: Throwable) {
            }
        }
        if (setBlurRadiusOnViewBackground(row, radius)) touched = true
        return touched
    }

    private fun setBlurRadiusOnViewBackground(view: View, radius: Int): Boolean {
        return try {
            val d = view.background ?: return false
            if (!d.javaClass.name.endsWith("BackgroundBlurDrawable")) return false
            XposedHelpers.callMethod(d, "setBlurRadius", radius)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun effectiveOpacityPercent(): Int {
        val raw = if (!cachedBeautify) {
            cachedOpacityBias
        } else {
            val couple = (50f - cachedIntensity) / 50f * COUPLE_SPAN
            (cachedOpacityBias + couple).roundToInt().coerceIn(0, 100)
        }
        // 幂映射：同滑块值比线性更不透明（35 → 约 48%，70 → 约 78%）
        return (100f * Math.pow((raw / 100f).toDouble(), OPACITY_EXP.toDouble()).toFloat())
            .roundToInt().coerceIn(0, 100)
    }

    private fun applyMaskColor(color: Int): Int {
        if (color == Color.TRANSPARENT) return color
        val alpha = (255 * effectiveOpacityPercent() / 100f).roundToInt().coerceIn(0, 255)
        if (!cachedBeautify) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }
        var r = Color.red(color)
        var g = Color.green(color)
        var b = Color.blue(color)
        val luminance = (r * 299 + g * 587 + b * 114) / 1000
        if (luminance >= 160) {
            r = mixChannel(r, 236, DAY_TINT_MIX)
            g = mixChannel(g, 241, DAY_TINT_MIX)
            b = mixChannel(b, 247, DAY_TINT_MIX)
        } else if (luminance <= 80) {
            r = mixChannel(r, 34, NIGHT_TINT_MIX)
            g = mixChannel(g, 36, NIGHT_TINT_MIX)
            b = mixChannel(b, 40, NIGHT_TINT_MIX)
        }
        return Color.argb(alpha, r, g, b)
    }

    private fun mixChannel(from: Int, to: Int, t: Float): Int {
        return (from + (to - from) * t).roundToInt().coerceIn(0, 255)
    }
}
