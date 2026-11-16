package com.evofactory.hal

import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HaCardLayoutTest {
    @Test
    fun wideDashboardKeepsOffScreenCardsAndItsVisibleColumnSetting() {
        val json = JSONObject("""
            {"pages":[
                {"title":"Wide","columns":24,"columnsPerScreen":3,"cards":[
                    {"id":"last","title":"Last","x":22,"y":1,"w":2,"h":3,"entities":[]}
                ]},
                {"title":"Legacy","columns":5,"cards":[]},
                {"title":"Auto","columns":10,"cards":[]}
            ]}
        """.trimIndent())
        val saved = dashboardLayoutFromJson(dashboardLayoutFromJson(json).toJson())
        val (wide, legacy, auto) = saved.pages
        assertEquals(24, wide.columns)
        assertEquals(3, wide.visibleColumns)
        assertEquals(22, wide.cards.single().x)
        assertEquals(2, wide.cards.single().width)
        assertEquals(5, legacy.visibleColumns)
        assertEquals(6, auto.visibleColumns)

        json.getJSONArray("pages").getJSONObject(0).put("columns", 23)
        assertThrows(IllegalArgumentException::class.java) { dashboardLayoutFromJson(json) }
    }

    @Test
    fun threeFixedColumnsAndTwoGapsFitTheDashboardWidth() {
        val width = 960.dp
        val column = dashboardColumnWidth(width)
        assertEquals(width, column * 3 + PanelItemSpacing * 2)
    }

    @Test
    fun pairsEntitiesWithoutCrossingHeadingsOrWeather() {
        val card = SensorCard(
            "room", "Living room",
            listOf("light.ceiling", "switch.fan", "sensor.temperature", "weather.home", "light.floor", "light.desk"),
            subtitles = mapOf(1 to "Climate"),
        )

        assertEquals(listOf(0..0, 1..2, 3..3, 4..5), card.entityRows(twoPerRow = true))
        assertEquals(card.ids.indices.map { it..it }, card.entityRows(twoPerRow = false))
    }

    @Test
    fun releasedHalfWidthSlotsCanReflowWithoutCrossingSubheadings() {
        val card = SensorCard(
            "room", "Living room",
            listOf("light.ceiling", "switch.fan", "sensor.temperature", "light.floor", "light.desk"),
            subtitles = mapOf(3 to "Lamps"),
        )
        assertEquals(listOf(listOf(0, 2), listOf(4)), card.itemRows(listOf(0, 2, 4), true, null))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(4)), card.itemRows(listOf(0, 1, 2, 4), true, null))
    }

    @Test
    fun sortsCardTitlesWithoutReorderingEntitiesInsideCards() {
        val ids = listOf("switch.fan", "light.floor", "switch.heater", "light.desk")
        val layout = HaCardLayout(grouping = HaCardGrouping.Type)
        val switches = listOf("switch.fan", "switch.heater")
        val lights = listOf("light.floor", "light.desk")

        assertEquals(listOf(switches, lights), layout.selectionCards(ids, emptyMap()).map { it.ids })
        assertEquals(listOf(lights, switches), layout.copy(alphabetical = true).selectionCards(ids, emptyMap()).map { it.ids })
        assertEquals(listOf(ids), layout.copy(grouping = HaCardGrouping.None).selectionCards(ids, emptyMap()).map { it.ids })
    }
}
