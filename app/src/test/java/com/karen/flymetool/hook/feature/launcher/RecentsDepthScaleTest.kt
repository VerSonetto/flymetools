package com.karen.flymetool.hook.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RecentsDepthScaleTest {
    @Test
    fun foregroundRemainsLargerAfterLowerCardReachesCenter() {
        assertEquals(1f, RecentsDepthScale.at(0f), 0.00001f)
        assertEquals(0.978f, RecentsDepthScale.at(-1f), 0.00001f)
        assertTrue(RecentsDepthScale.at(1f) > RecentsDepthScale.at(0f))
        assertTrue(RecentsDepthScale.at(2f) > RecentsDepthScale.at(1f))
    }

    @Test
    fun adjacentLayersStayOrderedThroughoutSwipeInEitherDirection() {
        for (step in -200..200) {
            val back = step / 100f
            assertTrue(RecentsDepthScale.at(back + 1f) > RecentsDepthScale.at(back))
        }
    }

    @Test
    fun crossingCenterDoesNotJumpAndDistantCardsRemainBounded() {
        assertTrue(abs(RecentsDepthScale.at(0.0001f) - RecentsDepthScale.at(-0.0001f)) < 0.0001f)
        for (position in listOf(-100f, -3f, 0f, 3f, 100f)) {
            assertTrue(RecentsDepthScale.at(position) in 0.956f..1.044f)
        }
    }

    @Test
    fun liveTileKeepsNativeSizeWithoutFlatteningOtherLayers() {
        for (step in -200..200) {
            val running = step / 100f
            val baseline = RecentsDepthScale.at(running)
            assertEquals(1f, RecentsDepthScale.withLiveTileBaseline(baseline, baseline), 0.00001f)
            val behind = RecentsDepthScale.withLiveTileBaseline(RecentsDepthScale.at(running - 1f), baseline)
            val ahead = RecentsDepthScale.withLiveTileBaseline(RecentsDepthScale.at(running + 1f), baseline)
            assertTrue(behind < 1f)
            assertTrue(ahead > 1f)
        }
    }
}
