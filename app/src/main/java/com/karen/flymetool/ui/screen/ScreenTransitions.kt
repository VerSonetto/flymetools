package com.karen.flymetool.ui.screen

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * 页面 / Tab 切换共用动效：水平推入推出 + 轻淡入淡出，末段缓停。
 */
object ScreenTransitions {
    const val DURATION_MS = 380
    const val FADE_IN_MS = 240
    const val FADE_OUT_MS = 160
    /** 被推开的页面只滑出约 1/5 宽度，形成视差层次 */
    const val PARALLAX_DIVISOR = 5

    val Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    fun enterFromEnd() = slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(DURATION_MS, easing = Easing)
    ) + fadeIn(animationSpec = tween(FADE_IN_MS, easing = Easing))

    fun exitToStart() = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / PARALLAX_DIVISOR },
        animationSpec = tween(DURATION_MS, easing = Easing)
    ) + fadeOut(animationSpec = tween(FADE_OUT_MS, easing = Easing))

    fun enterFromStart() = slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / PARALLAX_DIVISOR },
        animationSpec = tween(DURATION_MS, easing = Easing)
    ) + fadeIn(animationSpec = tween(FADE_IN_MS, easing = Easing))

    fun exitToEnd() = slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(DURATION_MS, easing = Easing)
    ) + fadeOut(animationSpec = tween(FADE_OUT_MS, easing = Easing))

    /**
     * Tab 切换：按 ordinal 方向滑入滑出（幅度略小于全屏导航，更轻）。
     */
    fun tabContentTransform(forward: Boolean): ContentTransform {
        val slideFraction = 8
        return if (forward) {
            (
                slideInHorizontally(
                    initialOffsetX = { it / slideFraction },
                    animationSpec = tween(DURATION_MS, easing = Easing)
                ) + fadeIn(tween(FADE_IN_MS, easing = Easing))
            ) togetherWith (
                slideOutHorizontally(
                    targetOffsetX = { -it / slideFraction },
                    animationSpec = tween(DURATION_MS, easing = Easing)
                ) + fadeOut(tween(FADE_OUT_MS, easing = Easing))
            )
        } else {
            (
                slideInHorizontally(
                    initialOffsetX = { -it / slideFraction },
                    animationSpec = tween(DURATION_MS, easing = Easing)
                ) + fadeIn(tween(FADE_IN_MS, easing = Easing))
            ) togetherWith (
                slideOutHorizontally(
                    targetOffsetX = { it / slideFraction },
                    animationSpec = tween(DURATION_MS, easing = Easing)
                ) + fadeOut(tween(FADE_OUT_MS, easing = Easing))
            )
        }
    }
}
