package com.evofactory.hal

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HaLightColorTest {
    private val amber = Color(0xFFFFC107)
    private fun light(id: String, state: String, rgb: List<Int>? = null, mode: String = "rgb") = HaEntity("light.$id", state,
        JSONObject().put("color_mode", mode).apply { if (rgb != null) put("rgb_color", JSONArray(rgb)) })

    @Test fun reportedRgbIsIndependentOfUiToneAndNeverReusesOffColor() {
        val red = light("red", "on", listOf(255, 0, 0))
        assertEquals(Color.Red, reportedLightColor(red))
        assertNull(reportedLightColor(light("red", "off", listOf(255, 0, 0))))
        assertNull(reportedLightColor(light("red", "unavailable", listOf(255, 0, 0))))
        assertNull(reportedLightColor(light("red", "on", listOf(255, 0, 0), "brightness")))
        assertNull(reportedLightColor(light("broken", "on", listOf(-1, 0, 255))))
    }

    @Test fun summariesKeepAUniformOnColorAndUseDefaultForMixedOnLights() {
        val red = light("red", "on", listOf(255, 0, 0))
        val anotherRed = light("other", "on", listOf(255, 0, 0))
        val blue = light("blue", "on", listOf(0, 0, 255))
        assertEquals(Color.Red, summaryLightColor(listOf(red, anotherRed), amber))
        assertEquals(amber, summaryLightColor(listOf(red, blue), amber))
        assertEquals(Color.Red, summaryLightColor(listOf(red, light("off", "off", listOf(0, 0, 255))), amber))
        assertEquals(amber, summaryLightColor(listOf(red, light("plain", "on")), amber))
        assertNull(summaryLightColor(listOf(light("off", "off")), amber))
    }
}
