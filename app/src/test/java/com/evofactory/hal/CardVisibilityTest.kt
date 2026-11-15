package com.evofactory.hal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardVisibilityTest {
    @Test
    fun nestedRulesFollowStateChangesIncludingMissingEntities() {
        val rule = parseCardVisibility(JSONObject("""
            {"condition":"and","conditions":[
                {"condition":"state","entity":"sensor.mode","state_not":["away","sleep"]},
                {"condition":"or","conditions":[
                    {"condition":"state","entity":"light.hall","state":"on"},
                    {"condition":"state","entity":"binary_sensor.door","state":"on"}
                ]}
            ]}
        """.trimIndent()))
        val states = mutableMapOf("light.hall" to "on")
        assertNull(rule.matches(states::get))
        states["sensor.mode"] = "home"
        assertEquals(true, rule.matches(states::get))
        states["light.hall"] = "off"
        assertNull(rule.matches(states::get))
        states["binary_sensor.door"] = "off"
        assertEquals(false, rule.matches(states::get))
        states["binary_sensor.door"] = "on"
        assertEquals(true, rule.matches(states::get))
        states["sensor.mode"] = "sleep"
        assertEquals(false, rule.matches(states::get))
    }

    @Test
    fun notGroupMatchesWhenAtLeastOneConditionFails() {
        val rule = parseCardVisibility(JSONObject("""
            {"condition":"not","conditions":[
                {"condition":"state","entity":"light.hall","state":"on"},
                {"condition":"state","entity":"light.kitchen","state":"on"}
            ]}
        """.trimIndent()))
        val states = mutableMapOf("light.hall" to "on", "light.kitchen" to "off")
        assertEquals(true, rule.matches(states::get))
        states["light.kitchen"] = "on"
        assertEquals(false, rule.matches(states::get))
    }

    @Test
    fun importedConditionalCardKeepsRulesAndSubscribesToTheirEntitiesAfterSaving() {
        val board = requireNotNull(parseDashboard(JSONObject("""
            {"views":[{"sections":[{"cards":[
                {"type":"entity","entity":"sensor.temperature"},
                {"type":"conditional","conditions":[
                    {"condition":"state","entity_id":"binary_sensor.door","state":"on"}
                ],"card":{"type":"entity","entity":"sensor.humidity","visibility":[
                    {"condition":"state","entity":"sensor.mode","state_not":"away"}
                ]}}
            ]}]}]}
        """.trimIndent())))
        val layout = seedDashboardLayout(board, emptyList(), columns = 2, twoPerRow = false)
        val saved = dashboardLayoutFromJson(layout.toJson())
        val (ordinary, conditional) = saved.pages.single().cards.map { it.card }
        assertNull(ordinary.visibility)
        assertEquals(listOf("sensor.humidity"), conditional.ids)
        assertEquals(layout.pages.single().cards.last().card.visibility, conditional.visibility)
        assertEquals(setOf("sensor.temperature", "sensor.humidity", "binary_sensor.door", "sensor.mode"), saved.entityIds.toSet())
        assertTrue(ordinary.visibility.isVisible(emptyMap()))
        assertFalse(conditional.visibility.isVisible(emptyMap()))
    }

    @Test
    fun importedLegacyComparisonFollowsBothEntitiesAndUsesHomeAssistantMissingState() {
        val imported = parseCardVisibility(JSONObject("""
            {"entity":"sensor.mode","state":["sensor.target_mode","unknown"]}
        """.trimIndent()), fromHomeAssistant = true)
        val saved = parseCardVisibility(imported.toJson())
        val states = mutableMapOf("sensor.target_mode" to "quiet")
        assertEquals(listOf("sensor.mode", "sensor.target_mode"), saved.entityIds())
        assertEquals(true, saved.matches(states::get))
        states["sensor.mode"] = "quiet"
        assertEquals(true, saved.matches(states::get))
        states["sensor.target_mode"] = "away"
        assertEquals(false, saved.matches(states::get))
    }

    @Test
    fun itemRulesRoundTripAndFollowTheirEntitiesOnBothCardTypes() {
        val layout = dashboardLayoutFromJson(JSONObject("""
            {"pages":[{"title":"Home","columns":2,"cards":[
                {"id":"readings","x":0,"y":0,"w":1,"h":3,"entities":["sensor.temperature"],"visibility":null,
                    "itemVisibility":{"0":{"keepSpace":true,"rule":{"condition":"state","entity":"sensor.mode","state":"home"}}}},
                {"id":"apps","x":1,"y":0,"w":1,"h":3,"entities":[],"apps":["app.one","app.two"],
                    "itemVisibility":{"app.one":{"rule":{"condition":"state","entity":"light.hall","state_not":"off"}}}}
            ]}]}
        """.trimIndent()))
        val saved = dashboardLayoutFromJson(layout.toJson())
        val (readings, apps) = saved.pages.single().cards
        assertNull(readings.card.visibility)
        assertTrue(readings.itemVisibility.getValue("0").keepSpace)
        assertFalse(apps.itemVisibility.getValue("app.one").keepSpace)
        assertEquals(setOf("sensor.temperature", "sensor.mode", "light.hall"), saved.entityIds.toSet())

        val reordered = saved.replaceApps(listOf("app.two", "app.one"))
        assertEquals(apps.itemVisibility, reordered.pages.single().cards.last().itemVisibility)
        val removed = saved.replaceApps(listOf("app.two"))
        assertTrue(removed.pages.single().cards.last().itemVisibility.isEmpty())
    }

    @Test
    fun importerReportsUnsupportedStateOptionsWithoutChangingSiblingCards() {
        val board = requireNotNull(parseDashboard(JSONObject("""
            {"views":[{"cards":[
                {"type":"entity","entity":"sensor.temperature","visibility":[
                    {"condition":"state","entity":"climate.room","attribute":"hvac_action","state":"heating"}
                ]},
                {"type":"entity","entity":"sensor.humidity"}
            ]}]}
        """.trimIndent())))
        assertEquals(listOf("sensor.humidity"), board.entityIds)
        assertEquals(listOf("card with invalid or unsupported visibility rules"), board.skipped)
    }
}
