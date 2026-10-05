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
        fun stackRelative(relative: Float, invertDepth: Boolean): Float =
            if (invertDepth) -relative else relative

        /** 一旦接管轴心就保持到会话清理，不能跨过中央便恢复入场时的远轴心。 */
        fun shouldCenterPivot(scaleFactor: Float, alreadyCentered: Boolean, settled: Boolean): Boolean =
            alreadyCentered || settled || abs(scaleFactor - 1f) > 0.0005f

        /** Android View 以 pivot 缩放后，显示中心相对布局中心的偏移（不包含 translation）。 */
        fun scaleCenterShift(size: Float, pivot: Float, scale: Float): Float =
            (size / 2f - pivot) * (scale - 1f)

        fun overscrollSinkScale(relative: Float, overscroll: Float): Float {
            if (overscroll <= 0f) return 1f
            val distance = (-relative).coerceAtLeast(0f)
            val weight = 0.3f + 0.7f * (distance / (distance + 0.8f))
            val damped = overscroll / (1f + overscroll * 0.8f)
            return 1f - damped * 0.15f * weight
        }
    }
}
