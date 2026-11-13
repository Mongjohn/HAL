package com.evofactory.hal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A dashboard's history graph card: its [entities] over the last [hours]. [minY] and [maxY] bound
 * its charts' scale, which grows past them to fit the data only with [fitY].
 */
class HaGraph(val entities: List<GraphEntity>, val hours: Double, val minY: Double?, val maxY: Double?, val fitY: Boolean)

/** An entity of a history graph, with the name and color the card gives it, if any */
class GraphEntity(val id: String, val name: String?, val color: Color?)

/** A state an entity took at [time], in milliseconds */
data class HaHistoryPoint(val time: Long, val state: String)

/** A history graph's place among a card's entities: see [SensorCard.graphs] */
fun isGraphKey(id: String) = id.startsWith("graph:")

/** Reads a history graph card: its entities (as strings or { entity, name, color } rows), hours_to_show and y axis */
fun parseGraph(card: JSONObject): HaGraph {
    val rows = card.optJSONArray("entities")
    val entities = (0 until (rows?.length() ?: 0)).mapNotNull { index ->
        when (val row = rows?.opt(index)) {
            is String -> GraphEntity(row, null, null)
            is JSONObject -> row.text("entity")?.let { GraphEntity(it, row.text("name"), cssColor(row.text("color"))) }
            else -> null
        }
    }.filter { it.id.contains('.') }.distinctBy { it.id }
    return HaGraph(
        entities,
        hours = card.optDouble("hours_to_show").takeIf { it > 0 } ?: 24.0,
        minY = card.optDouble("min_y_axis").takeUnless { it.isNaN() },
        maxY = card.optDouble("max_y_axis").takeUnless { it.isNaN() },
        fitY = card.optBoolean("fit_y_data"),
    )
}

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

// History is read again this often; changes in between are added as they come
private const val HISTORY_REFRESH_MS = 5 * 60_000L

/**
 * A history graph, as Home Assistant draws it: entities with a unit as lines, one chart per unit,
 * each with its entities below in their line's color; the others (on and off, open and closed,
 * states in words) as a bar per entity, colored by state over time
 */
@Composable
fun HistoryGraph(connection: HaConnection, graph: HaGraph, names: Map<String, String>, aliases: Map<String, String>) {
    val session = connection.session
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val start = now - (graph.hours * 3_600_000).roundToLong()
    var history by remember(graph) { mutableStateOf<Map<String, List<HaHistoryPoint>>>(emptyMap()) }
    // Changes since the history was read
    val live = remember(graph) { mutableStateMapOf<String, List<HaHistoryPoint>>() }
    LaunchedEffect(session, graph) {
        if (session == null) return@LaunchedEffect
        while (true) {
            val since = System.currentTimeMillis() - (graph.hours * 3_600_000).roundToLong()
            try {
                history = session.history(graph.entities.map { it.id }, since)
                live.clear()
            } catch (_: HaException) {
                // Logged by the exception; what was read stays
            }
            delay(HISTORY_REFRESH_MS)
        }
    }
    LaunchedEffect(graph) {
        snapshotFlow { graph.entities.map { connection.entities[it.id]?.state } }.collect { states ->
            val time = System.currentTimeMillis()
            graph.entities.forEachIndexed { index, entity ->
                val state = states[index] ?: return@forEachIndexed
                val changes = live[entity.id].orEmpty()
                if (changes.lastOrNull()?.state != state) live[entity.id] = changes + HaHistoryPoint(time, state)
            }
        }
    }
    // The right edge is now
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    val series = graph.entities.associate { entity ->
        val points = (history[entity.id].orEmpty() + live[entity.id].orEmpty()).sortedBy { it.time }
        // Each change once, from the state at the window's start
        val changes = points.filterIndexed { index, point -> index == 0 || points[index - 1].state != point.state }
        val before = changes.lastOrNull { it.time <= start }?.copy(time = start)
        entity.id to listOfNotNull(before) + changes.filter { it.time > start }
    }
    // As Home Assistant: a line for what has a unit and a number, a bar for the rest
    val (lineEntities, barEntities) = graph.entities.partition { entity ->
        val state = connection.entities[entity.id]
        (state?.unit != null || state?.attributes?.has("state_class") == true) &&
            series[entity.id].orEmpty().any { it.state.toDoubleOrNull() != null }
    }
    val colors = MaterialTheme.colorScheme
    // One chart per unit, each line in the color the card gives it, or the next of Home Assistant's
    var colorIndex = 0
    val charts = lineEntities.groupBy { connection.entities[it.id]?.unit }.map { (unit, entities) ->
        val lines = entities.map { it to (it.color ?: GraphColors[colorIndex++ % GraphColors.size]) }
        Chart(unit, lines, lines.map { (entity, color) -> color to series[entity.id].orEmpty() }, graph)
    }
    // The scales' values as wide as the widest, so the charts line up
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val labelWidth = with(density) {
        (charts.flatMap { listOf(it.highLabel, it.lowLabel) }.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0).toDp()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        charts.forEach { chart ->
            LineChart(chart, start, now, labelWidth)
            chart.entities.forEach { (entity, color) ->
                SensorReading(entity.id, connection.entities[entity.id], aliases[entity.id] ?: entity.name ?: names[entity.id], iconColor = color)
            }
        }
        barEntities.forEach { entity ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SensorReading(entity.id, connection.entities[entity.id], aliases[entity.id] ?: entity.name ?: names[entity.id])
                val current = connection.entities[entity.id]
                // Each state in words, as the entity's reading would say it, e.g. "Open" for a door
                TimelineBar(series[entity.id].orEmpty(), start, now, colors.onSurface.copy(alpha = 0.12f)) { state ->
                    HaEntity(entity.id, state, current?.attributes ?: JSONObject()).reading
                }
            }
        }
        Text(
            "Last ${duration(graph.hours)}",
            style = MaterialTheme.typography.bodySmall,
            color = FaintText,
        )
    }
}

/**
 * A chart of a graph: its [entities] with their color, and their [lines]' points. Its scale goes
 * from the lowest value to the highest, or the graph's own bounds.
 */
private class Chart(
    unit: String?,
    val entities: List<Pair<GraphEntity, Color>>,
    val lines: List<Pair<Color, List<HaHistoryPoint>>>,
    graph: HaGraph,
) {
    val low: Double
    val high: Double

    init {
        val numbers = lines.flatMap { (_, points) -> points.mapNotNull { it.state.toDoubleOrNull() } }
        var low = numbers.minOrNull() ?: 0.0
        var high = numbers.maxOrNull() ?: 1.0
        graph.minY?.let { low = if (graph.fitY) minOf(it, low) else it }
        graph.maxY?.let { high = if (graph.fitY) maxOf(it, high) else it }
        if (high - low < 1e-9) {
            low -= 1
            high += 1
        }
        this.low = low
        this.high = high
    }

    val highLabel = axisValue(high, unit)
    val lowLabel = axisValue(low, unit)
}

/** A chart's lines from [start] to [end], with its scale's top and bottom at the left, [labelWidth] wide */
@Composable
private fun LineChart(chart: Chart, start: Long, end: Long, labelWidth: Dp) {
    val low = chart.low
    val high = chart.high
    val lines = chart.lines
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Row(Modifier.fillMaxWidth().height(ChartHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(labelWidth).fillMaxHeight()) {
            Text(chart.highLabel, style = MaterialTheme.typography.labelSmall, color = FaintText, maxLines = 1, modifier = Modifier.align(Alignment.TopEnd))
            Text(chart.lowLabel, style = MaterialTheme.typography.labelSmall, color = FaintText, maxLines = 1, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            fun x(time: Long) = (time - start).toFloat() / (end - start).coerceAtLeast(1) * size.width
            fun y(value: Double) = inset + (1 - ((value - low) / (high - low)).toFloat().coerceIn(0f, 1f)) * (size.height - 2 * inset)
            listOf(inset, size.height / 2, size.height - inset).forEach { lineY ->
                drawLine(grid, Offset(0f, lineY), Offset(size.width, lineY), strokeWidth = 1.dp.toPx())
            }
            lines.forEach { (color, points) ->
                val path = Path()
                var drawing = false
                points.forEach { point ->
                    val value = point.state.toDoubleOrNull()
                    // No value, e.g. unavailable: a gap in the line
                    if (value == null) {
                        drawing = false
                    } else {
                        if (drawing) path.lineTo(x(point.time), y(value)) else path.moveTo(x(point.time), y(value))
                        drawing = true
                    }
                }
                // The last value lasts until now
                points.lastOrNull()?.state?.toDoubleOrNull()?.let { if (drawing) path.lineTo(size.width, y(it)) }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * An entity's states from [start] to [end] as colored stretches of a bar, each with its [label]
 * where it fits; nothing where it had no state
 */
@Composable
private fun TimelineBar(points: List<HaHistoryPoint>, start: Long, end: Long, track: Color, label: (String) -> String) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    Canvas(Modifier.fillMaxWidth().height(TimelineHeight)) {
        val round = CornerRadius(4.dp.toPx())
        val padding = 4.dp.toPx()
        fun x(time: Long) = ((time - start).toFloat() / (end - start).coerceAtLeast(1) * size.width).coerceIn(0f, size.width)
        drawRoundRect(track, cornerRadius = round)
        val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, round)) }
        clipPath(outline) {
            points.forEachIndexed { index, point ->
                val color = stateColor(point.state) ?: return@forEachIndexed
                val from = x(point.time)
                val to = points.getOrNull(index + 1)?.let { x(it.time) } ?: size.width
                if (to <= from) return@forEachIndexed
                drawRect(color, Offset(from, 0f), Size(to - from, size.height))
                val text = measurer.measure(label(point.state), style, maxLines = 1)
                if (text.size.width + 2 * padding <= to - from) {
                    drawText(
                        text,
                        color = if (color.luminance() > 0.5f) Color.Black else Color.White,
                        topLeft = Offset(from + padding, (size.height - text.size.height) / 2),
                    )
                }
            }
        }
    }
}

/** A state's color on a bar: amber when on, grey when off, none without a state, else one of the graph colors */
private fun stateColor(state: String): Color? = when (state) {
    "unavailable", "unknown", "" -> null
    in ActiveStates -> Color(0xFFFFC107)
    in InactiveStates -> Color(0xFF616161)
    else -> GraphColors[abs(state.hashCode()) % GraphColors.size]
}

private val ActiveStates = setOf("on", "open", "home", "playing", "detected", "unlocked", "heat", "cool", "heat_cool", "auto", "cleaning")
private val InactiveStates = setOf("off", "closed", "not_home", "idle", "standby", "paused", "locked", "docked", "clear")

// Home Assistant's graph colors, in its order
private val GraphColors = listOf(
    Color(0xFF4285F4), Color(0xFFDB4437), Color(0xFFF4B400), Color(0xFF0F9D58), Color(0xFFAB47BC), Color(0xFF00ACC1),
    Color(0xFFFF7043), Color(0xFF9E9D24), Color(0xFF5C6BC0), Color(0xFFF06292), Color(0xFF00796B), Color(0xFFC2185B),
)

private val ChartHeight = 80.dp
private val TimelineHeight = 20.dp

/** A scale's value: whole from 100 on, else to one decimal, without a trailing ".0" */
private fun axisValue(value: Double, unit: String?): String {
    val number = if (abs(value) >= 100) value.roundToInt().toString() else ((value * 10).roundToInt() / 10.0).toString().removeSuffix(".0")
    return listOfNotNull(number, unit).joinToString(" ")
}

/** "24 hours", "1 hour", "30 minutes" */
private fun duration(hours: Double): String = when {
    hours < 1 -> "${(hours * 60).roundToInt()} minutes"
    hours == 1.0 -> "hour"
    hours % 1 == 0.0 -> "${hours.toInt()} hours"
    else -> "$hours hours"
}
