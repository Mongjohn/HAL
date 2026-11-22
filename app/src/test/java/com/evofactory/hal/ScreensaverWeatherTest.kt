package com.evofactory.hal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScreensaverWeatherTest {
    private fun weather(id: String, state: String) = HaEntity(id, state, JSONObject().put("friendly_name", "My local weather"))

    @Test fun prefersConfiguredWeatherAndKeepsItsName() {
        val entities = mapOf("weather.a" to weather("weather.a", "rainy"), "weather.b" to weather("weather.b", "sunny"))
        val selected = screensaverWeather(listOf("weather.b"), entities)
        assertEquals("weather.b", selected?.id)
        assertEquals("My local weather", selected?.name)
    }

    @Test fun discoversStableFallbackAndDoesNotUseMissingStateAsCurrent() {
        val entities = mapOf("weather.b" to weather("weather.b", "sunny"), "weather.a" to weather("weather.a", "cloudy"),
            "weather.home" to weather("weather.home", "unavailable"))
        assertEquals("weather.a", screensaverWeather(listOf("weather.home"), entities)?.id)
        assertEquals("weather.a", screensaverWeather(emptyList(), entities)?.id)
        assertNull(screensaverWeather(emptyList(), emptyMap()))
    }
}
