package com.karen.flymetool.hook.feature.launcher

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/** 缩放随视觉深度递增，中央两侧连续，右侧前景卡保留大于中央卡的尺寸。 */
internal object RecentsDepthScale {
    private const val DEPTH_RANGE = 0.044f
    private const val DEPTH_DECAY = 0.5f

    fun at(relativePosition: Float): Float {
        val amount = DEPTH_RANGE * (1f - DEPTH_DECAY.pow(abs(relativePosition)))
        return 1f + amount * relativePosition.sign
    }

    /** 实时画面保持原生尺寸；整组使用同一基准，避免把单张运行卡强制置 1 后破坏层次。 */
    fun withLiveTileBaseline(depthScale: Float, runningDepthScale: Float): Float =
        depthScale / runningDepthScale
}
