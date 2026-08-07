package com.karen.flymetool.hook.feature.systemui

import android.graphics.Color
import com.karen.flymetool.hook.base.FeatureHook
import com.karen.flymetool.hook.base.Logger
import com.karen.flymetool.hook.base.XposedPrefs
import com.karen.flymetool.util.FlymeVersionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 去除通知卡片厚罩色，改用系统其它组件的「薄玻璃」配色，保留 Live/Static 模糊。
 *
 * 反编译对照（非自创）：
 * - 通知默认罩：日 0xB2FFFFFF / 夜 0xB21A1A1A（约 70%）
 * - 锁屏插件毛玻璃 [PluginBlurView]：
 *     setBackgroundBlurDrawable(..., color = 452984831) → **0x1AFFFFFF**
 * - 系统资源 blur_background_cover_normal = #08000000（极薄黑罩）
 * - 音量面板跨窗模糊开启时 setColor(transparent)；但通知路径 alpha=0 会挖空 blur，
 *   故采用 PluginBlurView 同款 0x1AFFFFFF（官方已验证能出毛玻璃）。
 *
 * Static 路径同一 color 会进 WallpaperBlurDrawable foreground，与 Live 一致。
 */
object NotificationCardMaskHook : FeatureHook {

    private const val FEATURE_KEY = "notification_card_no_mask"
    private const val TAG = "NotificationCardMask"
    private const val BACKGROUND_VIEW =
        "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"

    /**
     * 与 [com.flyme.keyguard.plugin.PluginBlurView] Live/Static 前景色相同：
     * 452984831 == 0x1AFFFFFF（约 10% 白），官方薄玻璃罩，不是厚通知罩。
     */
    private const val OFFICIAL_PLUGIN_GLASS = 0x1AFFFFFF

    /**
     * 夜间：保持同 alpha 的深灰 RGB（对齐通知夜色 0x1A1A1A 通道），
     * 避免夜景上仍盖一层发白的 0x1AFFFFFF。
     */
    private const val OFFICIAL_PLUGIN_GLASS_NIGHT = 0x1A1A1A1A

    override fun handle(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        if (lpparam.packageName != "com.android.systemui") return
        if (!FlymeVersionUtils.isFlyme12()) return
        if (!XposedPrefs.isFeatureEnabled(lpparam, packageName, FEATURE_KEY)) return

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
                        val original = param.args[1] as? Int ?: return
                        val glass = officialThinGlass(original)
                        if (glass != original) {
                            param.args[1] = glass
                            Logger.once(
                                TAG,
                                "plugin_glass",
                                "官方薄玻璃 ${Integer.toHexString(original)}→${Integer.toHexString(glass)}"
                            )
                        }
                    }
                }
            )
            Logger.i(TAG, "已启用：通知罩色改为 PluginBlurView 官方薄玻璃 0x1A…")
        } catch (e: Throwable) {
            Logger.e(TAG, "Hook 失败", e)
        }
    }

    /**
     * 按原色明度选日/夜薄玻璃，alpha 固定为官方 PluginBlurView 的 0x1A。
     */
    private fun officialThinGlass(original: Int): Int {
        if (original == Color.TRANSPARENT) return OFFICIAL_PLUGIN_GLASS
        val r = Color.red(original)
        val g = Color.green(original)
        val b = Color.blue(original)
        val luminance = (r * 299 + g * 587 + b * 114) / 1000
        // 原通知夜色 0xB21A1A1A 偏暗 → 用薄深灰；否则用 PluginBlurView 同款薄白
        return if (luminance < 80) OFFICIAL_PLUGIN_GLASS_NIGHT else OFFICIAL_PLUGIN_GLASS
    }
}
