package com.evofactory.homeboard

import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme

/**
 * The launcher's colors, dimmed for a dark room. The library's components read these too, so
 * screens use the scheme rather than their own colors:
 * - surfaceVariant: panels, dialogs and inactive buttons
 * - onSurface: text, and the fill of the item the remote is on
 * - onSurfaceVariant: secondary text and the text of inactive buttons
 * - border: the outline of the item the remote is on
 */
val AppColorScheme = darkColorScheme(
    surfaceVariant = Color(0xFF2B2A2F),
    onSurface = Color(0xFFC4C1C6),
    onSurfaceVariant = Color(0xFF9A979D),
    inverseSurface = Color(0xFFC4C1C6),
)

/** A step above surfaceVariant: menus over panels, buttons on dialogs, the card gone into */
val RaisedSurface = Color(0xFF3D3C42)

/** A step above RaisedSurface: buttons on a raised card */
val HigherSurface = Color(0xFF504E55)

/** A switch that's on: its track, the blue of the remote's blue button, and its knob */
val SwitchOn = Color(0xFF5A8FD9)
val SwitchKnob = Color(0xFFEDEBF0)

/** A step below onSurfaceVariant: figures that are only there if you look for them */
val FaintText = Color(0xFF6E6C72)
