package com.karen.flymetool.hook.feature.launcher

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/** 每张卡片复用一个结果；只缓存闭式几何，不跳过原生动画属性的读回和同步。 */
internal class RecentsStackGeometry {
    var primaryCenter = 0f
        private set
    var scale = 1f
        private set
    var alpha = 1f
        private set

    private var lastCenter = Float.NaN
    private var lastSize = Float.NaN
    private var lastRelative = Float.NaN
    private var lastOverscroll = Float.NaN

    fun update(center: Float, size: Float, relative: Float, overscroll: Float): RecentsStackGeometry {
        if (center == lastCenter && size == lastSize && relative == lastRelative && overscroll == lastOverscroll) {
            return this
        }
        lastCenter = center
        lastSize = size
        lastRelative = relative
        lastOverscroll = overscroll

        val leftPeek = size * 0.24f
        val rightSpacing = size * 0.85f
        val base = if (relative <= 0f) {
            center - leftPeek * (1f - 0.28f.pow(-relative)) / (1f - 0.28f)
        } else {
            center + relative * rightSpacing
        }
        primaryCenter = if (abs(overscroll) <= 0.0005f) base else {
            val weight = if (overscroll < 0f) {
                val distance = relative.coerceAtLeast(0f)
                0.72f + 0.55f * (distance / (distance + 0.6f))
            } else {
                val distance = (-relative).coerceAtLeast(0f)
                0.65f / (distance + 1f)
            }
            val amount = abs(overscroll)
            val damped = amount / (1f + amount * 0.8f)
            base - overscroll.sign * damped * rightSpacing * weight
        }
        scale = RecentsDepthScale.at(relative) * overscrollSinkScale(relative, overscroll)
        alpha = when {
            relative >= -2f -> 1f
            relative <= -3f -> 0f
            else -> 3f + relative
        }
        return this
    }

    companion object {
        fun overscrollSinkScale(relative: Float, overscroll: Float): Float {
            if (overscroll <= 0f) return 1f
            val distance = (-relative).coerceAtLeast(0f)
            val weight = 0.3f + 0.7f * (distance / (distance + 0.8f))
            val damped = overscroll / (1f + overscroll * 0.8f)
            return 1f - damped * 0.15f * weight
        }
    }
}
