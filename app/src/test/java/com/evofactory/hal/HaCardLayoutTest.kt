package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Test

class HaCardLayoutTest {
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
