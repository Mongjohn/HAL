package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaPrimaryActionTest {
    @Test
    fun locksUseTheOppositeStableState() {
        assertEquals("unlock", haPrimaryService("lock.front_door", "locked", 0))
        assertEquals("lock", haPrimaryService("lock.front_door", "unlocked", 0))
        for (state in listOf(null, "unknown", "unavailable", "jammed", "locking", "unlocking", "open")) {
            assertNull(haPrimaryService("lock.front_door", state, 0))
        }
        assertNull(haPrimaryService("binary_sensor.front_door", "locked", 0))
    }

    @Test
    fun controlsRespectDirectionAndAdvertisedFeatures() {
        assertEquals("toggle", haPrimaryService("light.hall", "on", 0))
        assertEquals("turn_on", haPrimaryService("humidifier.bedroom", "off", 0))
        assertEquals("open_cover", haPrimaryService("cover.garage", "closed", 1))
        assertNull(haPrimaryService("cover.garage", "open", 1))
        assertEquals("close_valve", haPrimaryService("valve.water", "open", 2))
        assertEquals("media_pause", haPrimaryService("media_player.speaker", "playing", 1))
        assertNull(haPrimaryService("media_player.speaker", "paused", 1))
        assertEquals("return_to_base", haPrimaryService("vacuum.floor", "cleaning", 16))
        assertNull(haPrimaryService("vacuum.floor", "returning", 8192))
    }
}
