package com.karen.flymetool.hook.feature.systemui

import android.graphics.Color
import android.os.SystemClock
import android.view.View
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.HookContext
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.Reflect
import com.karen.flymetool.util.FlymeVersionUtils
import com.karen.flymetool.util.NotificationCardBlurMath
import io.github.libxposed.api.XposedInterface

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
 * 映射算法见 [NotificationCardBlurMath]（UI 预览共用）。
 * 强度：0..[NotificationCardBlurMath.MAX_INTENSITY]，>100 时半径上限随强度延伸
 * （100 → 240，上限 → [NotificationCardBlurMath.MAX_LIVE_RADIUS_AT_MAX]）。
 */
object NotificationCardBlurHook : FeatureHook {

    private const val FEATURE_KEY = "notification_card_blur"
    private const val OPACITY_SUFFIX = "opacity"
    private const val BEAUTIFY_SUFFIX = "beautify"
    private const val TAG = "NotificationCardBlur"

    private const val DEFAULT_INTENSITY = NotificationCardBlurMath.DEFAULT_INTENSITY
    private const val DEFAULT_OPACITY = NotificationCardBlurMath.DEFAULT_OPACITY
    private const val DEFAULT_BEAUTIFY = 0

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

    /** 胶囊要比卡片本体再亮/再实一点，避免模糊开启后完全融进卡片。 */
    private const val PILL_ALPHA_BOOST = 25

    private var prefsPackage: String = "com.android.systemui"
    private var loadParam: HookContext? = null

    private var backgroundViewClass: Class<*>? = null
    private var landscapeHunClass: Class<*>? = null
    private var mediaCarouselClass: Class<*>? = null

    private var prefsCachedAt = 0L
    private var cachedIntensity = DEFAULT_INTENSITY
    private var cachedOpacityBias = DEFAULT_OPACITY
    private var cachedBeautify = false

    override fun handle(ctx: HookContext) {
        if (ctx.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!ctx.featureEnabled(FEATURE_KEY)) return

        prefsPackage = ctx.packageName
        loadParam = ctx
        backgroundViewClass = findClassOrNull(BACKGROUND_VIEW)
        landscapeHunClass = findClassOrNull(LANDSCAPE_HUN_VIEW)
        mediaCarouselClass = findClassOrNull(MEDIA_CAROUSEL_VIEW)
        refreshPrefs(force = true)

        hookSetBlurBackground(ctx.api)
        hookMzBlurUtilsImpl(ctx.api)
        hookMediaStaticForegroundColor(ctx.api)

        Logger.i(
            TAG,
            "已启用：通知/媒体模糊 beautify=$cachedBeautify " +
                "intensity=$cachedIntensity opacity=$cachedOpacityBias " +
                "media=${mediaCarouselClass != null}"
        )
    }

    private fun findClassOrNull(name: String): Class<*>? {
        return try {
            Reflect.findClass(name, loadParam!!.classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookSetBlurBackground(api: XposedInterface) {
        val bgCl = backgroundViewClass ?: return
        try {
            Reflect.hookMethodOn(
                api,
                bgCl,
                "setBlurBackground",
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ) { chain ->
                refreshPrefs(force = false)
                val array = chain.getArgs().toMutableList()
                val original = array[1] as? Int ?: return@hookMethodOn chain.proceed()
                val adjusted = applyMaskColor(original)
                if (adjusted != original) {
                    array[1] = adjusted
                    Logger.once(
                        TAG,
                        "notif_color_${cachedIntensity}_$cachedOpacityBias",
                        "通知罩色 ${Integer.toHexString(original)}→${Integer.toHexString(adjusted)}"
                    )
                }
                chain.proceed(array.toTypedArray())
            }
            Logger.i(TAG, "已挂载 NotificationBackgroundView.setBlurBackground")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 setBlurBackground 失败", e)
        }
    }

    /**
     * 10 参实现：通知背景 / HUN / 媒体卡片 共用。
     * 媒体与 HUN 的 color 都走 args[4]，不经 setBlurBackground。
     */
    private fun hookMzBlurUtilsImpl(api: XposedInterface) {
        try {
            val blurUtilsClass = Reflect.findClass(MZ_BLUR_UTILS, loadParam!!.classLoader)
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

            Reflect.hookMethod(api, impl) { chain ->
                val array = chain.getArgs().toMutableList()
                val view = array[0] as? View ?: return@hookMethod chain.proceed()
                if (!isTargetBlurView(view)) return@hookMethod chain.proceed()

                refreshPrefs(force = false)

                val originalRadius = array[1] as? Int ?: return@hookMethod chain.proceed()
                if (originalRadius > 0) {
                    val scaled = scaleLiveRadius(originalRadius)
                    if (scaled != originalRadius) {
                        array[1] = scaled
                        Logger.once(
                            TAG,
                            "radius_${view.javaClass.simpleName}_$cachedIntensity",
                            "radius $originalRadius→$scaled view=${view.javaClass.simpleName}"
                        )
                    }
                }

                // 直传 color：HUN、媒体；通知背景色已在 setBlurBackground 处理
                if (isDirectColorBlurView(view)) {
                    val color = array[4] as? Int ?: return@hookMethod chain.proceed()
                    val adjusted = applyMaskColorForView(color, view)
                    if (adjusted != color) {
                        array[4] = adjusted
                    }
                }
                chain.proceed(array.toTypedArray())
            }
            Logger.i(TAG, "已挂载 MzBlurUtils 10 参（通知+HUN+媒体）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MzBlurUtils 失败", e)
        }
    }

    /**
     * 媒体 Static：WallpaperBlurDrawableManager.setAllForegroundColor(View, int)
     * 与 addBlurDrawableTo(View, int color, float r) 的 color 参数。
     */
    private fun hookMediaStaticForegroundColor(api: XposedInterface) {
        try {
            val mgr = Reflect.findClass(WALLPAPER_BLUR_MANAGER, loadParam!!.classLoader)

            Reflect.hookMethodOn(
                api,
                mgr,
                "setAllForegroundColor",
                View::class.java,
                Int::class.javaPrimitiveType!!,
            ) { chain ->
                val array = chain.getArgs().toMutableList()
                val view = array[0] as? View ?: return@hookMethodOn chain.proceed()
                if (!isMediaCarousel(view) && !isMediaPill(view)) return@hookMethodOn chain.proceed()
                refreshPrefs(force = false)
                val original = array[1] as? Int ?: return@hookMethodOn chain.proceed()
                val adjusted = applyMaskColorForView(original, view)
                if (adjusted != original) {
                    array[1] = adjusted
                    Logger.once(
                        TAG,
                        "media_static_fg_$cachedOpacityBias",
                        "媒体 Static 罩色 ${Integer.toHexString(original)}→${Integer.toHexString(adjusted)}"
                    )
                }
                chain.proceed(array.toTypedArray())
            }

            // addBlurDrawableTo(View, int color, float radius) 与 5 参圆角版
            for (m in mgr.declaredMethods) {
                if (m.isSynthetic || m.name != "addBlurDrawableTo") continue
                val pts = m.parameterTypes
                if (pts.isEmpty() || !View::class.java.isAssignableFrom(pts[0])) continue
                if (pts.size < 2 || pts[1] != Int::class.javaPrimitiveType) continue
                Reflect.hookMethod(api, m) { chain ->
                    val array = chain.getArgs().toMutableList()
                    val view = array[0] as? View ?: return@hookMethod chain.proceed()
                    if (!isMediaCarousel(view) && !isMediaPill(view)) return@hookMethod chain.proceed()
                    refreshPrefs(force = false)
                    val original = array[1] as? Int ?: return@hookMethod chain.proceed()
                    val adjusted = applyMaskColorForView(original, view)
                    if (adjusted != original) array[1] = adjusted
                    chain.proceed(array.toTypedArray())
                }
            }
            Logger.i(TAG, "已挂载媒体 Static 壁纸模糊罩色")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载媒体 Static 罩色失败", e)
        }
    }

    private fun isTargetBlurView(view: View): Boolean {
        return isNotificationBackground(view) ||
            isLandscapeHun(view) ||
            isMediaCarousel(view) ||
            isMediaPill(view)
    }

    /** color 不经 setBlurBackground、直接进 MzBlurUtils / Static manager */
    private fun isDirectColorBlurView(view: View): Boolean {
        return isLandscapeHun(view) || isMediaCarousel(view) || isMediaPill(view)
    }

    /** 媒体卡片紧凑布局注入的底部胶囊背景，同样属于媒体卡片模糊的一部分。 */
    private fun isMediaPill(view: View): Boolean = MediaCardCompactHook.isPillView(view)

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
        cachedIntensity = lp.featureValue(
            FEATURE_KEY, DEFAULT_INTENSITY
        ).coerceIn(0, NotificationCardBlurMath.MAX_INTENSITY)
        cachedOpacityBias = lp.featureExtraValue(
            FEATURE_KEY, OPACITY_SUFFIX, DEFAULT_OPACITY
        ).coerceIn(0, 100)
        cachedBeautify = lp.featureExtraValue(
            FEATURE_KEY, BEAUTIFY_SUFFIX, DEFAULT_BEAUTIFY
        ) == 1
    }

    private fun scaleLiveRadius(original: Int): Int {
        return NotificationCardBlurMath.scaleLiveRadius(
            original = original,
            intensityPercent = cachedIntensity,
            beautify = cachedBeautify,
            stackSoftCap = IosNotificationStackHook.stackBlurSoftCapActive
        )
    }

    /**
     * 堆叠 softCap 边沿：压/恢复已创建 BackgroundBlurDrawable 半径。
     * rows 可为通知 row；媒体若在列表中也会尝试 row 自身 background。
     */
    fun onStackSoftCapChanged(api: XposedInterface, rows: List<View>, softCap: Boolean) {
        refreshPrefs(force = false)
        // cap 内部已按 stackBlurSoftCapActive 区分（180/240 基准 + 强度>100 延伸）
        val target = scaleLiveRadius(180)
        var n = 0
        for (row in rows) {
            if (applyRadiusToRowBackgrounds(api, row, target)) n++
            // 媒体卡片紧凑布局的胶囊也跟随堆叠 softCap 一起压/恢复
            try {
                MediaCardCompactHook.refreshPillBackgrounds(api, row)
            } catch (_: Throwable) {
            }
        }
        if (n > 0) {
            Logger.d(TAG) { "stackSoftCap=$softCap → radius=$target on $n rows" }
        }
    }

    private fun applyRadiusToRowBackgrounds(api: XposedInterface, row: View, radius: Int): Boolean {
        var touched = false
        for (field in arrayOf("mBackgroundFlyme", "mBackgroundNormal")) {
            try {
                val bg = Reflect.getObjectField(row, field) as? View ?: continue
                if (setBlurRadiusOnViewBackground(api, bg, radius)) touched = true
            } catch (_: Throwable) {
            }
        }
        if (setBlurRadiusOnViewBackground(api, row, radius)) touched = true
        return touched
    }

    private fun setBlurRadiusOnViewBackground(api: XposedInterface, view: View, radius: Int): Boolean {
        return try {
            val d = view.background ?: return false
            if (!d.javaClass.name.endsWith("BackgroundBlurDrawable")) return false
            Reflect.callMethod(api, d, "setBlurRadius", radius)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyMaskColor(color: Int): Int {
        return NotificationCardBlurMath.applyMaskColor(
            color = color,
            intensityPercent = cachedIntensity,
            opacityBias = cachedOpacityBias,
            beautify = cachedBeautify
        )
    }

    /** 胶囊在统一模糊映射基础上再提高一点 alpha，保持可见的按钮分组边界。 */
    private fun applyMaskColorForView(color: Int, view: View): Int {
        val adjusted = applyMaskColor(color)
        if (!isMediaPill(view)) return adjusted
        val alpha = (Color.alpha(adjusted) + PILL_ALPHA_BOOST).coerceIn(0, 255)
        return Color.argb(alpha, Color.red(adjusted), Color.green(adjusted), Color.blue(adjusted))
    }
}