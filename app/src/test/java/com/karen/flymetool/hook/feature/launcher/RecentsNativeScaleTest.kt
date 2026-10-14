package com.karen.flymetool.hook.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentsNativeScaleTest {
    @Test
    fun normalStatePreservesPersistentScale() {
        assertEquals(0.9f, RecentsNativeScale.compose(0.9f, 1f, 0f, 0.8f)!!, 0.000001f)
    }

    @Test
    fun dismissScaleMultipliesPersistentScale() {
        assertEquals(0.45f, RecentsNativeScale.compose(0.9f, 0.5f, 0f, 0.8f)!!, 0.000001f)
    }

    @Test
    fun modalTransitionInterpolatesScaleBeforeComposition() {
        assertEquals(0.81f, RecentsNativeScale.compose(0.9f, 1f, 0.5f, 0.8f)!!, 0.000001f)
        assertEquals(0.72f, RecentsNativeScale.compose(0.9f, 1f, 1f, 0.8f)!!, 0.000001f)
        assertEquals(0.405f, RecentsNativeScale.compose(0.9f, 0.5f, 0.5f, 0.8f)!!, 0.000001f)
    }

    @Test
    fun gridPersistentScaleRemainsTheCompositionBaseline() {
        assertEquals(0.6f, RecentsNativeScale.compose(0.6f, 1f, 0f, 0.8f)!!, 0.000001f)
        assertEquals(0.24f, RecentsNativeScale.compose(0.6f, 0.5f, 1f, 0.8f)!!, 0.000001f)
    }

    @Test
    fun zeroScaleIsValid() {
        assertEquals(0f, RecentsNativeScale.compose(0f, 1f, 0f, 1f)!!, 0f)
        assertEquals(0f, RecentsNativeScale.compose(1f, 0f, 0f, 1f)!!, 0f)
        assertEquals(0f, RecentsNativeScale.compose(1f, 1f, 1f, 0f)!!, 0f)
    }

    @Test
    fun nonFiniteInputsAreRejectedInEveryPosition() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(RecentsNativeScale.compose(invalid, 1f, 0f, 1f))
            assertNull(RecentsNativeScale.compose(1f, invalid, 0f, 1f))
            assertNull(RecentsNativeScale.compose(1f, 1f, invalid, 1f))
            assertNull(RecentsNativeScale.compose(1f, 1f, 0f, invalid))
        }
    }

    @Test
    fun negativeResultsAreRejected() {
        assertNull(RecentsNativeScale.compose(-1f, 1f, 0f, 1f))
        assertNull(RecentsNativeScale.compose(1f, -1f, 0f, 1f))
        assertNull(RecentsNativeScale.compose(1f, 1f, 1f, -1f))
    }

    @Test
    fun nonFiniteResultsFromFiniteInputsAreRejected() {
        assertNull(RecentsNativeScale.compose(Float.MAX_VALUE, 2f, 0f, 1f))
        assertNull(RecentsNativeScale.compose(1f, 1f, Float.MAX_VALUE, Float.MAX_VALUE))
        assertNull(RecentsNativeScale.compose(0f, 1f, Float.MAX_VALUE, Float.MAX_VALUE))
    }
}
