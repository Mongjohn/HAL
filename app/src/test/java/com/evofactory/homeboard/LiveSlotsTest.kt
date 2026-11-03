package com.evofactory.homeboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSlotsTest {
    private val ids = listOf("a", "b", "c", "d", "e")

    private fun slots(max: Int) = LiveSlots(max).apply { ids.forEach(::want) }

    private fun LiveSlots.live(spare: Int = 0) = ids.filter { has(it, spare) }

    @Test
    fun firstToAskPlay() {
        assertEquals(listOf("a", "b"), slots(2).live())
    }

    @Test
    fun selectedTakesFromOneNeverSelected() {
        val slots = slots(2)
        slots.select("a")
        slots.select("d")
        assertEquals(listOf("a", "d"), slots.live())
    }

    @Test
    fun selectedTakesFromSelectedLongestAgo() {
        val slots = slots(2)
        slots.select("a")
        slots.select("d")
        slots.select("e")
        assertEquals(listOf("d", "e"), slots.live())
        slots.select("a")
        assertEquals(listOf("a", "e"), slots.live())
    }

    @Test
    fun selectingOnePlayingKeepsTheOthers() {
        val slots = slots(2)
        slots.select("b")
        assertEquals(listOf("a", "b"), slots.live())
        slots.select("d")
        assertEquals(listOf("b", "d"), slots.live())
    }

    @Test
    fun leavingLetsTheNextIn() {
        val slots = slots(2)
        slots.select("d")
        slots.leave("d")
        assertEquals(listOf("a", "b"), slots.live())
    }

    @Test
    fun spareComesFromSelectedLongestAgo() {
        val slots = slots(2)
        slots.select("a")
        slots.select("d")
        assertEquals(listOf("d"), slots.live(spare = 1))
    }

    @Test
    fun onlyThoseThatAsked() {
        val slots = slots(2)
        slots.select("z")
        assertEquals(listOf("a", "b"), slots.live())
    }
}
