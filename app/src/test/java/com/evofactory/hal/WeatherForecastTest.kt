package com.evofactory.hal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WeatherForecastTest {
    private val today = LocalDate.of(2026, 11, 23)
    private fun day(offset: Long, hour: Int = 12) = HaForecast(JSONObject()
        .put("datetime", today.plusDays(offset).atTime(hour, 0).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString())
        .put("condition", "sunny").put("temperature", 12).put("templow", 4))

    @Test fun hourlyForecastSortsDeduplicatesAndExcludesExpiredHours() {
        val now = today.atTime(13, 30).atZone(java.time.ZoneId.systemDefault())
        val hours = hourlyForecast(listOf(day(0, 15), day(0, 12), day(0, 14), day(0, 13), day(0, 14), day(1)), now)
        assertEquals(listOf(13, 14, 15, 12), hours.map { it.time!!.hour })
        assertTrue(hourlyForecast(null, now).isEmpty())
    }

    @Test fun hintIsCenteredBetweenTheIconAndCardRim() {
        val inset = 10f; val tileInset = 2f; val height = 2f
        val center = inset - appsRowHintOutset(inset, tileInset, height) + height / 2
        assertEquals(inset + tileInset - center, center, 0.001f)
    }

    @Test fun selectsFiveDistinctUpcomingLocalDatesInOrder() {
        val input = listOf(day(3), day(-1), day(0), day(1, 18), day(1), day(6), day(5), day(4), day(2))
        assertEquals((0L..4L).map { today.plusDays(it) }, fiveDayForecast(input, today).map { it.time?.toLocalDate() })
    }

    @Test fun missingAndExpiredDatesDoNotBecomeInventedForecasts() {
        val malformed = HaForecast(JSONObject().put("datetime", "not a date"))
        assertTrue(fiveDayForecast(listOf(day(-2), malformed), today).isEmpty())
        assertEquals(2, fiveDayForecast(listOf(day(1), day(3)), today).size)
        assertTrue(fiveDayForecast(null, today).isEmpty())
    }
}
