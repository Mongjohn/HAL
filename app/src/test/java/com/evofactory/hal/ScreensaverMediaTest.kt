package com.evofactory.hal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScreensaverMediaTest {
    private val show = CardVisibility.State("input_boolean.show", listOf("on"), false)
    private fun entity(id: String, state: String) = HaEntity(id, state, JSONObject())

    @Test fun hiddenCardsAndReservedHiddenPlayersNeverAppear() {
        val card = SensorCard("players", "Players", listOf("media_player.private", "media_player.visible"))
        val options = DashboardCard(card, 0, 0, 1, 3, itemVisibility = mapOf("0" to ItemVisibility(show, true)))
        val hidden = SensorCard("hidden", "Hidden", listOf("media_player.other"), visibility = show)
        val names = mapOf("media_player.visible" to "Séjour æøå")
        val result = screensaverMediaPlayers(listOf(card, hidden), mapOf(card.key to options), emptyMap(), names)
        assertEquals(listOf(ScreensaverMediaPlayer("media_player.visible", "Séjour æøå")), result)
        val live = mapOf("input_boolean.show" to entity("input_boolean.show", "on"))
        assertEquals(3, screensaverMediaPlayers(listOf(card, hidden), mapOf(card.key to options), live, names).size)
    }

    @Test fun mixedCardsAndPausedPlayersAreIncludedWithoutDiscoveringOtherPlayers() {
        val card = SensorCard("room", "Room", listOf("sensor.temperature", "media_player.living"))
        val entities = mapOf("media_player.living" to entity("media_player.living", "paused"),
            "media_player.unconfigured" to entity("media_player.unconfigured", "playing"))
        assertEquals(listOf("media_player.living"), screensaverMediaPlayers(listOf(card), emptyMap(), entities, emptyMap()).map { it.id })
        val off = entities + ("media_player.living" to entity("media_player.living", "off"))
        assertEquals(1, screensaverMediaPlayers(listOf(card), emptyMap(), off, emptyMap()).size)
    }

    @Test fun gridOrderAndDuplicatesFollowTheVisibleCards() {
        val late = SensorCard("late", "Later", listOf("media_player.second", "media_player.first"))
        val first = SensorCard("first", "First", listOf("media_player.first"))
        val options = mapOf("late" to DashboardCard(late, 1, 3, 1, 3), "first" to DashboardCard(first, 0, 0, 1, 3))
        assertEquals(listOf("media_player.first", "media_player.second"),
            screensaverMediaPlayers(listOf(late, first), options, emptyMap(), emptyMap()).map { it.id })
        assertTrue(screensaverMediaPlayers(emptyList(), options, emptyMap(), emptyMap()).isEmpty())
    }

    @Test fun gaugeReadingsAndAppsContentDoNotBecomePlayers() {
        val gauge = SensorCard("gauge", "Gauge", listOf("media_player.level"),
            gauges = mapOf("media_player.level" to HaGauge(0.0, 100.0, false, emptyList())))
        val apps = SensorCard("apps", "Apps", listOf("media_player.not_a_reading"))
        val options = mapOf("apps" to DashboardCard(apps, 0, 0, 1, 3, apps = listOf(ALL_APPS_TILE_KEY)))
        assertTrue(screensaverMediaPlayers(listOf(gauge, apps), options, emptyMap(), emptyMap()).isEmpty())
    }
}
