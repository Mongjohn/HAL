package com.evofactory.hal

import org.junit.Assert.*
import org.junit.Test

class CardPopupLayoutTest {
    @Test fun compactPopupIsHalfThePreviousWidthAndWidePopupKeepsIt() {
        assertEquals(380, calmPopupWidth(1))
        assertEquals(760, calmPopupWidth(2))
    }

    @Test fun oneColumnKeepsEveryItemOnItsOwnRowAndTwoColumnsPairInOrder() {
        val card = SensorCard("lights", "Lights", listOf("light.a", "light.b", "switch.c", "sensor.d", "button.e"))
        val positions = card.ids.indices.toList()
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(4)), card.popupRows(positions, 1))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), card.popupRows(positions, 2))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(4)), card.itemRows(positions, false, emptySet()))
    }

    @Test fun fullWidthWeatherAndHeadingBoundariesInterruptPairs() {
        val card = SensorCard("mixed", "Mixed", listOf("light.a", "weather.home", "light.b", "light.c", "light.d"), subtitles = mapOf(4 to "Another group"))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)), card.popupRows(card.ids.indices.toList(), 2))
    }

    @Test fun reservedSlotsAndReleasedSlotsRetainTheirOrderAndControlRows() {
        val card = SensorCard("lights", "Lights", listOf("light.a", "light.b", "light.c", "light.d"))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), card.popupRows(listOf(0, 1, 2, 3), 2))
        assertEquals(listOf(listOf(0, 2), listOf(3)), card.popupRows(listOf(0, 2, 3), 2))
    }

    @Test fun dedicatedMediaPanelsRemainFullWidth() {
        val card = SensorCard("media", "", listOf("media_player.a", "media_player.b"))
        assertEquals(listOf(listOf(0), listOf(1)), card.popupRows(listOf(0, 1), 2))
    }
}
