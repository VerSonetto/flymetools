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
 * 通知卡片模糊强度 / 遮罩不透明度（Flyme 12）。
 *
 * 反编译依据：
 * - Live：NotificationBackgroundView.setBlurBgForLive →
 *   MzBlurUtils.setBackgroundBlurDrawable(view, blurRadius=180, …) 完整 10 参实现
 * - 短重载最终也会调到该 10 参方法；**只 hook 这一处**，避免半径被叠乘两次
 * - 颜色：setBlurBackground(isStatic, color, clipR) / HUN 直传 color
 *
 * 子开关「曲线联动」beautify（package:feature:beautify，默认关）：
 * - 关：线性半径（滑块×GAIN）+ 纯 alpha 遮罩，完全跟手
 * - 开：强度曲线、模糊↔罩色联动、日/夜轻微偏色
 *
 * 性能：prefs TTL 缓存、单点 hook、半径上限、堆叠 softCap。
 */
object NotificationCardBlurHook : FeatureHook {

    private const val FEATURE_KEY = "notification_card_blur"
    private const val NO_MASK_KEY = "notification_card_no_mask"
    private const val OPACITY_SUFFIX = "opacity"
    private const val BEAUTIFY_SUFFIX = "beautify"
    private const val TAG = "NotificationCardBlur"

    private const val DEFAULT_INTENSITY = 50
    private const val DEFAULT_OPACITY = 70
    /** 美化默认关：额外子开关，opt-in */
    private const val DEFAULT_BEAUTIFY = 0

    private const val INTENSITY_GAIN = 2f
    private const val RADIUS_CURVE_EXP = 0.88f
    private const val COUPLE_SPAN = 18f
    private const val DAY_TINT_MIX = 0.14f
    private const val NIGHT_TINT_MIX = 0.12f

    private const val MAX_LIVE_RADIUS = 240
    private const val MAX_LIVE_RADIUS_STACKED = 180
    private const val PREFS_TTL_MS = 800L

    private const val BACKGROUND_VIEW =
        "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val LANDSCAPE_HUN_VIEW =
        "com.flyme.notification.view.LandscapeHeadsUpNotificationView"
    private const val MZ_BLUR_UTILS = "com.flyme.systemui.utils.MzBlurUtils"

    private var prefsPackage: String = "com.android.systemui"
    private var loadParam: XC_LoadPackage.LoadPackageParam? = null

    private var backgroundViewClass: Class<*>? = null
    private var landscapeHunClass: Class<*>? = null

    private var prefsCachedAt = 0L
    private var cachedIntensity = DEFAULT_INTENSITY
    private var cachedOpacityBias = DEFAULT_OPACITY
    private var cachedNoMask = false
    private var cachedBeautify = false
    /** intensity → 半径倍率（是否含曲线由 beautify 决定） */
    private var cachedRadiusMul = 1f

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        prefsPackage = packageName
        loadParam = lpparam
        try {
            backgroundViewClass = XposedHelpers.findClass(BACKGROUND_VIEW, lpparam.classLoader)
        } catch (_: Throwable) {
            backgroundViewClass = null
        }
        try {
            landscapeHunClass = XposedHelpers.findClass(LANDSCAPE_HUN_VIEW, lpparam.classLoader)
        } catch (_: Throwable) {
            landscapeHunClass = null
        }
        refreshPrefs(force = true)

        hookSetBlurBackground()
        hookMzBlurUtilsImpl()

        Logger.i(
            TAG,
            "已启用：通知卡片模糊 beautify=$cachedBeautify " +
                "intensity=$cachedIntensity opacity=$cachedOpacityBias " +
                "cap=$MAX_LIVE_RADIUS/stack=$MAX_LIVE_RADIUS_STACKED"
        )
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
                        if (cachedNoMask) return
                        val original = param.args[1] as? Int ?: return
                        val adjusted = applyMaskColor(original)
                        if (adjusted != original) {
                            param.args[1] = adjusted
                            Logger.once(
                                TAG,
                                "color_b${cachedBeautify}_${cachedIntensity}_$cachedOpacityBias",
                                "罩色 ${Integer.toHexString(original)}→${Integer.toHexString(adjusted)}"
                            )
                        }
                    }
                }
            )
            Logger.i(TAG, "已挂载 setBlurBackground")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 setBlurBackground 失败", e)
        }
    }

    /**
     * 只 hook 真正干活的 10 参实现：
     * (View, int, float, float, int, boolean, int, int, Function0, Consumer)
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
                    if (!isNotificationCardBlurView(view)) return

                    refreshPrefs(force = false)

                    val originalRadius = param.args[1] as? Int ?: return
                    if (originalRadius > 0) {
                        val scaled = scaleLiveRadius(originalRadius)
                        if (scaled != originalRadius) {
                            param.args[1] = scaled
                            Logger.once(
                                TAG,
                                "radius_b${cachedBeautify}_$cachedIntensity",
                                "radius $originalRadius→$scaled (mul=$cachedRadiusMul)"
                            )
                        }
                    }

                    if (!cachedNoMask && isDirectColorBlurView(view)) {
                        val color = param.args[4] as? Int ?: return
                        val adjusted = applyMaskColor(color)
                        if (adjusted != color) {
                            param.args[4] = adjusted
                        }
                    }
                }
            })
            Logger.i(TAG, "已挂载 MzBlurUtils 10 参实现")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MzBlurUtils 失败", e)
        }
    }

    private fun isNotificationCardBlurView(view: View): Boolean {
        val bg = backgroundViewClass
        if (bg != null && bg.isInstance(view)) return true
        val hun = landscapeHunClass
        return hun != null && hun.isInstance(view)
    }

    private fun isDirectColorBlurView(view: View): Boolean {
        val hun = landscapeHunClass
        return hun != null && hun.isInstance(view)
    }

    private fun refreshPrefs(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - prefsCachedAt < PREFS_TTL_MS) return
        prefsCachedAt = now
        val lp = loadParam ?: return
        cachedIntensity = XposedPrefs.getFeatureValue(
            lp, prefsPackage, FEATURE_KEY, DEFAULT_INTENSITY
        ).coerceIn(0, 100)
        cachedOpacityBias = XposedPrefs.getFeatureExtraValue(
            lp, prefsPackage, FEATURE_KEY, OPACITY_SUFFIX, DEFAULT_OPACITY
        ).coerceIn(0, 100)
        cachedBeautify = XposedPrefs.getFeatureExtraValue(
            lp, prefsPackage, FEATURE_KEY, BEAUTIFY_SUFFIX, DEFAULT_BEAUTIFY
        ) == 1
        cachedNoMask = XposedPrefs.isFeatureEnabled(lp, prefsPackage, NO_MASK_KEY)
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
        val cap = if (IosNotificationStackHook.stackBlurSoftCapActive) {
            MAX_LIVE_RADIUS_STACKED
        } else {
            MAX_LIVE_RADIUS
        }
        return (original * cachedRadiusMul).roundToInt().coerceIn(0, cap)
    }

    /**
     * 堆叠进入/离开折叠滑动时调用：直接改已挂上的 BackgroundBlurDrawable 半径。
     * 仅在 softCap 边沿触发，不进每帧热路径。
     */
    fun onStackSoftCapChanged(rows: List<View>, softCap: Boolean) {
        refreshPrefs(force = false)
        val target = if (softCap) {
            scaleLiveRadius(180).coerceAtMost(MAX_LIVE_RADIUS_STACKED)
        } else {
            scaleLiveRadius(180).coerceAtMost(MAX_LIVE_RADIUS)
        }
        var n = 0
        for (row in rows) {
            if (applyRadiusToRowBackgrounds(row, target)) n++
        }
        if (n > 0) {
            Logger.d(TAG) {
                "stackSoftCap=$softCap → radius=$target on $n rows"
            }
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
        if (!cachedBeautify) {
            return cachedOpacityBias
        }
        val couple = (50f - cachedIntensity) / 50f * COUPLE_SPAN
        return (cachedOpacityBias + couple).roundToInt().coerceIn(0, 100)
    }

    /** 关美化：只改 alpha；开美化：alpha 联动 + 日/夜 tint */
    private fun applyMaskColor(color: Int): Int {
        if (color == Color.TRANSPARENT) return color

        val alpha = (255 * effectiveOpacityPercent() / 100f).roundToInt().coerceIn(0, 255)
        if (!cachedBeautify) {
            return Color.argb(
                alpha,
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
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
