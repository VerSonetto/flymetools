package com.karen.flymetool.hook.feature.launcher

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/**
 * Quickstep / Flyme 最近任务主轴契约（反编译对照，禁止再引入 logical↔physical 翻转）。
 *
 * ## Handler 对照表
 * | Handler   | rotation | 主轴 | primaryScroll | primaryTranslate | 次轴 | getRecentsRtlSetting      |
 * |-----------|----------|------|---------------|------------------|------|---------------------------|
 * | Portrait  | 0        | X    | scrollX       | TRANSLATE_X      | Y    | 正常 RTL                  |
 * | Landscape | 1        | Y    | scrollY       | TRANSLATE_Y      | X    | LTR 机强制 `!isRtl`=true  |
 * | Seascape  | 3        | Y    | scrollY       | TRANSLATE_Y      | X    | 正常 RTL                  |
 *
 * 主轴原语按反编译对照直接读 View 字段，不再走 handler 反射：
 * `getPrimaryScroll(view)`=`getScrollX/Y`、`getChildStart(view)`=`getLeft/Top`、
 * `getPrimarySize(view)`=`getWidth/Height`（三个 handler 实现均为纯字段透传，
 * 与 applyStack 原有的 handler 缺失回退路径同值，仅免掉每帧 Method.invoke）。
 * handler 仅保留在 `axisFor` 中用于解析 rotation。
 *
 * ## TaskView 位移通道
 * - 竖屏堆叠 offset：`getHorizontalOffsetTranslationProperty()` → 只进 `applyTranslationX`
 * - 横屏堆叠 offset：`getPrimaryTaskOffsetTranslationProperty()` → land/seascape 为
 *   `TASK_OFFSET_TRANSLATION_Y`（进 `applyTranslationY`）
 * - `horizontalOffsetTranslationX` **不会**进入 `applyTranslationY`，横屏绝不可误用
 *
 * ## 视口主轴公式（横竖统一）
 * ```
 * visualCenter = primaryViewportSize / 2
 * nativeCenter = childPrimaryStart + primarySize/2 - primaryScroll + nativeOffset
 * customOffset = stackPrimaryCenter(...) - nativeCenter
 * ```
 *
 * Landscape 的 pageScroll 方向已由 `getRecentsRtlSetting` 写入 `mPageScrolls` /
 * `getScrollForPage`，排序与插值直接使用该逻辑空间，不再二次符号翻转。
 */
internal data class RecentsAxis(
    val rotation: Int,
    val landscape: Boolean,
) {
    /**
     * Seascape(rotation=3) 下 pageScroll 升序对应屏幕偏左，而 iOS 堆叠要求：
     * - 右侧更高 Z
     * - 后方 peek / 缩小落在低 Z 侧（与正向横屏视觉一致）
     * 因此曲线在视觉空间采样（rel 取反），目标主轴坐标再镜像回 layout。
     * Portrait / Landscape 无需镜像。
     */
    val invertStackDepth: Boolean
        get() = rotation == 3

    fun primaryScroll(recents: ViewGroup): Float {
        return if (landscape) recents.scrollY.toFloat() else recents.scrollX.toFloat()
    }

    fun primaryViewportSize(recents: ViewGroup): Float {
        return if (landscape) recents.height.toFloat() else recents.width.toFloat()
    }

    fun childPrimaryStart(view: View): Float {
        return if (landscape) view.top.toFloat() else view.left.toFloat()
    }

    fun childPrimarySize(view: View): Float {
        return if (landscape) view.height.toFloat() else view.width.toFloat()
    }

    fun childSecondarySize(view: View): Float {
        return if (landscape) view.width.toFloat() else view.height.toFloat()
    }

    fun primaryTranslation(view: View): Float {
        return if (landscape) view.translationY else view.translationX
    }

    fun secondaryTranslation(view: View): Float {
        return if (landscape) view.translationX else view.translationY
    }

    fun setPrimaryTranslation(view: View, value: Float) {
        if (landscape) {
            view.translationY = value
        } else {
            view.translationX = value
        }
    }

    /** 标题遮挡等：主轴上的可见区间起点。 */
    fun boundsPrimaryStart(rect: Rect): Int {
        return if (landscape) rect.top else rect.left
    }

    fun boundsPrimaryEnd(rect: Rect): Int {
        return if (landscape) rect.bottom else rect.right
    }

    companion object {
        fun isLandscape(rotation: Int): Boolean = rotation == 1 || rotation == 3
    }
}
