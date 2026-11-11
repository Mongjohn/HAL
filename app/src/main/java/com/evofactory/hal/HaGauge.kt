package com.evofactory.hal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import org.json.JSONObject

/**
 * A dashboard's gauge card: the range from [min] to [max], and the colors of its [levels], each
 * from its value up to the next one's. Without a [needle], the gauge fills to the value in the
 * color of its level; with one, it shows every level's color and marks the value.
 */
class HaGauge(val min: Double, val max: Double, val needle: Boolean, val levels: List<GaugeLevel>) {
    /** The color for [value], as Home Assistant picks it: its level's, or blue below them all */
    fun colorAt(value: Double): Color = levels.lastOrNull { value >= it.from }?.color ?: GaugeNormal

    /** Where [value] lies on the gauge, from 0 to 1 */
    fun fraction(value: Double): Float =
        if (max <= min) 0f else ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

class GaugeLevel(val from: Double, val color: Color)

/**
 * Reads a gauge card: its min (default 0) and max (default 100), its needle, and its colors, as
 * "segments" (from, color) or the older "severity" (green, yellow and red, each from a value)
 */
fun parseGauge(card: JSONObject): HaGauge {
    val min = card.optDouble("min").takeUnless { it.isNaN() } ?: 0.0
    val max = card.optDouble("max").takeUnless { it.isNaN() } ?: 100.0
    val segments = card.optJSONArray("segments")
    val levels = if (segments != null) {
        (0 until segments.length()).mapNotNull { segments.optJSONObject(it) }.mapNotNull { segment ->
            val from = segment.optDouble("from").takeUnless { it.isNaN() } ?: return@mapNotNull null
            GaugeLevel(from, cssColor(segment.optString("color")) ?: GaugeNormal)
        }
    } else {
        card.optJSONObject("severity")?.let { severity ->
            severity.keys().asSequence().mapNotNull { key ->
                val color = SeverityColors[key] ?: return@mapNotNull null
                val from = severity.optDouble(key).takeUnless { it.isNaN() } ?: return@mapNotNull null
                GaugeLevel(from, color)
            }.toList()
        }.orEmpty()
    }
    return HaGauge(min, max, card.optBoolean("needle"), levels.sortedBy { it.from })
}

// Home Assistant's default theme: info, success, warning and error
private val GaugeNormal = Color(0xFF039BE5)
private val SeverityColors = mapOf(
    "green" to Color(0xFF43A047),
    "yellow" to Color(0xFFFFA600),
    "red" to Color(0xFFDB4437),
)

/** A gauge card's entity: its reading, as any sensor's, above a bar from min to max */
@Composable
fun GaugeReading(id: String, entity: HaEntity?, name: String?, gauge: HaGauge) {
    // Closer to its own reading than to the next one
    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SensorReading(id, entity, name)
        GaugeBar(gauge, entity?.state?.toDoubleOrNull())
    }
}

/** The bar: filled to [value] in its color, or every level's color with a mark at [value]. Empty without a value. */
@Composable
private fun GaugeBar(gauge: HaGauge, value: Double?) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val mark = MaterialTheme.colorScheme.onSurface
    val markEdge = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(if (gauge.needle) 16.dp else BarHeight)) {
        val bar = BarHeight.toPx()
        val top = (size.height - bar) / 2
        val round = CornerRadius(bar / 2)
        drawRoundRect(track, Offset(0f, top), Size(size.width, bar), round)
        if (gauge.needle) {
            val outline = Path().apply { addRoundRect(RoundRect(0f, top, size.width, top + bar, round)) }
            clipPath(outline) {
                gauge.levels.forEachIndexed { index, level ->
                    val start = gauge.fraction(level.from) * size.width
                    val end = gauge.levels.getOrNull(index + 1)?.let { gauge.fraction(it.from) * size.width } ?: size.width
                    if (end > start) drawRect(level.color, Offset(start, top), Size(end - start, bar))
                }
            }
            if (value != null) {
                val width = 4.dp.toPx()
                val edge = 2.dp.toPx()
                val x = (gauge.fraction(value) * size.width).coerceIn(width / 2 + edge, size.width - width / 2 - edge)
                drawRoundRect(markEdge, Offset(x - width / 2 - edge, 0f), Size(width + 2 * edge, size.height), CornerRadius(width / 2 + edge))
                drawRoundRect(mark, Offset(x - width / 2, edge), Size(width, size.height - 2 * edge), CornerRadius(width / 2))
            }
        } else if (value != null) {
            val fraction = gauge.fraction(value)
            // Rounded at both ends, so at least as wide as it's tall
            if (fraction > 0f) drawRoundRect(gauge.colorAt(value), Offset(0f, top), Size(maxOf(fraction * size.width, bar), bar), round)
        }
    }
}

private val BarHeight = 8.dp

/**
 * A color as CSS writes it, as a gauge's segments can have: "#e45", "#ee4455", "rgb(238, 68, 85)",
 * "hsl(355 83% 60%)", a name like "orange", or a Home Assistant theme color like
 * "var(--red-color)". Null if it can't be read.
 */
fun cssColor(text: String?): Color? {
    val css = text?.trim()?.lowercase()?.ifEmpty { null } ?: return null
    return when {
        css.startsWith("var(") -> {
            val inside = css.removePrefix("var(").removeSuffix(")")
            val name = inside.substringBefore(',').trim().removePrefix("--").removeSuffix("-color")
            ThemeColors[name] ?: cssColor(inside.substringAfter(',', ""))
        }
        css.startsWith("#") -> hexColor(css.drop(1))
        css.startsWith("rgb") || css.startsWith("hsl") -> {
            val parts = css.substringAfter('(').substringBefore(')').split(',', ' ', '/').filter { it.isNotBlank() }
            if (parts.size < 3) return null
            val alpha = parts.getOrNull(3)?.let { cssNumber(it, 1f) } ?: 1f
            if (css.startsWith("rgb")) {
                val (r, g, b) = parts.take(3).map { cssNumber(it, 255f) ?: return null }
                Color(r / 255f, g / 255f, b / 255f, alpha.coerceIn(0f, 1f))
            } else {
                val hue = parts[0].removeSuffix("deg").toFloatOrNull() ?: return null
                val (s, l) = parts.subList(1, 3).map { cssNumber(it, 1f) ?: return null }
                Color.hsl(((hue % 360) + 360) % 360, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
            }
        }
        else -> NamedColors[css]?.let { Color(it) }
    }
}

/** A number, or a percentage of [whole] */
private fun cssNumber(text: String, whole: Float): Float? =
    if (text.endsWith("%")) text.removeSuffix("%").toFloatOrNull()?.let { it / 100 * whole } else text.toFloatOrNull()

/** "e45", "e45f", "ee4455" or "ee4455ff", alpha last as in CSS */
private fun hexColor(hex: String): Color? {
    val full = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val value = full.toLongOrNull(16) ?: return null
    val rgba = if (full.length == 6) (value shl 8) or 0xFF else value
    return Color(
        red = ((rgba shr 24) and 0xFF).toInt(),
        green = ((rgba shr 16) and 0xFF).toInt(),
        blue = ((rgba shr 8) and 0xFF).toInt(),
        alpha = (rgba and 0xFF).toInt(),
    )
}

// Home Assistant's theme colors, by their name without "--" and "-color"
private val ThemeColors = mapOf(
    "error" to Color(0xFFDB4437), "warning" to Color(0xFFFFA600), "success" to Color(0xFF43A047),
    "info" to Color(0xFF039BE5), "primary" to Color(0xFF03A9F4), "accent" to Color(0xFFFF9800),
    "red" to Color(0xFFF44336), "pink" to Color(0xFFE91E63), "purple" to Color(0xFF926BC7),
    "deep-purple" to Color(0xFF6E41AB), "indigo" to Color(0xFF3F51B5), "blue" to Color(0xFF2196F3),
    "light-blue" to Color(0xFF03A9F4), "cyan" to Color(0xFF00BCD4), "teal" to Color(0xFF009688),
    "green" to Color(0xFF4CAF50), "light-green" to Color(0xFF8BC34A), "lime" to Color(0xFFCDDC39),
    "yellow" to Color(0xFFFFEB3B), "amber" to Color(0xFFFFC107), "orange" to Color(0xFFFF9800),
    "deep-orange" to Color(0xFFFF6F22), "brown" to Color(0xFF795548), "light-grey" to Color(0xFFBDBDBD),
    "grey" to Color(0xFF9E9E9E), "dark-grey" to Color(0xFF606060), "blue-grey" to Color(0xFF607D8B),
    "black" to Color(0xFF000000), "white" to Color(0xFFFFFFFF), "disabled" to Color(0xFFBDBDBD),
)

// CSS's color names most likely in a gauge
private val NamedColors = mapOf(
    "black" to 0xFF000000, "white" to 0xFFFFFFFF, "gray" to 0xFF808080, "grey" to 0xFF808080,
    "silver" to 0xFFC0C0C0, "lightgray" to 0xFFD3D3D3, "lightgrey" to 0xFFD3D3D3, "darkgray" to 0xFFA9A9A9,
    "darkgrey" to 0xFFA9A9A9, "red" to 0xFFFF0000, "darkred" to 0xFF8B0000, "crimson" to 0xFFDC143C,
    "maroon" to 0xFF800000, "orangered" to 0xFFFF4500, "tomato" to 0xFFFF6347, "coral" to 0xFFFF7F50,
    "orange" to 0xFFFFA500, "darkorange" to 0xFFFF8C00, "gold" to 0xFFFFD700, "yellow" to 0xFFFFFF00,
    "khaki" to 0xFFF0E68C, "olive" to 0xFF808000, "lime" to 0xFF00FF00, "limegreen" to 0xFF32CD32,
    "green" to 0xFF008000, "darkgreen" to 0xFF006400, "lightgreen" to 0xFF90EE90, "seagreen" to 0xFF2E8B57,
    "teal" to 0xFF008080, "cyan" to 0xFF00FFFF, "aqua" to 0xFF00FFFF, "turquoise" to 0xFF40E0D0,
    "skyblue" to 0xFF87CEEB, "lightblue" to 0xFFADD8E6, "dodgerblue" to 0xFF1E90FF, "blue" to 0xFF0000FF,
    "royalblue" to 0xFF4169E1, "navy" to 0xFF000080, "darkblue" to 0xFF00008B, "purple" to 0xFF800080,
    "violet" to 0xFFEE82EE, "magenta" to 0xFFFF00FF, "fuchsia" to 0xFFFF00FF, "indigo" to 0xFF4B0082,
    "pink" to 0xFFFFC0CB, "hotpink" to 0xFFFF69B4, "brown" to 0xFFA52A2A, "chocolate" to 0xFFD2691E,
)
