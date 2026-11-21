package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsRowIndicatorsTest {
    @Test fun followsReachableRowsAndSkipsReservedHiddenRows() {
        val rows = listOf(listOf("a", "b"), listOf("hidden"), listOf("c"), listOf("hidden2"))
        val hidden = setOf("hidden", "hidden2")
        assertEquals(false to true, appsRowIndicators(rows, hidden, 0))
        assertEquals(true to false, appsRowIndicators(rows, hidden, 2))
        assertEquals(false to false, appsRowIndicators(rows, hidden + setOf("a", "b", "c"), 2))
        assertEquals(false to false, appsRowIndicators(emptyList(), emptySet(), 0))
    }

    @Test fun showsBothDirectionsOnlyForMiddleRows() {
        val rows = listOf(listOf("a"), listOf("b"), listOf("c"))
        assertEquals(true to true, appsRowIndicators(rows, emptySet(), 1))
        assertEquals(false to false, appsRowIndicators(listOf(listOf("a")), emptySet(), 0))
    }
}
