package com.evofactory.hal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val DashboardMaxRows = 360

data class DashboardCard(
    val card: SensorCard,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val twoPerRow: Boolean = false,
)

data class DashboardPage(val title: String, val columns: Int, val cards: List<DashboardCard>)

data class DashboardLayout(
    val pages: List<DashboardPage>,
    val names: Map<String, String> = emptyMap(),
    val bottomRow: Boolean = false,
    val aliases: Map<String, String> = emptyMap(),
) {
    val entityIds get() = HaDashboard(
        pages.map { DashboardView(it.title, it.cards.map(DashboardCard::card)) }, names, emptyList(),
    ).entityIds

    fun toJson() = JSONObject()
        .put("bottomRow", bottomRow)
        .put("names", JSONObject(names))
        .put("aliases", JSONObject(aliases))
        .put("pages", JSONArray(pages.map { page ->
            JSONObject().put("title", page.title).put("columns", page.columns)
                .put("cards", JSONArray(page.cards.map { item ->
                    val card = item.card
                    JSONObject().put("id", card.key).put("title", card.title)
                        .put("x", item.x).put("y", item.y).put("w", item.width).put("h", item.height)
                        .put("twoPerRow", item.twoPerRow).put("entities", JSONArray(card.ids))
                        .put("subtitles", JSONObject(card.subtitles.mapKeys { it.key.toString() }))
                        .put("gauges", JSONObject(card.gauges.mapValues { (_, gauge) ->
                            JSONObject().put("min", gauge.min).put("max", gauge.max).put("needle", gauge.needle)
                                .put("levels", JSONArray(gauge.levels.map { level ->
                                    JSONObject().put("from", level.from).put("color", level.color.toArgb())
                                }))
                        }))
                        .put("graphs", JSONObject(card.graphs.mapValues { (_, graph) ->
                            JSONObject().put("hours", graph.hours).put("minY", graph.minY)
                                .put("maxY", graph.maxY).put("fitY", graph.fitY)
                                .put("entities", JSONArray(graph.entities.map { entity ->
                                    JSONObject().put("id", entity.id).put("name", entity.name)
                                        .put("color", entity.color?.toArgb())
                                }))
                        }))
                }))
        }))
}

data class DashboardLayouts(val assistant: DashboardLayout? = null, val home: DashboardLayout? = null) {
    fun toJson() = JSONObject().put("version", 1)
        .put("assistant", assistant?.toJson()).put("home", home?.toJson())

    companion object {
        fun fromJson(json: JSONObject): DashboardLayouts {
            require(json.getInt("version") == 1)
            return DashboardLayouts(
                json.optJSONObject("assistant")?.let(::dashboardLayoutFromJson),
                json.optJSONObject("home")?.let(::dashboardLayoutFromJson),
            )
        }
    }
}

private fun JSONObject.entries() = keys().asSequence().toList()
private fun JSONObject.text(key: String, max: Int = 160) = getString(key).also { require(it.length <= max) }
private fun JSONObject.number(key: String, range: IntRange): Int {
    val value = get(key) as? Number ?: throw IllegalArgumentException(key)
    return value.toInt().also { require(it in range && value.toDouble() == it.toDouble()) }
}
private fun <T> JSONArray.items(max: Int, read: JSONArray.(Int) -> T): List<T> {
    require(length() <= max)
    return List(length()) { read(it) }
}

internal fun dashboardLayoutFromJson(json: JSONObject): DashboardLayout {
    val pages = json.getJSONArray("pages").items(32) { index ->
        val page = getJSONObject(index)
        val columns = page.number("columns", 1..6)
        val cards = page.getJSONArray("cards").items(150) { cardIndex ->
            val item = getJSONObject(cardIndex)
            val x = item.number("x", 0 until columns)
            val y = item.number("y", 0 until DashboardMaxRows)
            val width = item.number("w", 1..(columns - x))
            val height = item.number("h", 1..minOf(30, DashboardMaxRows - y))
            val ids = item.getJSONArray("entities").items(250) { getString(it).also { id -> require(id.length in 1..255) } }
            val subtitles = item.optJSONObject("subtitles") ?: JSONObject()
            val gauges = item.optJSONObject("gauges") ?: JSONObject()
            val graphs = item.optJSONObject("graphs") ?: JSONObject()
            DashboardCard(
                SensorCard(
                    item.text("id", 255).also { require(it.isNotBlank()) }, item.text("title"), ids,
                    subtitles.entries().mapNotNull { key ->
                        key.toIntOrNull()?.takeIf { it in ids.indices }?.let { it to subtitles.text(key) }
                    }.toMap(),
                    ids.filter(gauges::has).associateWith { id ->
                        val gauge = gauges.getJSONObject(id)
                        val min = gauge.getDouble("min")
                        val max = gauge.getDouble("max")
                        require(min.isFinite() && max.isFinite() && max > min)
                        HaGauge(min, max, gauge.optBoolean("needle"), gauge.getJSONArray("levels").items(100) {
                            getJSONObject(it).let { level -> GaugeLevel(level.getDouble("from"), Color(level.getInt("color"))) }
                        })
                    },
                    ids.filter(::isGraphKey).associateWith { id ->
                        val graph = graphs.getJSONObject(id)
                        HaGraph(graph.getJSONArray("entities").items(50) {
                            getJSONObject(it).let { entity ->
                                GraphEntity(entity.text("id", 255), entity.optString("name").ifEmpty { null },
                                    if (entity.has("color")) Color(entity.getInt("color")) else null)
                            }
                        }, graph.getDouble("hours").also { require(it in 0.01..8760.0) },
                            graph.optDouble("minY").takeIf(Double::isFinite),
                            graph.optDouble("maxY").takeIf(Double::isFinite), graph.optBoolean("fitY"))
                    },
                ), x, y, width, height, item.optBoolean("twoPerRow"),
            )
        }
        require(cards.map { it.card.key }.distinct().size == cards.size)
        val occupied = mutableSetOf<Int>()
        for (card in cards) for (y in card.y until card.y + card.height) for (x in card.x until card.x + card.width) {
            require(occupied.add(y * columns + x)) { "Cards overlap" }
        }
        DashboardPage(page.text("title"), columns, cards)
    }
    require(pages.isNotEmpty())
    fun readNames(key: String): Map<String, String> {
        val names = json.optJSONObject(key) ?: return emptyMap()
        require(names.length() <= 5000)
        return names.entries().associateWith { names.text(it, 255) }
    }
    return DashboardLayout(pages, readNames("names"), json.optBoolean("bottomRow"), readNames("aliases"))
}

internal fun seedDashboardLayout(
    board: HaDashboard?,
    selection: List<SensorCard>,
    columns: Int,
    twoPerRow: Boolean,
    bottomRow: Boolean = false,
): DashboardLayout {
    val views = board?.views?.ifEmpty { null } ?: listOf(DashboardView("Overview", selection))
    val pages = views.flatMap { view ->
        val groups = view.cards.chunked(minOf(150, (DashboardMaxRows / 12) * columns))
        groups.ifEmpty { listOf(emptyList()) }.mapIndexed { index, cards ->
            val nextRow = IntArray(columns)
            val title = if (index == 0) view.title.take(160) else view.title.take(150) + " (" + (index + 1) + ")"
            DashboardPage(title, columns, cards.map { card ->
                val x = nextRow.indices.minBy { nextRow[it] }
                val y = nextRow[x]
                val height = (1 + card.entityRows(twoPerRow).size + card.subtitles.size +
                    card.graphs.size * 3 + card.ids.count(::isHaWeather) * 4).coerceIn(2, 12)
                nextRow[x] += height
                DashboardCard(
                    SensorCard(UUID.randomUUID().toString(), card.title.take(160), card.ids, card.subtitles, card.gauges, card.graphs),
                    x, y, 1, height, twoPerRow,
                )
            })
        }
    }
    return DashboardLayout(pages, board?.names.orEmpty(), bottomRow)
}
