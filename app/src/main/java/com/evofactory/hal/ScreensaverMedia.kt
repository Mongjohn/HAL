package com.evofactory.hal

data class ScreensaverMediaPlayer(val id: String, val name: String?)

/** Reconstruct the last Home view without composing a launcher or relying on its lifetime. */
internal fun screensaverViewPlayers(
    layout: DashboardLayout?, board: HaDashboard?, selection: HaCardLayout, ids: List<String>,
    viewIndex: Int, entities: Map<String, HaEntity>,
): List<ScreensaverMediaPlayer> {
    val page = layout?.pages?.getOrNull(viewIndex) ?: layout?.pages?.firstOrNull()
    val view = board?.views?.getOrNull(viewIndex) ?: board?.views?.firstOrNull()
    val cards = when {
        layout != null -> page?.cards.orEmpty().map { it.card }
        board != null -> view?.cards.orEmpty()
        else -> selection.selectionCards(ids.filterNot(::isHaAction), entities)
    }
    val names = (layout?.names ?: board?.names.orEmpty()) + layout?.aliases.orEmpty()
    return screensaverMediaPlayers(cards, page?.cards.orEmpty().associateBy { it.card.key }, entities, names)
}

/** The current view's player readings, including mixed cards; visibility always wins. */
internal fun screensaverMediaPlayers(
    cards: List<SensorCard>, options: Map<String, DashboardCard>, entities: Map<String, HaEntity>, names: Map<String, String>,
): List<ScreensaverMediaPlayer> = cards
    .filter { it.visibility.isVisible(entities) && options[it.key]?.apps == null }
    .sortedWith(compareBy({ options[it.key]?.y ?: 0 }, { options[it.key]?.x ?: 0 }))
    .flatMap { card ->
        card.ids.mapIndexedNotNull { index, id ->
            if (isHaMediaPlayer(id) && id !in card.gauges && id !in card.graphs &&
                options[card.key]?.itemVisibility?.get(index.toString())?.rule.isVisible(entities))
                ScreensaverMediaPlayer(id, names[id]) else null
        }
    }.distinctBy { it.id }
