package com.evofactory.hal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CalmDashboardTest {
    @Test fun summariesExcludeHiddenAndReservedReadings() {
        val card = SensorCard("room", "Room", listOf("light.private", "sensor.temperature"))
        val options = DashboardCard(card, 0, 0, 1, 4, itemVisibility = mapOf(
            "0" to ItemVisibility(CardVisibility.State("input_boolean.show", listOf("on"), false), true),
        ))
        val entities = mapOf("light.private" to entity("light.private", "on"),
            "sensor.temperature" to entity("sensor.temperature", "21"))
        val visible = calmVisibleEntities(card, options, entities)
        assertEquals(listOf("sensor.temperature"), visible.map { it.id })
        assertEquals("21", calmSummary(visible))
    }

    @Test fun missingLightsAreNotReportedAsOff() {
        assertEquals("Lights unavailable", calmSummary(listOf(entity("light.a", "unavailable"))))
        assertEquals("Lights unavailable", calmSummary(listOf(entity("light.a", "off"), entity("light.b", "unknown"))))
        assertEquals("Lights off", calmSummary(listOf(entity("light.a", "off"))))
        assertEquals("1 light on", calmSummary(listOf(entity("light.a", "on"), entity("light.b", "off"))))
    }

    private fun entity(id: String, state: String) = HaEntity(id, state, JSONObject())

    @Test fun overviewReflowsBeforeItScales() {
        assertEquals(CalmOverviewMode.Stacked, calmOverviewMode(160, 140, 80))
        assertEquals(CalmOverviewMode.Horizontal, calmOverviewMode(100, 140, 80))
        assertEquals(CalmOverviewMode.Scaled, calmOverviewMode(60, 140, 80))
    }

    @Test fun lockBadgeUsesSubduedSemanticColors() {
        val locked = calmLockColor("locked")
        val unlocked = calmLockColor("unlocked")
        org.junit.Assert.assertTrue(locked.green > locked.red && locked.green > locked.blue)
        org.junit.Assert.assertTrue(unlocked.red > unlocked.green && unlocked.red > unlocked.blue)
        org.junit.Assert.assertTrue(listOf(locked, unlocked).all { maxOf(it.red, it.green, it.blue) < 0.7f })
    }

    @Test fun navigationHasHomeAndOnlyConfiguredCameraOrAlertTabs() {
        assertEquals(listOf(HomeTab.Home), availableHomeTabs(false, false))
        assertEquals(listOf(HomeTab.Home, HomeTab.Cameras), availableHomeTabs(true, false))
        assertEquals(listOf(HomeTab.Home, HomeTab.Alerts), availableHomeTabs(false, true))
        assertEquals(listOf(HomeTab.Home, HomeTab.Cameras, HomeTab.Alerts), availableHomeTabs(true, true))
    }

    @Test fun retiredAssistantPreservesHomeAndAppendsItsCardsWithoutKeyCollisions() {
        val home = DashboardLayout(listOf(DashboardPage("Home", 2, listOf(
            DashboardCard(SensorCard("same", "Original", listOf("sensor.home")), 0, 0, 1, 2)))),
            aliases = mapOf("sensor.shared" to "Home name"))
        val rule = CardVisibility.State("input_boolean.show", listOf("on"), false)
        val original = DashboardCard(SensorCard("same", "Imported", listOf("light.a"), visibility = rule),
            1, 3, 1, 4, focusOnHome = true, halfWidthItems = setOf(0), itemVisibility = mapOf("0" to ItemVisibility(rule, true)))
        val old = DashboardLayout(listOf(DashboardPage("Room", 3, listOf(original))),
            aliases = mapOf("sensor.shared" to "Assistant name", "light.a" to "Lamp"))
        val migrated = DashboardLayouts(assistant = old, home = home).retireAssistant(home)
        assertEquals(null, migrated.assistant)
        val layout = migrated.home!!
        assertEquals(home.pages.first(), layout.pages.first())
        val item = layout.pages.last().cards.single()
        org.junit.Assert.assertNotEquals("same", item.card.key)
        assertEquals(original.x, item.x)
        assertEquals(original.y, item.y)
        assertEquals(original.height, item.height)
        assertEquals(rule, item.card.visibility)
        assertEquals(original.itemVisibility, item.itemVisibility)
        assertEquals(original.halfWidthItems, item.halfWidthItems)
        assertEquals(true, item.focusOnHome)
        assertEquals("Home name", layout.aliases["sensor.shared"])
        assertEquals("Lamp", layout.aliases["light.a"])
        assertEquals(2, DashboardLayouts.fromJson(migrated.toJson()).home!!.pages.size)
        assertEquals(migrated, migrated.retireAssistant(layout))
    }

    @Test fun bothMaximumLegacyViewSetsSurviveRetirementAndReload() {
        val home = DashboardLayout(List(32) { DashboardPage("Home $it", 1,
            listOf(DashboardCard(SensorCard("h$it", "", listOf("sensor.a")), 0, 0, 1, 1))) })
        val old = DashboardLayout(List(32) { DashboardPage("Old $it", 1,
            listOf(DashboardCard(SensorCard("a$it", "", listOf("sensor.b")), 0, 0, 1, 1))) })
        val migrated = DashboardLayouts(old, home).retireAssistant(home)
        assertEquals(64, DashboardLayouts.fromJson(migrated.toJson()).home!!.pages.size)
    }

    @Test fun lockBadgesRespectVisibilityAndNeverAssumeMissingLocksAreLocked() {
        val card = SensorCard("room", "Room", listOf("lock.front", "lock.back"))
        val states = mapOf("lock.front" to entity("lock.front", "locked"), "lock.back" to entity("lock.back", "unlocked"))
        assertEquals("unlocked", calmLockState(card, null, states))
        assertEquals("unknown", calmLockState(card, null, states - "lock.back"))
        val hidden = DashboardCard(card, 0, 0, 1, 4, itemVisibility = mapOf(
            "1" to ItemVisibility(CardVisibility.State("input_boolean.show", listOf("on"), false), true)))
        assertEquals("locked", calmLockState(card, hidden, states))
        assertEquals(null, calmLockState(SensorCard("empty", "", listOf("light.a")), null, states))
    }

    @Test fun mediaSlotsKeepSavedOrderAndReserveOnlyRequestedHiddenSpaces() {
        val card = SensorCard("players", "Players", listOf("media_player.a", "media_player.b", "media_player.c"))
        val rule = CardVisibility.State("input_boolean.show", listOf("on"), false)
        val options = DashboardCard(card, 0, 0, 1, 4, itemVisibility = mapOf(
            "0" to ItemVisibility(rule), "1" to ItemVisibility(rule, true)))
        val slots = calmPlayerSlots(listOf(card, SensorCard("mixed", "", listOf("sensor.a", "media_player.d"))),
            mapOf(card.key to options), emptyMap())
        assertEquals(listOf("media_player.b", "media_player.c"), slots.map { it.id })
        assertEquals(listOf(true, false), slots.map { it.hidden })
    }

    @Test fun transportRequiresCurrentAvailabilityAndAdvertisedCapabilities() {
        val all = 1 or 16 or 32 or 4096 or 16384
        assertEquals(true, calmMediaActionSupported("playing", all, CalmMediaAction.Pause))
        assertEquals(false, calmMediaActionSupported("paused", all, CalmMediaAction.Pause))
        assertEquals(true, calmMediaActionSupported("paused", all, CalmMediaAction.Play))
        for (action in CalmMediaAction.entries) {
            assertEquals(false, calmMediaActionSupported("playing", 0, action))
            for (state in listOf(null, "unknown", "unavailable", "off"))
                assertEquals(false, calmMediaActionSupported(state, all, action))
        }
    }
}
