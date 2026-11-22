package com.evofactory.hal

data class ScreensaverMediaPlayer(val id: String, val name: String?)

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
