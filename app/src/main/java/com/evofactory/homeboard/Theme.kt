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

/** A step above surfaceVariant: menus over panels, and buttons on dialogs */
val RaisedSurface = Color(0xFF3D3C42)

/** A step below onSurfaceVariant: figures that are only there if you look for them */
val FaintText = Color(0xFF6E6C72)
