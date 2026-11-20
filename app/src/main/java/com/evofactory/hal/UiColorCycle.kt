package com.evofactory.hal

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

internal const val COLOR_INVERSION_DURATION_MS = 10 * 60 * 1000L
private const val COLOR_UPDATE_INTERVAL_MS = 500L
internal val CyclingSecondaryText = Color(0xFFD3DAE5)
internal val LocalUiColorCycle = staticCompositionLocalOf<State<UiColorCycle?>?> { null }

@Composable
internal fun rememberUiColorCycle(enabled: Boolean, active: Boolean): State<UiColorCycle?>? {
    if (!enabled) return null
    val cycle = remember { mutableStateOf<UiColorCycle?>(null) }
    val start = remember { SystemClock.uptimeMillis() }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            cycle.value = UiColorCycle(colorInversionProgress(SystemClock.uptimeMillis() - start))
            delay(COLOR_UPDATE_INTERVAL_MS)
        }
    }
    return cycle
}

internal fun colorInversionProgress(elapsed: Long): Float {
    val duration = COLOR_INVERSION_DURATION_MS
    val phase = elapsed % (2 * duration)
    return minOf(phase, 2 * duration - phase).toFloat() / duration
}

internal data class UiColorCycle(val progress: Float) {
    // Reverse chroma around luma so black stays black and light text stays light.
    // A gentle warming of neutrals also varies white text.
    private val chroma = 1f - 2f * progress
    private val lumaMix = 1f - chroma
    private val greenGain = 1f - 0.015f * progress
    private val blueGain = 1f - 0.06f * progress
    private val redLuma = 0.2126f * lumaMix
    private val greenLuma = 0.7152f * lumaMix
    private val blueLuma = 0.0722f * lumaMix

    val filter by lazy(LazyThreadSafetyMode.NONE) {
        if (progress == 0f) null else ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            redLuma + chroma, greenLuma, blueLuma, 0f, 0f,
            redLuma * greenGain, (greenLuma + chroma) * greenGain, blueLuma * greenGain, 0f, 0f,
            redLuma * blueGain, greenLuma * blueGain, (blueLuma + chroma) * blueGain, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    // Use the same transform on individual paints beside protected images or video.
    fun color(color: Color): Color {
        if (progress == 0f) return color
        val luma = redLuma * color.red + greenLuma * color.green + blueLuma * color.blue
        return color.copy(
            red = (luma + chroma * color.red).coerceIn(0f, 1f),
            green = ((luma + chroma * color.green) * greenGain).coerceIn(0f, 1f),
            blue = ((luma + chroma * color.blue) * blueGain).coerceIn(0f, 1f),
        )
    }
}

@Composable
internal fun Modifier.cycleUiColors(layerOutsets: LayerOutsets = LayerOutsets.Zero): Modifier {
    val cycle = LocalUiColorCycle.current ?: return this
    return graphicsLayer {
        colorFilter = cycle.value?.filter
        outsets = layerOutsets
    }
}

@Composable
internal fun cycleUiColor(color: Color): Color =
    LocalUiColorCycle.current?.value?.color(color) ?: color

@Composable
internal fun darkenCycleSurface(color: Color): Color {
    if (LocalUiColorCycle.current == null) return color
    return color.copy(red = color.red * 0.5f, green = color.green * 0.5f, blue = color.blue * 0.5f)
}

@Composable
internal fun readableCycleText(color: Color): Color =
    if (LocalUiColorCycle.current == null || LocalLauncherTheme.current != LauncherTheme.Glass) color
    else CyclingSecondaryText
