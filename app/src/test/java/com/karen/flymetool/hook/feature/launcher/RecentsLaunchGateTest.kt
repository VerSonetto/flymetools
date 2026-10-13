package com.karen.flymetool.hook.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsLaunchGateTest {
    @Test
    fun beginActivatesGateAndIncrementsSessionToken() {
        val gate = RecentsLaunchGate()
        assertFalse(gate.active)

        val first = gate.begin()
        assertTrue(gate.active)
        val second = gate.begin()
        assertEquals(first + 1L, second)
        assertTrue(gate.active)
    }

    @Test
    fun resetClearsGateAndInvalidatesPreviousTokens() {
        val gate = RecentsLaunchGate()
        val first = gate.begin()

        gate.reset()
        assertFalse(gate.active)
        assertFalse(gate.cancel(first))

        val second = gate.begin()
        assertTrue(second > first)
        assertFalse(gate.cancel(first))
        assertTrue(gate.active)
        assertTrue(gate.cancel(second))
        assertFalse(gate.active)
    }

    @Test
    fun oldTokenCannotCancelNewSession() {
        val gate = RecentsLaunchGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.cancel(first))
        assertTrue(gate.active)
        assertTrue(gate.cancel(second))
        assertFalse(gate.active)
    }

    @Test
    fun currentSessionCanOnlyBeCancelledOnce() {
        val gate = RecentsLaunchGate()
        val token = gate.begin()

        assertTrue(gate.cancel(token))
        assertFalse(gate.active)
        assertFalse(gate.cancel(token))
        assertFalse(gate.active)
    }

    @Test
    fun resetWithoutActiveSessionKeepsGateInactive() {
        val gate = RecentsLaunchGate()

        gate.reset()
        gate.reset()
        assertFalse(gate.active)
        assertFalse(gate.cancel(0L))
        assertTrue(gate.begin() > 0L)
        assertTrue(gate.active)
    }
}
