package com.evofactory.hal

import org.junit.Assert.*
import org.junit.Test

class HalIdleStateTest {
    @Test fun usesConfiguredAmbientDeadlineAndFallback() {
        assertEquals(300_000L, halIdleTimeout(300_000, 1_200_000))
        assertEquals(60_000L, halIdleTimeout(300_000, 60_000))
        assertEquals(300_000L, halIdleTimeout(null, -1))
        assertEquals(300_000L, halIdleTimeout(0, null))
    }

    @Test fun entersOnlyAtDeadlineAndActivityRestartsIt() {
        val idle = HalIdleState(100)
        assertFalse(idle.expire(1099, 1000))
        assertTrue(idle.expire(1100, 1000))
        assertTrue(idle.active)
        idle.reset(2000)
        assertFalse(idle.active)
        idle.activity(2500)
        assertEquals(500L, idle.waitMillis(3000, 1000))
        assertFalse(idle.expire(3499, 1000))
        assertTrue(idle.expire(3500, 1000))
    }

    @Test fun heldKeysDeferEntryUntilAFullTimeoutAfterRelease() {
        val idle = HalIdleState(0)
        assertFalse(idle.onKey(23, true, false, false, 500))
        assertNull(idle.waitMillis(5000, 1000))
        assertFalse(idle.expire(5000, 1000))
        assertFalse(idle.onKey(23, false, true, false, 5000))
        assertFalse(idle.expire(5999, 1000))
        assertTrue(idle.expire(6000, 1000))
    }

    @Test fun firstWakeKeyRepeatsAndReleaseAreConsumedIncludingHome() {
        for (key in listOf(3, 4, 19, 23, 24, 85, 82)) {
            val idle = HalIdleState(0)
            assertTrue(idle.expire(1000, 1000))
            assertTrue(idle.onKey(key, true, false, false, 1001))
            assertFalse(idle.active)
            assertTrue(idle.waking)
            assertTrue(idle.onKey(key, true, false, false, 1200))
            assertTrue(idle.onKey(key, false, true, false, 1300))
            assertFalse(idle.waking)
            assertFalse(idle.onKey(key, true, false, false, 1400))
            assertFalse(idle.onKey(key, false, true, false, 1500))
        }
    }

    @Test fun simultaneousKeysCannotLeakActionsWhileWaking() {
        val idle = HalIdleState(0)
        idle.expire(1000, 1000)
        assertTrue(idle.onKey(23, true, false, false, 1100))
        assertTrue(idle.onKey(19, true, false, false, 1200))
        assertTrue(idle.onKey(23, false, true, false, 1300))
        assertTrue(idle.onKey(19, false, true, false, 1400))
        assertFalse(idle.onKey(19, true, false, false, 1500))
    }

    @Test fun powerAlwaysPassesThrough() {
        val idle = HalIdleState(0)
        idle.expire(1000, 1000)
        assertFalse(idle.onKey(26, true, false, true, 1100))
        assertFalse(idle.onKey(26, false, true, true, 1200))
        assertTrue(idle.active)
    }

    @Test fun pointerWakeConsumesWholeGestureAndHoldingDefersIdle() {
        val idle = HalIdleState(0)
        assertFalse(idle.onPointer(true, false, 100))
        assertFalse(idle.expire(5000, 1000))
        assertFalse(idle.onPointer(false, true, 5000))
        assertTrue(idle.expire(6000, 1000))
        assertTrue(idle.onPointer(true, false, 6100))
        assertTrue(idle.waking)
        assertTrue(idle.onPointer(false, false, 6200))
        assertTrue(idle.onPointer(false, true, 6300))
        assertFalse(idle.waking)
        assertFalse(idle.onPointer(true, false, 6400))
    }

    @Test fun foregroundResetClearsHeldStateAndStartsFresh() {
        val idle = HalIdleState(0)
        idle.onKey(23, true, false, false, 100)
        idle.reset(10_000)
        assertEquals(1000L, idle.waitMillis(10_000, 1000))
        assertFalse(idle.expire(10_999, 1000))
        assertTrue(idle.expire(11_000, 1000))
    }
}
