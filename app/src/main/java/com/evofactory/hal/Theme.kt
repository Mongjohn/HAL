package com.evofactory.hal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
val AppColorScheme = darkColorScheme(
    background = Color(0xFF111014),
    surface = Color(0xFF111014),
    surfaceVariant = Color(0xFF222125),
    onSurface = Color(0xFFC4C1C6),
    onSurfaceVariant = Color(0xFF9A979D),
    inverseSurface = Color(0xFFC4C1C6),
)

internal enum class DashboardTheme(val id: String, val label: String, val defaultOpacity: Int) {
    Classic("classic", "Classic", 100),
    Glass("glass", "Glass", 50);

    val colors get() = when (this) {
        Classic -> AppColorScheme
        Glass -> GlassColorScheme
    }
}

private val GlassColorScheme = darkColorScheme(
    background = Color(0xFF101526),
    surface = Color(0xFF151A2B),
    surfaceVariant = Color(0xFF29354D),
    onSurface = Color(0xFFF6F8FF),
    onSurfaceVariant = Color(0xFFDDE5F5),
    inverseSurface = Color(0xFFE8EFFF),
    primary = Color(0xFFB2D5FF),
)

internal data class DashboardAppearance(
    val theme: DashboardTheme = DashboardTheme.Classic,
    val cardOpacity: Int? = null,
) {
    val opacity get() = cardOpacity ?: theme.defaultOpacity
}

internal val LocalDashboardAppearance = staticCompositionLocalOf { DashboardAppearance() }

@Composable
internal fun HalTheme(appearance: DashboardAppearance, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDashboardAppearance provides appearance) {
        MaterialTheme(colorScheme = appearance.theme.colors, content = content)
    }
}

internal fun Modifier.dashboardCard(
    appearance: DashboardAppearance,
    color: Color,
    shape: Shape,
): Modifier {
    val alpha = appearance.opacity / 100f
    return when (appearance.theme) {
        DashboardTheme.Classic -> background(color.copy(alpha = alpha), shape)
        DashboardTheme.Glass -> background(
            Brush.verticalGradient(
                0f to Color(0xFF536681).copy(alpha = alpha),
                0.4f to color.copy(alpha = alpha),
                1f to Color(0xFF141D30).copy(alpha = alpha),
            ),
            shape,
        ).border(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.48f), Color.White.copy(alpha = 0.12f))),
            shape,
        )
    }
}

internal val DashboardFocusColor = Color(0xFFA49DAA)
internal val DashboardFocusStroke = BorderStroke(2.dp, DashboardFocusColor)
internal val DashboardFocusBorder = Border(DashboardFocusStroke)
internal val DashboardPanelOutline = Color(0xFF514D56)
internal val DashboardClockColor = Color(0xFF89868D)

/** A step above surfaceVariant: menus over panels, buttons on dialogs, the card gone into */
val RaisedSurface = Color(0xFF312F35)

/** A step above RaisedSurface: buttons on a raised card */
val HigherSurface = Color(0xFF403D45)

/** A switch that's on: its track, the blue of the remote's blue button, and its knob */
val SwitchOn = Color(0xFF5A8FD9)
val SwitchKnob = Color(0xFFEDEBF0)

/** A step below onSurfaceVariant: figures that are only there if you look for them */
val FaintText = Color(0xFF6E6C72)

/** Home Assistant state hues on the dashboard's dark surfaces. */
internal fun HaStateTone.color(neutral: Color): Color = when (this) {
    HaStateTone.Neutral -> neutral
    HaStateTone.Active -> Color(0xFFFFC107)
    HaStateTone.Good -> Color(0xFF8DBD9B)
    HaStateTone.Caution -> Color(0xFFE9A16D)
    HaStateTone.Alert -> Color(0xFFE39491)
    HaStateTone.Blue -> Color(0xFF92B2DC)
    HaStateTone.Cyan -> Color(0xFF80C9D7)
    HaStateTone.Purple -> Color(0xFFC2A1E6)
    HaStateTone.Teal -> Color(0xFF78C7B7)
}
