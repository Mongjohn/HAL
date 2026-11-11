package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaStateCueTest {
    @Test
    fun booleansHaveDistinctOnAndOffShapes() {
        for (domain in listOf("light", "switch", "input_boolean", "fan", "automation", "humidifier")) {
            val on = requireNotNull(haStateCue(domain, null, "on"))
            val off = requireNotNull(haStateCue(domain, null, "off"))
            assertNotEquals(domain, on.icon, off.icon)
            assertEquals(domain, HaStateTone.Active, on.tone)
            assertEquals(domain, HaStateTone.Neutral, off.tone)
        }
    }

    @Test
    fun locksAndLockSensorsUseTheSameCues() {
        val locked = HaStateCue("lock", HaStateTone.Good, iconOnly = true)
        val unlocked = HaStateCue("lock-open", HaStateTone.Alert, iconOnly = true)
        assertEquals(locked, haStateCue("lock", null, "locked"))
        assertEquals(unlocked, haStateCue("lock", null, "unlocked"))
        assertEquals(locked, haStateCue("binary_sensor", "lock", "off"))
        assertEquals(unlocked, haStateCue("binary_sensor", "lock", "on"))
    }

    @Test
    fun aLockWithMissingStateNeverLooksSecure() {
        assertEquals(HaStateCue("cloud-off-outline"), haStateCue("lock", null, "unavailable"))
        assertEquals(HaStateCue("help-circle-outline"), haStateCue("lock", null, null))
        assertEquals(HaStateCue("help-circle-outline"), haStateCue("lock", null, "off"))
        assertEquals(HaStateCue("lock-alert", HaStateTone.Alert), haStateCue("lock", null, "jammed"))
    }

    @Test
    fun readingsAndCustomStatusesHaveDifferentFallbacks() {
        assertNull(haStateCue("sensor", "temperature", "22.4"))
        assertNull(haStateCue("input_text", null, "error"))
        assertEquals(HaStateCue("dots-horizontal-circle-outline"), haStateCue("sensor", "enum", "rinse_cycle"))
        assertNotEquals(haStateCue("binary_sensor", "door", "off"), haStateCue("binary_sensor", "door", "on"))
    }
}
