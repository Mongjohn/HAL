package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryNumberRangeTest {
    private fun check(vararg series: List<String>) {
        val points = series.map { states -> states.mapIndexed { index, state -> HaHistoryPoint(index.toLong(), state) } }
        val numbers = points.flatMap { line -> line.mapNotNull { it.state.toDoubleOrNull() } }
        val expected = (numbers.minOrNull() ?: 0.0) to (numbers.maxOrNull() ?: 1.0)
        assertEquals(expected, historyNumberRange(points))
    }

    @Test fun emptyAndNonNumericHistoryKeepDefaultRange() {
        check()
        check(emptyList(), listOf("unknown", "unavailable", "on", ""))
    }

    @Test fun numericHistoryKeepsOriginalExtremaAcrossLines() {
        check(listOf("4"))
        check(listOf("-4", "unknown", "12.5", "12.5"), listOf("-5.7", "0", "8"))
        check((0..10_000).map { (it - 5000).toString() })
    }

    @Test fun specialFloatingPointValuesKeepOriginalScaleSemantics() {
        check(listOf("Infinity", "-Infinity", "3"))
        check(listOf("3", "NaN", "-5"))
        check(listOf("NaN", "3"))
        check(listOf("0.0", "-0.0"))
    }
}
