package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class HaMediaPlayerTest {
    @Test
    fun mediaOnlyCardsKeepEachPlayerFullWidthAndMixedCardsUseNormalRows() {
        val mediaOnly = SensorCard("media", "", listOf("media_player.tv", "media_player.speaker"))
        val mixed = SensorCard("mixed", "", listOf("media_player.tv", "sensor.temperature"))
        assertTrue(mediaOnly.isMediaOnly)
        assertEquals(listOf(0..0, 1..1), mediaOnly.entityRows(twoPerRow = true))
        assertFalse(mixed.isMediaOnly)
        assertEquals(listOf(0..1), mixed.entityRows(twoPerRow = true))
        assertFalse(SensorCard("empty", "", emptyList()).isMediaOnly)
    }

    @Test
    fun progressAdvancesWithoutATimestampAndIgnoresUnrelatedUpdates() {
        val attributes = JSONObject().put("media_duration", 90).put("media_position", 20)
        val initial = requireNotNull(MediaPlayback.observe("playing", attributes, null, 1000, 10000))
        attributes.put("volume_level", 0.8)
        val updated = MediaPlayback.observe("playing", attributes, initial, 2000, 11000)
        assertSame(initial, updated)
        assertEquals(22.0, initial.positionAt(3000), 0.0)
        assertEquals(90.0, initial.positionAt(100000), 0.0)
    }

    @Test
    fun progressFreezesOnPauseResumesAndReanchorsOnSeekOrTrackChange() {
        val attributes = JSONObject().put("media_duration", 90).put("media_position", 20).put("media_title", "First track")
        val initial = MediaPlayback.observe("playing", attributes, null, 1000, 10000)
        val paused = requireNotNull(MediaPlayback.observe("paused", attributes, initial, 4000, 13000))
        assertEquals(23.0, paused.positionAt(8000), 0.0)
        val resumed = requireNotNull(MediaPlayback.observe("playing", attributes, paused, 8000, 17000))
        assertEquals(25.0, resumed.positionAt(10000), 0.0)
        attributes.put("media_position", 20.5)
        val seek = requireNotNull(MediaPlayback.observe("playing", attributes, resumed, 10000, 19000))
        assertEquals(20.5, seek.positionAt(10000), 0.0)
        attributes.put("media_title", "Next track")
        val next = requireNotNull(MediaPlayback.observe("playing", attributes, seek, 11000, 20000))
        assertEquals(20.5, next.positionAt(11000), 0.0)
    }

    @Test
    fun progressUsesFreshOffsetTimestampsAndFallsBackForUnusableOnes() {
        val attributes = JSONObject().put("media_duration", 90).put("media_position", 20)
            .put("media_position_updated_at", "1970-01-01T01:00:08.500+01:00")
        val initial = requireNotNull(MediaPlayback.observe("playing", attributes, null, 1000, 10000))
        assertEquals(22.5, initial.positionAt(2000), 0.0)
        for (timestamp in listOf("1970-01-01T00:00:20Z", "invalid", "1969-12-31T23:00:00Z")) {
            attributes.put("media_position_updated_at", timestamp)
            val fallback = requireNotNull(MediaPlayback.observe("playing", attributes, initial, 2000, 11000))
            assertEquals(21.0, fallback.positionAt(3000), 0.0)
        }
    }

    @Test
    fun artworkUsesTheLoginOnlyForTheHomeAssistantOrigin() {
        val base = "http://home.example:8123"
        val proxied = requireNotNull(mediaArtworkRequest(base, "/api/media_player_proxy/media_player.tv?token=signed", "session"))
        assertEquals("http://home.example:8123/api/media_player_proxy/media_player.tv?token=signed", proxied.url.toString())
        assertEquals("Bearer session", proxied.header("Authorization"))
        val external = requireNotNull(mediaArtworkRequest(base, "https://media.example/cover.jpg", "session"))
        assertNull(external.header("Authorization"))
        assertNull(mediaArtworkRequest(base, "http://home.example:8080/cover.jpg", "session"))
    }
}
