package com.karen.flymetool.hook.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecentsTaskPromotionTest {
    @Test
    fun phoneNativeContractPromotesQuickSwitchedTaskOnlyDuringOverviewCommit() {
        val theme = Any()
        val gallery = Any()
        val older = Any()
        val children = mutableListOf(theme, gallery, older)
        val promotion = RecentsTaskPromotion()
        // Flyme 手机契约：已存在的 running 卡直接返回当前位置，不会自动归首。
        fun expected(candidate: Any) = promotion.expectedIndex(candidate) ?: children.indexOf(candidate)
        assertEquals(1, expected(gallery))
        promotion.withTarget(gallery, 0) {
            val target = expected(gallery)
            children.remove(gallery)
            children.add(target, gallery)
        }
        assertSame(gallery, children[0])
        assertSame(theme, children[1])
        assertSame(older, children[2])
        // 后续翻页只改变当前页，不再把旧顶层恢复到首位。
        for (page in listOf(1, 2, 0, 1)) {
            assertEquals(page, expected(children[page]))
            assertSame(gallery, children[0])
        }
        assertNull(promotion.expectedIndex(gallery))
    }

    @Test
    fun quickSwitchAndOtherTasksKeepNativePositions() {
        val running = Any()
        val adjacent = Any()
        val promotion = RecentsTaskPromotion()
        assertNull(promotion.expectedIndex(running))
        promotion.withTarget(running, 2) {
            assertEquals(2, promotion.expectedIndex(running))
            assertNull(promotion.expectedIndex(adjacent))
        }
        assertNull(promotion.expectedIndex(running))
    }

    @Test
    fun throwingOrNestedNativeCallsNeverLeakPromotionIntoLaterGestures() {
        val first = Any()
        val second = Any()
        val promotion = RecentsTaskPromotion()
        promotion.withTarget(first, 0) {
            try {
                promotion.withTarget(second, 1) {
                    assertEquals(1, promotion.expectedIndex(second))
                    error("模拟原生重排失败")
                }
            } catch (_: IllegalStateException) {
                assertEquals(0, promotion.expectedIndex(first))
                assertNull(promotion.expectedIndex(second))
            }
        }
        assertNull(promotion.expectedIndex(first))
        assertNull(promotion.expectedIndex(second))
    }
}
