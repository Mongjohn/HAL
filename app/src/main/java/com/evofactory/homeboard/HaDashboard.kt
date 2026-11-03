package com.evofactory.homeboard

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
    val entityIds get() = views.flatMap { view -> view.cards.flatMap { it.ids } }.distinct()
    val cardCount get() = views.sumOf { it.cards.size }
}

class DashboardView(val title: String, val cards: List<SensorCard>)

/** The dashboard [key] as Home Assistant's lovelace/config wants it: no url path for the default one */
fun dashboardUrlPath(key: String): String? = key.ifEmpty { null }

/**
 * Reads a dashboard's layout as Home Assistant stores it ([config]) into cards for the Assistant
 * tab. Null for a dashboard Home Assistant builds itself (a "strategy"): there's no layout to read.
 *
 * Each view's cards are read in order, stacks and grids flattened: a heading (or a titled card)
 * starts a new card with its title, untitled cards add their entities to the card being built, and
 * a weather forecast is a card of its own. Sections end a card. Cards it can't show are listed
 * in [HaDashboard.skipped].
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
        val sections = view.optJSONArray("sections")
        if (sections != null) {
            sections.objects().forEach { section ->
                section.optJSONArray("cards").objects().forEach(cards::read)
                cards.close()
            }
        }
        view.optJSONArray("cards").objects().forEach(cards::read)
        cards.close()
        val title = view.string("title") ?: view.string("path") ?: "View ${index + 1}"
        DashboardView(title, cards.cards).takeIf { it.cards.isNotEmpty() }
    }
    return HaDashboard(views, names, skipped.toList())
}

private class CardBuilder(private val prefix: String, private val names: MutableMap<String, String>, private val skipped: MutableSet<String>) {
    val cards = mutableListOf<SensorCard>()
    private var title: String? = null
    private val ids = mutableListOf<String>()

    fun add(id: String?, name: String? = null) {
        if (id == null || !id.contains('.')) return
        if (id !in ids) ids += id
        if (name != null) names[id] = name
    }

    /** Ends the card being built, if it has anything */
    fun close() {
        if (ids.isNotEmpty()) cards += SensorCard("$prefix-${cards.size}", title.orEmpty(), ids.toList())
        title = null
        ids.clear()
    }

    private fun startCard(newTitle: String?) {
        close()
        title = newTitle
    }

    fun read(card: JSONObject) {
        when (val type = card.optString("type")) {
            "heading" -> {
                startCard(card.string("heading"))
                card.optJSONArray("badges")?.items()?.forEach { add(entityOf(it)) }
            }
            in Stacks -> {
                card.string("title")?.let(::startCard)
                card.optJSONArray("cards").objects().forEach(::read)
            }
            "conditional" -> card.optJSONObject("card")?.let(::read)
            "weather-forecast" -> card.string("entity")?.let { id ->
                close()
                cards += SensorCard("$prefix-${cards.size}", card.string("name") ?: "", listOf(id))
            }
            in ListCards -> {
                val cardTitle = card.string("title")
                if (cardTitle != null) startCard(cardTitle)
                card.optJSONArray("entities")?.items()?.forEach { row ->
                    add(entityOf(row), (row as? JSONObject)?.string("name"))
                }
                if (cardTitle != null) close()
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
    "gauge", "alarm-panel", "media-control", "picture-entity", "plant-status",
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
