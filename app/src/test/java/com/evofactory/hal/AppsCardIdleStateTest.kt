package com.evofactory.hal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsCardIdleStateTest {
    @Test
    fun waitsThirtySecondsAndRestartsAfterActivity() {
        val idle = AppsCardIdleState()
        idle.reset(100)
        idle.expire(30_099)
        assertFalse(idle.collapsed)
        idle.onKey(1, down = false, up = true, power = false, now = 20_000)
        idle.expire(49_999)
        assertFalse(idle.collapsed)
        idle.expire(50_000)
        assertTrue(idle.collapsed)
    }

    @Test
    fun wakePressConsumesRepeatsAndReleaseButNextPressWorks() {
        val idle = AppsCardIdleState()
        idle.expire(30_000)
        assertTrue(idle.onKey(1, true, false, false, 31_000))
        assertFalse(idle.collapsed)
        assertTrue(idle.onKey(1, true, false, false, 32_000))
        assertTrue(idle.onKey(1, false, true, false, 33_000))
        assertFalse(idle.onKey(1, true, false, false, 34_000))
    }

    @Test
    fun powerPassesThroughWithoutExpanding() {
        val idle = AppsCardIdleState()
        idle.expire(30_000)
        assertTrue(idle.collapsed)
        assertFalse(idle.onKey(2, true, false, true, 31_000))
        assertFalse(idle.onKey(2, false, true, true, 32_000))
        assertTrue(idle.collapsed)
    }

    @Test
    fun heldKeyCannotCollapseBeforeItsActionOnRelease() {
        val idle = AppsCardIdleState()
        idle.onKey(1, true, false, false, 1_000)
        idle.expire(31_000)
        assertFalse(idle.collapsed)
        idle.onKey(1, false, true, false, 32_000)
        idle.expire(61_999)
        assertFalse(idle.collapsed)
        idle.expire(62_000)
        assertTrue(idle.collapsed)
    }

    @Test
    fun focusOrMenuResetReopensAndClearsWakeSequence() {
        val idle = AppsCardIdleState()
        idle.expire(30_000)
        idle.onKey(1, true, false, false, 31_000)
        idle.reset(32_000)
        assertFalse(idle.collapsed)
        assertFalse(idle.onKey(1, false, true, false, 33_000))
        idle.expire(63_000)
        assertTrue(idle.collapsed)
    }
}
