package com.karen.flymetool.hook.feature.systemui

import android.graphics.Color
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
 * 通知卡片模糊强度 / 遮罩不透明度（Flyme 12）。
 *
 * 反编译依据：
 * - Live：NotificationBackgroundView.setBlurBgForLive →
 *   MzBlurUtils.setBackgroundBlurDrawable(view, blurRadius=180, clipR, color, …)
 * - 颜色入口：ActivatableNotificationView / GroupCollapse → setBlurBackground(isStatic, color, clipR)
 *   日间 0xB2FFFFFF / 夜间 0xB21A1A1A（alpha≈70%）
 * - HUN 横屏：LandscapeHeadsUpNotificationView 直接调 MzBlurUtils（同样 radius=180）
 *
 * Static 壁纸预模糊（WallpaperBlurDrawableManager）为多组件共享位图，不在此改半径。
 * 不改 BackgroundBlurDrawable 自身 fade alpha（系统用其做面板/控制中心互斥）。
 */
object NotificationCardBlurHook : FeatureHook {

    private const val FEATURE_KEY = "notification_card_blur"
    private const val NO_MASK_KEY = "notification_card_no_mask"
    private const val OPACITY_SUFFIX = "opacity"
    private const val TAG = "NotificationCardBlur"

    /**
     * 滑块 0–100 映射到约 0–200% 系统强度（见 [INTENSITY_GAIN]）。
     * 默认 50 ≈ 系统原样；100 ≈ 系统两倍。
     */
    private const val DEFAULT_INTENSITY = 50
    private const val DEFAULT_OPACITY = 70

    /**
     * 滑块仍为 0–100，实际模糊半径按此增益放大。
     * 1f = 系统原样；2f = 滑块 100% 约等于系统 200% 强度。
     */
    private const val INTENSITY_GAIN = 2f

    private const val BACKGROUND_VIEW =
        "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val LANDSCAPE_HUN_VIEW =
        "com.flyme.notification.view.LandscapeHeadsUpNotificationView"
    private const val MZ_BLUR_UTILS = "com.flyme.systemui.utils.MzBlurUtils"

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        hookSetBlurBackground(lpparam, packageName)
        hookMzBlurUtilsLiveRadius(lpparam, packageName)

        Logger.i(TAG, "已启用：通知卡片模糊强度/不透明度")
    }

    /**
     * setBlurBackground(boolean isStatic, int color, int clipCornerRadius)
     * 改 color 的 alpha，保留 RGB（日/夜底色）。
     * 若同时开了「去除遮罩」，颜色由 MaskHook 置透明，这里不再改 color。
     */
    private fun hookSetBlurBackground(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                BACKGROUND_VIEW,
                lpparam.classLoader,
                "setBlurBackground",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (XposedPrefs.isFeatureEnabled(lpparam, packageName, NO_MASK_KEY)) {
                            return
                        }
                        val opacity = readOpacity(lpparam, packageName)
                        val original = param.args[1] as? Int ?: return
                        val adjusted = applyOpacity(original, opacity)
                        if (adjusted != original) {
                            param.args[1] = adjusted
                            Logger.once(
                                TAG,
                                "color_$opacity",
                                "遮罩不透明度=$opacity% color=${Integer.toHexString(original)}→${Integer.toHexString(adjusted)}"
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
     * MzBlurUtils.setBackgroundBlurDrawable(...) 全量重载：
     * (View, int radius, float corner, float clipCorner, int color, boolean debug,
     *  int alpha, int z, Function0, Consumer)
     * 以及较短重载。按参数类型特征匹配，避免依赖混淆名以外的脆弱点。
     *
     * 仅处理通知卡片相关 View，避免误伤控制中心等其它调用方。
     */
    private fun hookMzBlurUtilsLiveRadius(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String
    ) {
        try {
            val blurUtilsClass = XposedHelpers.findClass(MZ_BLUR_UTILS, lpparam.classLoader)
            var hooked = 0
            for (method in blurUtilsClass.declaredMethods) {
                if (method.isSynthetic) continue
                if (method.name != "setBackgroundBlurDrawable") continue
                if (method.returnType != Void.TYPE) continue
                val params = method.parameterTypes
                if (params.isEmpty() || !View::class.java.isAssignableFrom(params[0])) continue
                if (params.size < 2 || params[1] != Int::class.javaPrimitiveType) continue

                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        if (!isNotificationCardBlurView(view)) return

                        val intensity = readIntensity(lpparam, packageName)
                        val originalRadius = param.args[1] as? Int ?: return
                        val scaled = scaleLiveRadius(originalRadius, intensity)
                        if (scaled != originalRadius) {
                            param.args[1] = scaled
                            val effective = (intensity * INTENSITY_GAIN).toInt()
                            Logger.once(
                                TAG,
                                "radius_$intensity",
                                "模糊强度滑块=$intensity%(≈系统${effective}%) radius=$originalRadius→$scaled view=${view.javaClass.simpleName}"
                            )
                        }

                        // NotificationBackgroundView 的 color 已在 setBlurBackground 改过；
                        // 仅 HUN 等直调 MzBlurUtils 的路径在此改 color，避免 alpha 叠乘两次。
                        if (params.size >= 5 &&
                            params[4] == Int::class.javaPrimitiveType &&
                            isDirectColorBlurView(view) &&
                            !XposedPrefs.isFeatureEnabled(lpparam, packageName, NO_MASK_KEY)
                        ) {
                            val opacity = readOpacity(lpparam, packageName)
                            val color = param.args[4] as? Int ?: return
                            val adjusted = applyOpacity(color, opacity)
                            if (adjusted != color) {
                                param.args[4] = adjusted
                            }
                        }
                    }
                })
                hooked++
            }
            if (hooked == 0) {
                throw NoSuchMethodException("未找到 setBackgroundBlurDrawable(View, int, …)")
            }
            Logger.i(TAG, "已挂载 MzBlurUtils.setBackgroundBlurDrawable ×$hooked")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MzBlurUtils Live 半径失败", e)
        }
    }

    private fun isNotificationCardBlurView(view: View): Boolean {
        return hasClassInHierarchy(view, BACKGROUND_VIEW) ||
            hasClassInHierarchy(view, LANDSCAPE_HUN_VIEW)
    }

    /** 不经过 setBlurBackground、直接把 color 传给 MzBlurUtils 的通知 View */
    private fun isDirectColorBlurView(view: View): Boolean {
        return hasClassInHierarchy(view, LANDSCAPE_HUN_VIEW)
    }

    private fun hasClassInHierarchy(view: View, className: String): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null && cls != Any::class.java) {
            if (cls.name == className) return true
            cls = cls.superclass
        }
        return false
    }

    private fun readIntensity(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String
    ): Int = XposedPrefs.getFeatureValue(
        lpparam, packageName, FEATURE_KEY, DEFAULT_INTENSITY
    ).coerceIn(0, 100)

    private fun readOpacity(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String
    ): Int = XposedPrefs.getFeatureExtraValue(
        lpparam, packageName, FEATURE_KEY, OPACITY_SUFFIX, DEFAULT_OPACITY
    ).coerceIn(0, 100)

    private fun scaleLiveRadius(original: Int, intensityPercent: Int): Int {
        // 滑块 0–100 → 实际 0–(100*GAIN)% 系统强度；常见 original=180
        return (original * intensityPercent / 100f * INTENSITY_GAIN)
            .toInt()
            .coerceAtLeast(0)
    }

    private fun applyOpacity(color: Int, opacityPercent: Int): Int {
        if (color == Color.TRANSPARENT) return color
        val alpha = (255 * opacityPercent / 100f).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
