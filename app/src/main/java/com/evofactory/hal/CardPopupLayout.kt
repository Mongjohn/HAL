package com.evofactory.hal

/** One column halves the old 760 dp popup; two columns retain its original width. */
internal fun calmPopupWidth(columns: Int): Int = if (columns == 2) 760 else 380

/** Preserve slot order, reserved spaces, full-width widgets and heading boundaries in the popup. */
internal fun SensorCard.popupRows(positions: List<Int>, columns: Int): List<List<Int>> =
    itemRows(positions, twoPerRow = columns == 2, halfWidthItems = null)
