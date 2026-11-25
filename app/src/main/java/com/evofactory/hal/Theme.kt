package com.evofactory.hal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The launcher's colors, dimmed for a dark room. The library's components read these too, so
 * screens use the scheme rather than their own colors:
 * - surfaceVariant: panels, dialogs and inactive buttons
 * - onSurface: primary text
 * - onSurfaceVariant: secondary text and the text of inactive buttons
 */
private val GlassColorScheme = darkColorScheme(
    background = Color(0xFF101526),
    surface = Color(0xFF151A2B),
    surfaceVariant = Color(0xFF29354D),
    onSurface = Color(0xFFF6F8FF),
    onSurfaceVariant = Color(0xFFB7C3D8),
    inverseSurface = Color(0xFFE8EFFF),
    primary = Color(0xFFB2D5FF),
)
private val CyclingGlassColorScheme = GlassColorScheme.copy(onSurfaceVariant = CyclingSecondaryText)
internal val GlassDarkText = Color(0xFFB5BFCB)
internal val GlassDarkSecondary = Color(0xFF929EAC)
internal val GlassDarkFaint = Color(0xFF8693A2)
internal const val ArtworkShade = 0.66f
private val GlassDarkColorScheme = GlassColorScheme.copy(
    background = Color.Black,
    surface = Color(0xFF080B10),
    surfaceVariant = Color(0xFF111720),
    onSurface = GlassDarkText,
    onSurfaceVariant = GlassDarkSecondary,
    inverseSurface = Color(0xFF9DAABB),
    primary = Color(0xFF899FB9),
)

private val CalmColorScheme = GlassColorScheme.copy(
    background = Color.Black,
    surface = Color(0xFF0C1112),
    surfaceVariant = Color(0xFF1C2523),
    onSurface = Color(0xFFE0E5DC),
    onSurfaceVariant = Color(0xFFA5B1A8),
    primary = Color(0xFFB8C6A4),
    inverseSurface = Color(0xFFCBD5C4),
)

internal enum class LauncherTheme(val label: String) {
    Glass("Glass"),
    GlassDark("Glass Dark"),
    Calm("Calm"),
}

internal val LocalLauncherTheme = staticCompositionLocalOf { LauncherTheme.Glass }

internal const val DefaultCardOpacity = 50
internal val LocalDashboardCardOpacity = staticCompositionLocalOf { DefaultCardOpacity }
internal val LocalPopupCardOpacity = staticCompositionLocalOf { DefaultCardOpacity }
internal val LocalFocusedCardOpacity = staticCompositionLocalOf { DefaultCardOpacity }
internal val LocalDashboardForecast = staticCompositionLocalOf { ForecastDisplay.Off }
internal val LocalCardForecast = staticCompositionLocalOf { ForecastDisplay.Off }
internal val LocalScreensaverForecast = staticCompositionLocalOf { ForecastDisplay.Off }

@Composable
internal fun HalTheme(
    cardOpacity: Int = DefaultCardOpacity,
    uiColorCycle: State<UiColorCycle?>? = null,
    theme: LauncherTheme = LauncherTheme.Glass,
    focusedCardOpacity: Int? = null,
    unfocusedCardOpacity: Int? = null,
    dashboardForecast: ForecastDisplay = ForecastDisplay.Off,
    screensaverForecast: ForecastDisplay = ForecastDisplay.Off,
    cardForecast: ForecastDisplay = dashboardForecast,
    popupCardOpacity: Int? = focusedCardOpacity,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLauncherTheme provides theme,
        LocalDashboardCardOpacity provides (unfocusedCardOpacity ?: cardOpacity),
        LocalFocusedCardOpacity provides (focusedCardOpacity ?: cardOpacity),
        LocalPopupCardOpacity provides (popupCardOpacity ?: cardOpacity),
        LocalDashboardForecast provides dashboardForecast,
        LocalScreensaverForecast provides screensaverForecast,
        LocalCardForecast provides cardForecast,
        LocalUiColorCycle provides uiColorCycle,
    ) {
        val colors = when {
            theme == LauncherTheme.Calm -> CalmColorScheme
            theme == LauncherTheme.GlassDark -> GlassDarkColorScheme
            uiColorCycle != null -> CyclingGlassColorScheme
            else -> GlassColorScheme
        }
        MaterialTheme(colorScheme = colors, content = content)
    }
}

@Composable
internal fun Modifier.dashboardCard(color: Color, shape: Shape, cyclePaints: Boolean = false, focused: Boolean = false): Modifier {
    val alpha = (if (focused) LocalFocusedCardOpacity.current else LocalDashboardCardOpacity.current) / 100f
    if (LocalLauncherTheme.current == LauncherTheme.Calm) {
        val fill = Color(0xFF101719).copy(alpha = alpha)
        val rim = Color(0xFFB8C6BF).copy(alpha = 0.13f)
        return background(if (cyclePaints) cycleUiColor(fill) else fill, shape)
            .border(1.dp, if (cyclePaints) cycleUiColor(rim) else rim, shape)
    }
    val dark = LocalLauncherTheme.current == LauncherTheme.GlassDark
    val cycle = if (cyclePaints) LocalUiColorCycle.current?.value else null
    val top = darkenCycleSurface(if (dark) Color(0xFF1B2635) else Color(0xFF3B4C67))
    val middle = darkenCycleSurface(color)
    val bottom = darkenCycleSurface(if (dark) Color(0xFF070A10) else Color(0xFF141D30))
    val highlight = cycle?.color(Color.White) ?: Color.White
    val fill = remember(alpha, cycle, top, middle, bottom) {
        Brush.verticalGradient(
            0f to (cycle?.color(top) ?: top).copy(alpha = alpha),
            0.4f to (cycle?.color(middle) ?: middle).copy(alpha = alpha),
            1f to (cycle?.color(bottom) ?: bottom).copy(alpha = alpha),
        )
    }
    val rim = remember(dark, highlight) {
        Brush.verticalGradient(listOf(highlight.copy(alpha = if (dark) 0.16f else 0.26f), highlight.copy(alpha = 0.08f)))
    }
    return background(fill, shape).border(1.dp, rim, shape)
}

@Composable
private fun glassColor(glass: Long, dark: Long): Color =
    Color(when (LocalLauncherTheme.current) {
        LauncherTheme.Glass -> glass
        LauncherTheme.GlassDark -> dark
        LauncherTheme.Calm -> when (glass) {
            0xFFA49DAA -> 0xFFB8C6B7
            0xFF312F35 -> 0xFF232D2B
            0xFF403D45 -> 0xFF303C37
            else -> dark
        }
    })

internal val DashboardFocusColor: Color
    @Composable get() = glassColor(0xFFA49DAA, 0xFF8597AE)
internal val DashboardFocusStroke: BorderStroke
    @Composable get() {
        val color = DashboardFocusColor
        return remember(color) { BorderStroke(2.dp, color) }
    }
internal val DashboardFocusBorder: Border
    @Composable get() {
        val stroke = DashboardFocusStroke
        return remember(stroke) { Border(stroke) }
    }
internal val DashboardPanelOutline: Color
    @Composable get() = glassColor(0xFF514D56, 0xFF384451)
internal val DashboardClockColor: Color
    @Composable get() = glassColor(0xFF89868D, 0xFF8D9BAD)

/** A step above surfaceVariant: menus over panels, buttons on dialogs, the card gone into */
val RaisedSurface: Color
    @Composable get() = glassColor(0xFF312F35, 0xFF171D26)

/** A step above RaisedSurface: buttons on a raised card */
val HigherSurface: Color
    @Composable get() = glassColor(0xFF403D45, 0xFF202834)

/** A switch that's on: its track, the blue of the remote's blue button, and its knob */
val SwitchOn = Color(0xFF5A8FD9)
val SwitchKnob = Color(0xFFEDEBF0)

/** A step below onSurfaceVariant: figures that are only there if you look for them */
val FaintText: Color
    @Composable get() = when (LocalLauncherTheme.current) {
        LauncherTheme.Glass -> Color(0xFF6E6C72)
        LauncherTheme.GlassDark -> GlassDarkFaint
        LauncherTheme.Calm -> Color(0xFF98A69B)
    }

/** Home Assistant state hues on the dashboard's dark surfaces. */
@Composable
internal fun HaStateTone.color(neutral: Color): Color = when (this) {
    HaStateTone.Neutral -> neutral
    HaStateTone.Active -> glassColor(0xFFFFC107, 0xFFC89C40)
    HaStateTone.Good -> glassColor(0xFF8DBD9B, 0xFF78A787)
    HaStateTone.Caution -> glassColor(0xFFE9A16D, 0xFFC58F68)
    HaStateTone.Alert -> glassColor(0xFFE39491, 0xFFC0807E)
    HaStateTone.Blue -> glassColor(0xFF92B2DC, 0xFF7D9DC2)
    HaStateTone.Cyan -> glassColor(0xFF80C9D7, 0xFF71ACB8)
    HaStateTone.Purple -> glassColor(0xFFC2A1E6, 0xFFA792C4)
    HaStateTone.Teal -> glassColor(0xFF78C7B7, 0xFF6BAC9D)
}
