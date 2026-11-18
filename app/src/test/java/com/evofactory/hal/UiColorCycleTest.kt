package com.evofactory.hal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiColorCycleTest {
    @Test
    fun glassDarkTextStaysReadableOverItsDarkSurface() {
        // Artwork now uses Glass's lighter shade; this guarantee applies to opaque dark surfaces.
        val backdrop = Color(0xFF111720)
        for (step in 0..100) {
            val cycle = UiColorCycle(step / 100f)
            for (text in listOf(GlassDarkText, GlassDarkSecondary, GlassDarkFaint)) {
                assertTrue((cycle.color(text).luminance() + 0.05f) / (backdrop.luminance() + 0.05f) >= 4.5f)
            }
        }
    }

    @Test
    fun progressReversesAndRepeats() {
        val duration = COLOR_INVERSION_DURATION_MS
        val samples = (0L..8L).map { colorInversionProgress(it * duration / 4) }
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 0.75f, 0.5f, 0.25f, 0f), samples)
        assertEquals(0.25f, colorInversionProgress(9 * duration / 4), 0f)
    }

    @Test
    fun darkSurfacesAndLightTextKeepTheirContrastAcrossTheCycle() {
        val brightBackdrop = Color(0xFF575757)
        for (step in 0..100) {
            val cycle = UiColorCycle(step / 100f)
            assertEquals(Color.Black, cycle.color(Color.Black))
            assertTrue(cycle.color(Color(0xFF29354D)).luminance() < 0.06f)
            val text = cycle.color(CyclingSecondaryText)
            assertTrue((text.luminance() + 0.05f) / (brightBackdrop.luminance() + 0.05f) >= 4.5f)
        }
    }

    @Test
    fun colorReversesChromaAndPreservesAlpha() {
        val original = Color(0x80204060)
        assertEquals(original, UiColorCycle(0f).color(original))
        val reversed = UiColorCycle(1f).color(original)
        assertEquals(original.alpha, reversed.alpha, 0f)
        assertTrue(reversed.red > reversed.green)
        assertTrue(reversed.green > reversed.blue)
    }
}
