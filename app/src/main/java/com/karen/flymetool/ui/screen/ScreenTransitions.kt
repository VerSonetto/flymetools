package com.karen.flymetool.ui.screen

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * 页面 / Tab 切换共用动效：iOS 风格左右平推。
 *
 * 完全复刻 UINavigationController 的 push / pop 观感：
 * - 前进：新页面从屏幕右侧 100% 宽度滑入，旧页面同时向左滑出约 1/3 宽度（视差）；
 * - 返回：栈顶页面向右 100% 宽度滑出，露出下层页面自左侧 1/3 处滑回；
 * - 全程无淡入淡出，页面以不透明状态平推；曲线用 iOS 导航默认的 easeInOutCubic。
 */
object ScreenTransitions {

    /** push / pop 动画时长，与 iOS 导航转场（约 0.3s）一致 */
    const val DURATION_MS = 320

    /** Tab 同级切换动画时长，比 push / pop 更轻快 */
    const val TAB_DURATION_MS = 260

    /** 被推开的页面只滑出约 1/3 宽度，形成 iOS 标志性视差层次 */
    const val PARALLAX_DIVISOR = 3

    /** iOS 导航转场曲线：easeInOutCubic（cubic-bezier(0.4, 0, 0.2, 1)） */
    val Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 前进：新页面自右侧全宽滑入（iOS push） */
    fun enterFromEnd() = slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(DURATION_MS, easing = Easing)
    )

    /** 前进：旧页面向左滑出 1/3 宽度，停在左侧露出视差（iOS push） */
    fun exitToStart() = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / PARALLAX_DIVISOR },
        animationSpec = tween(DURATION_MS, easing = Easing)
    )

    /** 返回：下层页面自左侧 1/3 处滑回原位（iOS pop） */
    fun enterFromStart() = slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / PARALLAX_DIVISOR },
        animationSpec = tween(DURATION_MS, easing = Easing)
    )

    /** 返回：栈顶页面向右全宽滑出（iOS pop） */
    fun exitToEnd() = slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(DURATION_MS, easing = Easing)
    )

    /**
     * Tab 切换：同级页面间轻量平推，按 ordinal 方向滑动 1/4 宽度，
     * 幅度小于 push / pop，时长更短，与 iOS 应用内同级切换观感一致。
     */
    fun tabContentTransform(forward: Boolean): ContentTransform {
        val slideFraction = 4
        return if (forward) {
            (
                slideInHorizontally(
                    initialOffsetX = { it / slideFraction },
                    animationSpec = tween(TAB_DURATION_MS, easing = Easing)
                )
            ) togetherWith (
                slideOutHorizontally(
                    targetOffsetX = { -it / slideFraction },
                    animationSpec = tween(TAB_DURATION_MS, easing = Easing)
                )
            )
        } else {
            (
                slideInHorizontally(
                    initialOffsetX = { -it / slideFraction },
                    animationSpec = tween(TAB_DURATION_MS, easing = Easing)
                )
            ) togetherWith (
                slideOutHorizontally(
                    targetOffsetX = { it / slideFraction },
                    animationSpec = tween(TAB_DURATION_MS, easing = Easing)
                )
            )
        }
    }
}
