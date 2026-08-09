package com.karen.flymetool.util

import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 通知 / 媒体卡片模糊参数映射（与 [com.karen.flymetool.hook.feature.systemui.NotificationCardBlurHook] 一致）。
 * UI 预览与 Hook 共用，避免两边手感对不齐。
 */
object NotificationCardBlurMath {

    const val DEFAULT_INTENSITY = 50
    const val DEFAULT_OPACITY = 70
    const val DEFAULT_BEAUTIFY = false

    /** 强度滑块上限（原 200，收至 140） */
    const val MAX_INTENSITY = 140

    /** 系统 Live 路径原始半径（NotificationBackgroundView / MediaCarousel） */
    const val BASE_LIVE_RADIUS = 180

    const val INTENSITY_GAIN = 2f
    const val RADIUS_CURVE_EXP = 0.88f
    const val COUPLE_SPAN = 18f
    const val DAY_TINT_MIX = 0.14f
    const val NIGHT_TINT_MIX = 0.12f
    const val OPACITY_EXP = 0.7f

    const val MAX_LIVE_RADIUS = 240
    /** 强度 = MAX_INTENSITY 时 Live 半径上限；斜率沿用 100→240、每 +1 强度 +2.4 */
    const val MAX_LIVE_RADIUS_AT_MAX = 336
    const val MAX_LIVE_RADIUS_STACKED = 180

    /** 系统日间罩色 #B2FFFFFF */
    const val SYSTEM_DAY_MASK = -1291845633

    /** 系统夜间罩色 #B21A1A1A */
    const val SYSTEM_NIGHT_MASK = -1306912230

    fun computeRadiusMul(intensityPercent: Int, beautify: Boolean): Float {
        if (intensityPercent <= 0) return 0f
        val t = intensityPercent.coerceIn(0, MAX_INTENSITY) / 100f
        return if (beautify) {
            t.toDouble().pow(RADIUS_CURVE_EXP.toDouble()).toFloat() * INTENSITY_GAIN
        } else {
            t * INTENSITY_GAIN
        }
    }

    fun liveRadiusCap(intensityPercent: Int, stackSoftCap: Boolean = false): Int {
        val intensity = intensityPercent.coerceIn(0, MAX_INTENSITY)
        val base = if (stackSoftCap) MAX_LIVE_RADIUS_STACKED else MAX_LIVE_RADIUS
        val span = (MAX_INTENSITY - 100).coerceAtLeast(1)
        val extra = (intensity - 100).coerceAtLeast(0) *
            (MAX_LIVE_RADIUS_AT_MAX - MAX_LIVE_RADIUS) / span
        return base + extra
    }

    fun scaleLiveRadius(
        original: Int,
        intensityPercent: Int,
        beautify: Boolean,
        stackSoftCap: Boolean = false
    ): Int {
        if (original <= 0) return 0
        val mul = computeRadiusMul(intensityPercent, beautify)
        if (mul <= 0f) return 0
        return (original * mul).roundToInt()
            .coerceIn(0, liveRadiusCap(intensityPercent, stackSoftCap))
    }

    /** 预览 / 生效统一：以系统 180 为基准的实际半径 */
    fun previewRadiusPx(intensityPercent: Int, beautify: Boolean): Int {
        return scaleLiveRadius(BASE_LIVE_RADIUS, intensityPercent, beautify)
    }

    fun effectiveOpacityPercent(
        intensityPercent: Int,
        opacityBias: Int,
        beautify: Boolean
    ): Int {
        val intensity = intensityPercent.coerceIn(0, MAX_INTENSITY)
        val bias = opacityBias.coerceIn(0, 100)
        val raw = if (!beautify) {
            bias
        } else {
            val couple = (50f - intensity) / 50f * COUPLE_SPAN
            (bias + couple).roundToInt().coerceIn(0, 100)
        }
        return (100f * (raw / 100f).toDouble().pow(OPACITY_EXP.toDouble()).toFloat())
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun applyMaskColor(
        color: Int,
        intensityPercent: Int,
        opacityBias: Int,
        beautify: Boolean
    ): Int {
        if (color == Color.TRANSPARENT) return color
        val alpha = (255 * effectiveOpacityPercent(intensityPercent, opacityBias, beautify) / 100f)
            .roundToInt()
            .coerceIn(0, 255)
        if (!beautify) {
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

    fun systemBaseMaskColor(night: Boolean): Int {
        return if (night) SYSTEM_NIGHT_MASK else SYSTEM_DAY_MASK
    }

    fun previewMaskColor(
        night: Boolean,
        intensityPercent: Int,
        opacityBias: Int,
        beautify: Boolean
    ): Int {
        return applyMaskColor(
            systemBaseMaskColor(night),
            intensityPercent,
            opacityBias,
            beautify
        )
    }

    private fun mixChannel(from: Int, to: Int, t: Float): Int {
        return (from + (to - from) * t).roundToInt().coerceIn(0, 255)
    }
}
