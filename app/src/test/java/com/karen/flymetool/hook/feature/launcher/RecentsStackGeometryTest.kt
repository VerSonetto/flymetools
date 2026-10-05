package com.karen.flymetool.hook.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
