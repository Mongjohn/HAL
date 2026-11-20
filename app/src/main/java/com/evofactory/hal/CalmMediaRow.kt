package com.evofactory.hal

import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch

// Same natural single-player height as Glass: 76dp artwork and 4dp above/below.
internal val CalmMediaTileHeight = MediaArtworkNaturalSide + MediaPanelVerticalPadding * 2

internal data class CalmPlayerSlot(val cardKey: String, val id: String, val hidden: Boolean, val heading: String = "")

internal fun calmPlayerSlots(cards: List<SensorCard>, options: Map<String, DashboardCard>, entities: Map<String, HaEntity>): List<CalmPlayerSlot> =
    cards.filter { it.isMediaOnly }.sortedWith(compareBy({ options[it.key]?.y ?: 0 }, { options[it.key]?.x ?: 0 })).flatMap { card ->
        var previousVisible = -1
        val lastVisible = card.ids.indices.lastOrNull { options[card.key]?.itemVisibility?.get(it.toString())?.rule.isVisible(entities) }
        card.ids.mapIndexedNotNull { index, id ->
            val setting = options[card.key]?.itemVisibility?.get(index.toString())
            val hidden = !setting?.rule.isVisible(entities)
            val headings = if (hidden) emptyList() else buildList {
                if (previousVisible < 0 && card.title.isNotBlank()) add(card.title)
                (previousVisible + 1..index).mapNotNullTo(this, card.subtitles::get)
                if (index == lastVisible) (index + 1..card.ids.size).mapNotNullTo(this, card.subtitles::get)
                previousVisible = index
            }
            if (hidden && setting?.keepSpace != true) null else CalmPlayerSlot(card.key, id, hidden, headings.joinToString(" · "))
        }
    }

internal enum class CalmMediaAction(val label: String, val icon: String, val service: String, val feature: Int) {
    Previous("Previous", "skip-previous", "media_previous_track", 16),
    Pause("Pause", "pause", "media_pause", 1),
    Play("Play", "play", "media_play", 16384),
    Stop("Stop", "stop", "media_stop", 4096),
    Next("Next", "skip-next", "media_next_track", 32),
}

internal fun calmMediaActionSupported(state: String?, features: Int, action: CalmMediaAction): Boolean =
    state in setOf("playing", "paused", "idle", "on", "buffering") &&
        features and action.feature != 0 && (action != CalmMediaAction.Pause || state == "playing")

/** Equal-width players in one row; artwork uses the existing bounded, off-main-thread decoder. */
@Composable
internal fun CalmMediaRow(
    slots: List<CalmPlayerSlot>,
    connection: HaConnection,
    names: Map<String, String>,
    targets: Map<String, FocusRequester>,
    up: FocusRequester,
    down: FocusRequester,
    onFocused: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<CalmPlayerSlot?>(null) }
    val visible = slots.filterNot { it.hidden }.map { it.cardKey to it.id }.toSet()
    val latestVisible by rememberUpdatedState(visible)
    LaunchedEffect(visible) {
        val lost = focused?.takeIf { (it.cardKey to it.id) !in visible } ?: return@LaunchedEffect
        focused = null
        withFrameNanos { }
        val next = slots.firstOrNull { !it.hidden && it.cardKey == lost.cardKey } ?: slots.firstOrNull { !it.hidden }
        runCatching { (targets[next?.cardKey] ?: down).requestFocus() }
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        slots.forEachIndexed { index, slot ->
            key(slot.cardKey, slot.id) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxSize()) {
                    if (slot.hidden) return@BoxWithConstraints
                    val entity = connection.entities[slot.id]
                    val wrapControls = maxWidth < 420.dp
                    val firstInCard = slots.indexOfFirst { it.cardKey == slot.cardKey && !it.hidden } == index
                    Row(Modifier.fillMaxSize()
                        .onFocusChanged {
                            if (it.hasFocus) focused = slot
                            else if (focused?.let { old -> old.cardKey == slot.cardKey && old.id == slot.id } == true &&
                                (slot.cardKey to slot.id) in latestVisible) focused = null
                            onFocused(slot.cardKey, it.hasFocus)
                        }
                        .focusGroup()
                        .dashboardCard(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp), cyclePaints = true)
                        .padding(horizontal = 10.dp, vertical = MediaPanelVerticalPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val playerName = names[slot.id] ?: entity?.name ?: slot.id
                        MediaPlayerReading(slot.id, entity,
                            if (slot.heading.isBlank()) playerName else "${slot.heading} · $playerName", connection.session,
                            compact = true, modifier = Modifier.weight(1f).fillMaxHeight(),
                            showPrimaryActionIcon = false, showProgress = true)
                        CalmTransportButtons(slot, entity, firstInCard, targets, up, down, connection, wrapControls) { action ->
                            scope.launch {
                                val latest = connection.entities[slot.id]
                                if (!calmMediaActionSupported(latest?.state, latest?.attributes?.optInt("supported_features") ?: 0, action)) return@launch
                                val session = connection.session ?: return@launch
                                try { session.callService(slot.id, action.service) }
                                catch (e: HaException) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalmTransportButtons(
    slot: CalmPlayerSlot, entity: HaEntity?, firstInCard: Boolean, targets: Map<String, FocusRequester>,
    up: FocusRequester, down: FocusRequester, connection: HaConnection, wrap: Boolean, onAction: (CalmMediaAction) -> Unit,
) {
    val middle = if (entity?.state == "playing") CalmMediaAction.Pause else CalmMediaAction.Play
    val actions = listOf(CalmMediaAction.Previous, middle, CalmMediaAction.Stop, CalmMediaAction.Next)
    val buttons = remember(slot.cardKey, slot.id) { List(4) { FocusRequester() } }
    val columns = if (wrap) 2 else 4
    Column(Modifier.width(if (wrap) 72.dp else 148.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.chunked(columns).forEachIndexed { row, group ->
            Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.forEachIndexed { column, action ->
                    val index = row * columns + column
                    val ready = connection.session != null && calmMediaActionSupported(entity?.state, entity?.attributes?.optInt("supported_features") ?: 0, action)
                    Surface(onClick = { if (ready) onAction(action) },
                        modifier = Modifier.weight(1f).height(32.dp)
                            .then(if (firstInCard && index == 0) Modifier.focusRequester(targets.getValue(slot.cardKey)) else Modifier)
                            .focusRequester(buttons[index])
                            .focusProperties {
                                this.up = if (row > 0) buttons[index - columns] else up
                                this.down = if (index + columns < actions.size) buttons[index + columns] else down
                            }
                            .semantics { contentDescription = "${action.label}: ${entity?.name ?: slot.id}${if (ready) "" else " (unavailable)"}" }
                            .cycleUiColors(),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        border = ClickableSurfaceDefaults.border(focusedBorder = DashboardFocusBorder),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = RaisedSurface,
                            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (ready) 1f else 0.35f),
                            focusedContainerColor = HigherSurface, focusedContentColor = MaterialTheme.colorScheme.onSurface),
                    ) {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            MdiIcon(action.icon, size = 19.dp)
                        }
                    }
                }
            }
        }
    }
}
