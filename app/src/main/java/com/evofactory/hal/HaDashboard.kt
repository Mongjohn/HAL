package com.evofactory.hal

import org.json.JSONArray
import org.json.JSONObject

/** A dashboard of Home Assistant, by its [urlPath]: null for its default one, Overview */
class HaDashboardInfo(val urlPath: String?, val title: String) {
    /** How it's saved in the settings: its url path, or "" for the default one */
    val key get() = urlPath.orEmpty()
}

/**
 * A dashboard as the Assistant tab shows it: its [views], each a list of cards, the [names] its
 * cards give entities in place of their own, and the types of the cards it can't show, e.g.
 * markdown or custom cards, once each.
 */
class HaDashboard(val views: List<DashboardView>, val names: Map<String, String>, val skipped: List<String>) {
    /** Every entity on it, to follow */
    val entityIds
        get() = views.flatMap { view ->
            view.cards.flatMap { card -> card.ids.filterNot(::isGraphKey) + card.graphs.values.flatMap { graph -> graph.entities.map { it.id } } }
        }.distinct()
    val cardCount get() = views.sumOf { it.cards.size }
}

class DashboardView(val title: String, val cards: List<SensorCard>)

enum class HaCardGrouping(val label: String) {
    Area("Area / room"),
    Type("Entity type"),
    None("One card"),
}

data class HaCardLayout(
    val grouping: HaCardGrouping = HaCardGrouping.Area,
    val alphabetical: Boolean = false,
    val twoPerRow: Boolean = false,
)

/**
 * What the Assistant tab and the Home tab's panel show of Home Assistant: each a [dashboard] (its
 * key, see [HaDashboardInfo.key]) or, if null, the sensors and actions chosen; the Assistant tab
 * with or without the [bottomRow], each with its sections in columns or not.
 */
data class HaDisplay(
    val dashboard: String? = null,
    val bottomRow: Boolean = false,
    val sectionColumns: Boolean = false,
    val homeDashboard: String? = null,
    val homeColumns: Boolean = true,
    val layout: HaCardLayout = HaCardLayout(),
    val homeLayout: HaCardLayout = HaCardLayout(),
) {
    /** The dashboards to read, each once */
    val dashboards get() = listOfNotNull(dashboard, homeDashboard).distinct()
}

/** The dashboard [key] as Home Assistant's lovelace/config wants it: no url path for the default one */
fun dashboardUrlPath(key: String): String? = key.ifEmpty { null }

/**
 * Reads a dashboard's layout as Home Assistant stores it ([config]) into cards for the Assistant
 * tab. Null for a dashboard Home Assistant builds itself (a "strategy"): there's no layout to read.
 *
 * Each block of the dashboard becomes one card, as Home Assistant shows it: a section, in a view
 * of sections, or else a card at the view's top level, stacks and grids flattened. A heading (or
 * titled card) at a block's top titles the card, and later ones head their part of it (see
 * [SensorCard.subtitles]). A weather forecast shows inside its block, or as a weather card when
 * it's alone. A gauge shows as a bar, in its colors, and a history graph as charts and timelines. The badges are a card at the top. Cards it can't show are listed in
 * [HaDashboard.skipped].
 */
fun parseDashboard(config: JSONObject?): HaDashboard? {
    if (config == null || config.has("strategy")) return null
    val names = mutableMapOf<String, String>()
    val skipped = linkedSetOf<String>()
    val views = config.optJSONArray("views").objects().mapIndexedNotNull { index, view ->
        if (view.has("strategy")) {
            skipped += "view built by Home Assistant"
            return@mapIndexedNotNull null
        }
        val cards = CardBuilder("v$index", names, skipped)
        // The view's badges first: entities shown at its top
        view.optJSONArray("badges")?.let { badges ->
            badges.items().forEach { cards.add(entityOf(it)) }
            cards.close()
        }
        // A section is one block, whatever it holds; so is each card of a view without sections
        view.optJSONArray("sections").objects().forEach { section ->
            section.optJSONArray("cards").objects().forEach(cards::read)
            cards.close()
        }
        view.optJSONArray("cards").objects().forEach { card ->
            cards.read(card)
            cards.close()
        }
        val title = view.string("title") ?: view.string("path") ?: "View ${index + 1}"
        DashboardView(title, cards.cards).takeIf { it.cards.isNotEmpty() }
    }
    return HaDashboard(views, names, skipped.toList())
}

private class CardBuilder(private val prefix: String, private val names: MutableMap<String, String>, private val skipped: MutableSet<String>) {
    val cards = mutableListOf<SensorCard>()
    private var title: String? = null
    private val ids = mutableListOf<String>()
    private val subtitles = mutableMapOf<Int, String>()
    private val gauges = mutableMapOf<String, HaGauge>()
    private val graphs = mutableMapOf<String, HaGraph>()

    fun add(id: String?, name: String? = null) {
        if (id == null || !id.contains('.')) return
        if (id !in ids) ids += id
        if (name != null) names[id] = name
    }

    /** Ends the card being built, if it has anything; a heading with nothing after it is dropped */
    fun close() {
        if (ids.isNotEmpty()) {
            cards += SensorCard("$prefix-${cards.size}", title.orEmpty(), ids.toList(), subtitles.filterKeys { it < ids.size }, gauges.toMap(), graphs.toMap())
        }
        title = null
        ids.clear()
        subtitles.clear()
        gauges.clear()
        graphs.clear()
    }

    /** A heading: the card's title at its top, otherwise the title of the part that follows */
    private fun heading(text: String?) {
        if (text == null) return
        if (ids.isEmpty() && subtitles.isEmpty() && title == null) title = text else subtitles[ids.size] = text
    }

    fun read(card: JSONObject) {
        when (val type = card.optString("type")) {
            "heading" -> {
                heading(card.string("heading"))
                card.optJSONArray("badges")?.items()?.forEach { add(entityOf(it)) }
            }
            in Stacks -> {
                heading(card.string("title"))
                card.optJSONArray("cards").objects().forEach(::read)
            }
            "conditional" -> card.optJSONObject("card")?.let(::read)
            "weather-forecast" -> add(card.string("entity"), card.string("name"))
            in ListCards -> {
                heading(card.string("title"))
                card.optJSONArray("entities")?.items()?.forEach { row ->
                    add(entityOf(row), (row as? JSONObject)?.string("name"))
                }
            }
            "gauge" -> {
                val id = card.string("entity")
                add(id, card.string("name"))
                if (id != null && id in ids) gauges[id] = parseGauge(card)
            }
            "history-graph" -> {
                heading(card.string("title"))
                val graph = parseGraph(card)
                if (graph.entities.isEmpty()) return
                // In the card's entities, in its place
                val key = "graph:${graphs.size}"
                ids += key
                graphs[key] = graph
            }
            in EntityCards -> {
                val id = card.string("entity")
                // Without an entity, e.g. a button that opens another page: nothing to show
                if (id != null) add(id, card.string("name")) else skipped += type
            }
            else -> skipped += type.ifEmpty { "card without a type" }
        }
    }
}

private val Stacks = setOf("vertical-stack", "horizontal-stack", "grid")

// Cards listing entities, as strings or { entity, name } rows
private val ListCards = setOf("entities", "glance")

// Cards showing one entity: shown as its reading, or a button for scenes, scripts and buttons
private val EntityCards = setOf(
    "tile", "entity", "sensor", "button", "entity-button", "light", "thermostat", "humidifier",
    "alarm-panel", "media-control", "picture-entity", "plant-status",
)

/** The entity of a row or badge: "light.kitchen", or { "entity": "light.kitchen" } */
private fun entityOf(item: Any?): String? = when (item) {
    is String -> item
    is JSONObject -> item.string("entity")
    else -> null
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray.items(): List<Any?> = (0 until length()).map { opt(it) }

/** The string at [key]; null if missing, null or empty */
private fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
