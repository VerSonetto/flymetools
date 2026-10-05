package com.karen.flymetool.hook.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RecentsStackGeometryTest {
    @Test
    fun keepsExistingPeekSpacingScaleAndFade() {
        val geometry = RecentsStackGeometry()
        val positions = floatArrayOf(-3f, -2f, -1f, 0f, 1f, 2f)
        val centers = floatArrayOf(67.3984f, 69.28f, 76f, 100f, 185f, 270f)
        val scales = floatArrayOf(0.9615f, 0.967f, 0.978f, 1f, 1.022f, 1.033f)
        for (index in positions.indices) {
            geometry.update(100f, 100f, positions[index], 0f)
            assertEquals(centers[index], geometry.primaryCenter, 0.0001f)
            assertEquals(scales[index], geometry.scale, 0.0001f)
            assertEquals(if (index == 0) 0f else 1f, geometry.alpha, 0f)
        }
        assertEquals(0.5f, geometry.update(100f, 100f, -2.5f, 0f).alpha, 0f)
    }

    @Test
    fun cacheTracksViewportRotationResizeSwipeAndOverscrollIndependently() {
        val cached = RecentsStackGeometry()
        // 入场 -> 滑动 -> 两端越界 -> 横屏换尺寸 -> 删除补位 -> 再次入场。
        val frames = listOf(
            floatArrayOf(540f, 820f, -1f, 0f),
            floatArrayOf(540f, 820f, -1.2f, 0f),
            floatArrayOf(540f, 820f, -1.2f, 0.4f),
            floatArrayOf(540f, 820f, -1.2f, -0.4f),
            floatArrayOf(1080f, 820f, -1.2f, -0.4f),
            floatArrayOf(1080f, 540f, -1.2f, -0.4f),
            floatArrayOf(1080f, 540f, -0.6f, 0f),
            floatArrayOf(540f, 820f, -1f, 0f),
        )
        for ((center, size, relative, overscroll) in frames) {
            val expected = RecentsStackGeometry().update(center, size, relative, overscroll)
            repeat(3) {
                assertSame(cached, cached.update(center, size, relative, overscroll))
                assertEquals(expected.primaryCenter, cached.primaryCenter, 0f)
                assertEquals(expected.scale, cached.scale, 0f)
                assertEquals(expected.alpha, cached.alpha, 0f)
            }
        }
    }

    @Test
    fun cardsRemainOrderedThroughoutSwipeAndMirroredSwipe() {
        val back = RecentsStackGeometry()
        val front = RecentsStackGeometry()
        for (direction in listOf(-1f, 1f)) {
            for (step in -300..300) {
                val relative = step / 100f * direction
                back.update(540f, 820f, relative, 0f)
                front.update(540f, 820f, relative + 1f, 0f)
                assertTrue(front.primaryCenter > back.primaryCenter)
                assertTrue(front.scale > back.scale)
                assertTrue(front.alpha >= back.alpha)
            }
        }
    }

    @Test
    fun overscrollRetainsDirectionAndDepthSink() {
        val geometry = RecentsStackGeometry()
        geometry.update(100f, 100f, 0f, 1f)
        assertEquals(69.30556f, geometry.primaryCenter, 0.0001f)
        assertEquals(0.975f, geometry.scale, 0.0001f)
        geometry.update(100f, 100f, 0f, -1f)
        assertEquals(134f, geometry.primaryCenter, 0.0001f)
        assertEquals(1f, geometry.scale, 0f)
    }

    @Test
    fun normalizedCardCrossesCenterWithoutRestoringDistantEntryPivot() {
        // updatePageScales 的轴心可能相隔数十页；运行卡在右侧时，中央卡也不是 1 倍。
        // 同时回放两个滑动方向和横竖屏常见卡片尺寸，检查实际显示中心而非仅检查 scale。
        for (size in listOf(700f, 1100f)) {
            for (entryPivot in listOf(-20000f, 20000f)) {
                for (direction in listOf(-1f, 1f)) {
                    var centered = false
                    var previousCenter = Float.NaN
                    val geometry = RecentsStackGeometry()
                    for (step in -100..100) {
                        val relative = step / 10000f * direction
                        geometry.update(540f, size, relative, 0f)
                        val factor = RecentsDepthScale.withLiveTileBaseline(
                            geometry.scale, RecentsDepthScale.at(relative + 1f),
                        )
                        // 即使入场的 fullscreenProgress 尚未到稳态阈值，也要保持轴心连续。
                        centered = RecentsStackGeometry.shouldCenterPivot(factor, centered, settled = false)
                        val pivot = if (centered) size / 2f else entryPivot
                        val displayCenter = geometry.primaryCenter +
                            RecentsStackGeometry.scaleCenterShift(size, pivot, factor)
                        assertEquals(geometry.primaryCenter, displayCenter, 0.001f)
                        if (!previousCenter.isNaN()) assertTrue(abs(displayCenter - previousCenter) < 1f)
                        previousCenter = displayCenter
                    }
                }
            }
        }
    }

    @Test
    fun distantPivotExplainsWholeCardJumpAtNonUnitScale() {
        // 原来跨中央便恢复旧轴心：仅 2.2% 的缩小，在远轴心下就会跳动半张卡以上。
        val factor = RecentsDepthScale.withLiveTileBaseline(RecentsDepthScale.at(0f), RecentsDepthScale.at(1f))
        val shifted = RecentsStackGeometry.scaleCenterShift(700f, -20000f, factor)
        assertTrue(abs(shifted) > 350f)
        assertEquals(0f, RecentsStackGeometry.scaleCenterShift(700f, 350f, factor), 0f)
        assertEquals(0f, RecentsStackGeometry.scaleCenterShift(700f, -20000f, 1f), 0f)
    }

    @Test
    fun centeredPivotStaysOwnedUntilSessionCleanupEvenWhenScaleReturnsToOne() {
        var centered = RecentsStackGeometry.shouldCenterPivot(0.978f, alreadyCentered = false, settled = false)
        centered = RecentsStackGeometry.shouldCenterPivot(1f, centered, settled = false)
        assertTrue(centered)
        assertTrue(RecentsStackGeometry.shouldCenterPivot(1.022f, centered, settled = false))
    }

    @Test
    fun scrollingAcrossFormerStrayThresholdsNeverChangesSideAbruptly() {
        for (invert in listOf(false, true)) {
            val geometry = RecentsStackGeometry()
            var previous = Float.NaN
            // 覆盖原 0.2 页退出阈值和 0.55 页符号翻转阈值。
            for (step in 0..1000) {
                val relative = RecentsStackGeometry.stackRelative(step / 1000f, invert)
                geometry.update(540f, 700f, relative, 0f)
                if (!previous.isNaN()) assertTrue(abs(geometry.primaryCenter - previous) < 1f)
                previous = geometry.primaryCenter
            }
        }
    }
}
