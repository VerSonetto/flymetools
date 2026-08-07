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
 * 去除通知 / 媒体卡片厚罩色 → 官方 PluginBlurView 薄玻璃 0x1A…，保留模糊。
 *
 * 深浅色切换丢模糊（不重建 drawable，避免崩 SystemUI）：
 * - detach 后 mTotalAlpha=-100；reattach / setBlurBgForLive 会把 Live alpha 写成 0
 * - 仅把传入 MzBlurUtils 的 alpha==0 改为 255（控制中心 hide 用 1，不动）
 * - 并在 setBlurBackground 前把 NotificationBackgroundView.mTotalAlpha 从 -100 拉回
 */
object NotificationCardMaskHook : FeatureHook {

    private const val FEATURE_KEY = "notification_card_no_mask"
    private const val TAG = "NotificationCardMask"

    private const val BACKGROUND_VIEW =
        "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val LANDSCAPE_HUN_VIEW =
        "com.flyme.notification.view.LandscapeHeadsUpNotificationView"
    private const val MEDIA_CAROUSEL_VIEW =
        "com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout"
    private const val MZ_BLUR_UTILS = "com.flyme.systemui.utils.MzBlurUtils"
    private const val WALLPAPER_BLUR_MANAGER =
        "com.flyme.systemui.wallpaper.WallpaperBlurDrawableManager"

    private const val OFFICIAL_PLUGIN_GLASS = 0x1AFFFFFF
    private const val OFFICIAL_PLUGIN_GLASS_NIGHT = 0x1A1A1A1A

    private var mediaCarouselClass: Class<*>? = null
    private var landscapeHunClass: Class<*>? = null
    private var backgroundViewClass: Class<*>? = null

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

        backgroundViewClass = findClassOrNull(BACKGROUND_VIEW, lpparam)
        mediaCarouselClass = findClassOrNull(MEDIA_CAROUSEL_VIEW, lpparam)
        landscapeHunClass = findClassOrNull(LANDSCAPE_HUN_VIEW, lpparam)

        hookNotificationSetBlurBackground(lpparam)
        hookMzBlurUtilsLive(lpparam)
        hookMediaStaticForeground(lpparam)
        hookInitTotalAlphaOnAttach(lpparam)

        Logger.i(TAG, "已启用：薄玻璃罩（安全路径，无强制重建） media=${mediaCarouselClass != null}")
    }

    private fun findClassOrNull(
        name: String,
        lpparam: XC_LoadPackage.LoadPackageParam
    ): Class<*>? = try {
        XposedHelpers.findClass(name, lpparam.classLoader)
    } catch (_: Throwable) {
        null
    }

    private fun hookNotificationSetBlurBackground(lpparam: XC_LoadPackage.LoadPackageParam) {
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
                        // 主题切换后 mTotalAlpha 常为 -100 → Live 写 alpha=0
                        ensureTotalAlphaReady(param.thisObject)

                        val original = param.args[1] as? Int ?: return
                        val glass = officialThinGlass(original)
                        if (glass != original) {
                            param.args[1] = glass
                            Logger.once(
                                TAG,
                                "notif_glass",
                                "通知 ${Integer.toHexString(original)}→${Integer.toHexString(glass)}"
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
     * Live 10 参：只改 color / 修正 alpha==0，绝不改 Function0、不 clear background。
     */
    private fun hookMzBlurUtilsLive(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val blurCl = XposedHelpers.findClass(MZ_BLUR_UTILS, lpparam.classLoader)
            val impl = blurCl.declaredMethods.singleOrNull { m ->
                !m.isSynthetic &&
                    m.name == "setBackgroundBlurDrawable" &&
                    m.returnType == Void.TYPE &&
                    m.parameterTypes.size == 10 &&
                    m.parameterTypes[0] == View::class.java &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[4] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[6] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[8].name.endsWith("Function0") &&
                    m.parameterTypes[9].name.endsWith("Consumer")
            } ?: throw NoSuchMethodException("10 参 setBackgroundBlurDrawable")

            XposedBridge.hookMethod(impl, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args[0] as? View ?: return
                    if (!isTargetView(view)) return

                    ensureTotalAlphaReady(view)

                    val originalColor = param.args[4] as? Int ?: return
                    val glass = officialThinGlass(originalColor)
                    if (glass != originalColor) {
                        param.args[4] = glass
                    }

                    // 仅修正「未初始化」写成的 0；控制中心 hide 用 1，不要动
                    val alpha = param.args[6] as? Int ?: return
                    if (alpha == 0) {
                        param.args[6] = 255
                        Logger.once(TAG, "alpha0_fix", "Live alpha 0→255")
                    }
                }
            })
            Logger.i(TAG, "已挂载 MzBlurUtils Live（仅 color/alpha）")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载 MzBlurUtils 失败", e)
        }
    }

    /**
     * attach 时若 mBlurColor!=0 会 setBlurBackground + updateLiveBlurAlpha(mTotalAlpha)，
     * 而 detach 把 mTotalAlpha 置 -100，updateLiveBlurAlpha 直接 return，糊会停在 alpha=0。
     * 在 onAttachedToWindow 开头先恢复 mTotalAlpha。
     */
    private fun hookInitTotalAlphaOnAttach(lpparam: XC_LoadPackage.LoadPackageParam) {
        val targets = listOfNotNull(backgroundViewClass, mediaCarouselClass)
        for (cl in targets) {
            try {
                XposedHelpers.findAndHookMethod(
                    cl,
                    "onAttachedToWindow",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            ensureTotalAlphaReady(param.thisObject)
                        }
                    }
                )
                Logger.i(TAG, "已挂载 ${cl.simpleName}.onAttachedToWindow 恢复 alpha")
            } catch (e: Throwable) {
                Logger.e(TAG, "挂载 ${cl.name} onAttachedToWindow 失败", e)
            }
        }
    }

    private fun hookMediaStaticForeground(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (mediaCarouselClass == null) return
        try {
            val mgr = XposedHelpers.findClass(WALLPAPER_BLUR_MANAGER, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                mgr,
                "setAllForegroundColor",
                View::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        if (!isMediaCarousel(view)) return
                        val original = param.args[1] as? Int ?: return
                        val glass = officialThinGlass(original)
                        if (glass != original) param.args[1] = glass
                    }
                }
            )

            for (m in mgr.declaredMethods) {
                if (m.isSynthetic || m.name != "addBlurDrawableTo") continue
                val pts = m.parameterTypes
                if (pts.isEmpty() || !View::class.java.isAssignableFrom(pts[0])) continue
                if (pts.size < 2 || pts[1] != Int::class.javaPrimitiveType) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        if (!isMediaCarousel(view)) return
                        val original = param.args[1] as? Int ?: return
                        val glass = officialThinGlass(original)
                        if (glass != original) param.args[1] = glass
                    }
                })
            }
            Logger.i(TAG, "已挂载媒体 Static 薄玻璃")
        } catch (e: Throwable) {
            Logger.e(TAG, "挂载媒体 Static 失败", e)
        }
    }

    /**
     * mTotalAlpha == -100 时系统 Live 会传 alpha=0。
     * 尽量按系统 initTotalAlpha 语义写成根透明度；失败则 255。
     */
    private fun ensureTotalAlphaReady(host: Any) {
        try {
            val cur = try {
                XposedHelpers.getIntField(host, "mTotalAlpha")
            } catch (_: Throwable) {
                return
            }
            if (cur != -100) return

            var next = 255
            if (host is View && host.isAttachedToWindow) {
                try {
                    // 对齐 SystemUICommonUtils.getAlphaFromRootView * 255
                    var v: View? = host
                    var a = 1f
                    var guard = 0
                    while (v != null && guard++ < 24) {
                        a *= v.alpha
                        val p = v.parent
                        v = p as? View
                    }
                    next = (a * 255f).toInt().coerceIn(1, 255)
                } catch (_: Throwable) {
                    next = 255
                }
            }
            XposedHelpers.setIntField(host, "mTotalAlpha", next)
            Logger.once(TAG, "total_alpha_init", "mTotalAlpha -100→$next")
        } catch (_: Throwable) {
        }
    }

    private fun isTargetView(view: View): Boolean {
        return isNotificationBackground(view) ||
            isMediaCarousel(view) ||
            isLandscapeHun(view)
    }

    private fun isNotificationBackground(view: View): Boolean {
        val cl = backgroundViewClass ?: return false
        return cl.isInstance(view)
    }

    private fun isMediaCarousel(view: View): Boolean {
        val cl = mediaCarouselClass ?: return false
        return cl.isInstance(view)
    }

    private fun isLandscapeHun(view: View): Boolean {
        val cl = landscapeHunClass ?: return false
        return cl.isInstance(view)
    }

    private fun officialThinGlass(original: Int): Int {
        if (original == Color.TRANSPARENT) return OFFICIAL_PLUGIN_GLASS
        val luminance =
            (Color.red(original) * 299 +
                Color.green(original) * 587 +
                Color.blue(original) * 114) / 1000
        return if (luminance < 80) OFFICIAL_PLUGIN_GLASS_NIGHT else OFFICIAL_PLUGIN_GLASS
    }
}
