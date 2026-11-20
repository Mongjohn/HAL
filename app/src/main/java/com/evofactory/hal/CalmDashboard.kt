package com.evofactory.hal

import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Presentation only: saved grid geometry and all control implementations belong to the dashboard. */
@Composable
internal fun CalmDashboard(
    connection: HaConnection,
    cards: List<SensorCard>,
    options: Map<String, DashboardCard>,
    entities: Map<String, HaEntity>,
    names: Map<String, String>,
    up: FocusRequester,
    targets: Map<String, FocusRequester>,
    railTargets: Map<String, FocusRequester>,
    homeAppsTarget: FocusRequester,
    openCard: String?,
    onOpen: (String?) -> Unit,
    onFocused: (String, Boolean) -> Unit,
    onPanelRemoteKey: (KeyEvent, () -> Unit) -> Boolean,
    renderApps: (@Composable (DashboardCard, FocusRequester, FocusRequester, Dp, (FocusDirection) -> FocusRequester) -> Unit)?,
    renderCard: @Composable (SensorCard, Dp) -> Unit,
) {
    val rooms = cards.filter { options[it.key]?.apps == null && !it.isMediaOnly }
        .sortedWith(compareBy({ options[it.key]?.y ?: 0 }, { options[it.key]?.x ?: 0 }))
    val players = calmPlayerSlots(cards, options, entities)
    val firstPlayer = players.firstOrNull { !it.hidden }?.let { targets[it.cardKey] }
    val opacity = LocalDashboardCardOpacity.current / 100f
    val apps = cards.firstOrNull { options[it.key]?.apps != null && renderApps != null }?.let { options[it.key] }
    var selected by remember { mutableStateOf<String?>(null) }
    val overview = rooms.firstOrNull { it.key == selected } ?: rooms.firstOrNull()
    var lastOpen by remember { mutableStateOf<String?>(null) }
    var restoreSummaryFocus by remember { mutableStateOf(true) }
    LaunchedEffect(openCard) {
        if (openCard == null && lastOpen != null && restoreSummaryFocus) {
            val previous = lastOpen
            withFrameNanos { }
            if (runCatching { railTargets[previous]?.requestFocus() }.getOrNull() != true) {
                runCatching { up.requestFocus() }
            }
        }
        if (openCard == null) restoreSummaryFocus = true
        lastOpen = openCard
    }
    BoxWithConstraints(Modifier.fillMaxSize()
        .graphicsLayer { alpha = if (openCard == null) 1f else 0f }
        .padding(horizontal = 8.dp, vertical = 12.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (overview != null) {
                    val visible = calmVisibleEntities(overview, options[overview.key], entities)
                    val reading = visible.firstOrNull { it.domain in setOf("weather", "climate", "sensor", "media_player") }
                    CalmOverview(calmTitle(overview, visible, names),
                        reading?.let { it.currentTemperature ?: it.temperature?.let { t -> "${t.toInt()}°" } ?: it.reading }
                            ?: calmSummary(visible),
                        reading?.let { if (it.domain == "weather") weatherLabel(it.state) ?: it.reading else names[it.id] ?: it.name },
                        Modifier.fillMaxSize())
                } else {
                    CalmOverview("", "HAL", "Make yourself at home", Modifier.fillMaxSize())
                }
            }
            if (players.isNotEmpty()) {
                CalmMediaRow(players, connection, names, targets, up,
                    railTargets[overview?.key] ?: apps?.let { targets[it.card.key] } ?: up,
                    onFocused, Modifier.fillMaxWidth().height(CalmMediaTileHeight).padding(horizontal = 5.dp))
            }
            if (rooms.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rooms.forEachIndexed { index, card ->
                        val visible = calmVisibleEntities(card, options[card.key], entities)
                        val entity = visible.firstOrNull()
                        val cue = entity?.let { haStateCue(it.domain, it.deviceClass, it.state) }
                        val lockState = calmLockState(card, options[card.key], entities)
                        Surface(
                            onClick = { onOpen(card.key) },
                            modifier = Modifier.width(186.dp).height(68.dp)
                                .focusRequester(railTargets.getValue(card.key))
                                .onPreviewKeyEvent { event ->
                                    val destination = when (event.key) {
                                        Key.DirectionUp -> firstPlayer ?: up
                                        Key.DirectionDown -> apps?.let { targets[it.card.key] } ?: FocusRequester.Cancel
                                        Key.DirectionLeft -> rooms.getOrNull(index - 1)?.let { railTargets[it.key] } ?: FocusRequester.Cancel
                                        Key.DirectionRight -> rooms.getOrNull(index + 1)?.let { railTargets[it.key] } ?: FocusRequester.Cancel
                                        else -> null
                                    }
                                    when {
                                        destination != null -> {
                                            if (event.type == KeyEventType.KeyDown && destination != FocusRequester.Cancel) {
                                                destination.requestFocus()
                                            }
                                            true
                                        }
                                        event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                                            if (event.type == KeyEventType.KeyUp) onOpen(card.key)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .onFocusChanged {
                                    if (it.hasFocus) selected = card.key
                                    onFocused(card.key, it.hasFocus)
                                }.focusProperties {
                                    this.up = firstPlayer ?: up
                                    down = apps?.let { targets[it.card.key] } ?: FocusRequester.Cancel
                                },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, cycleUiColor(DashboardFocusColor)))),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = cycleUiColor(Color(0xFF101719).copy(alpha = opacity)), focusedContainerColor = cycleUiColor(RaisedSurface),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                focusedContentColor = MaterialTheme.colorScheme.onSurface),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxSize().cycleUiColors().padding(12.dp).padding(end = if (lockState != null) 18.dp else 0.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(30.dp).background(Color(0x263C4C40), RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center) {
                                        MdiIcon(if (entity?.domain == "weather") entity.icon else cue?.icon ?: entity?.icon ?: "view-dashboard-outline", size = 19.dp,
                                            color = cue?.tone?.color(MaterialTheme.colorScheme.onSurfaceVariant)
                                                ?: MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(calmTitle(card, visible, names), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(calmSummary(visible), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (lockState != null) {
                                    val lockCue = haStateCue("lock", null, lockState)!!
                                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp)
                                        .semantics { contentDescription = "Lock: $lockState" }) {
                                        MdiIcon(lockCue.icon, size = 16.dp,
                                            color = calmLockColor(lockState))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (apps != null && renderApps != null) {
                // Keep the configured tile columns, item rules, long press, idle collapse and Home entry.
                val appsHeight = 96.dp
                Box(Modifier.fillMaxWidth().height(appsHeight).padding(horizontal = 5.dp)) {
                    renderApps(apps, targets.getValue(apps.card.key), homeAppsTarget, appsHeight) { direction ->
                        if (direction == FocusDirection.Up) railTargets[overview?.key] ?: firstPlayer ?: up else FocusRequester.Cancel
                    }
                }
            }
        }
    }
    rooms.firstOrNull { it.key == openCard }?.let { card ->
        Dialog(onDismissRequest = { onOpen(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyUp) onOpen(null)
                        true
                    } else onPanelRemoteKey(event) {
                        restoreSummaryFocus = false
                        onOpen(null)
                    }
                }
                .padding(40.dp), contentAlignment = Alignment.Center) {
                val panelHeight = maxHeight * 0.85f
                Box(Modifier.width(minOf(maxWidth, 760.dp)).heightIn(max = maxHeight)) {
                    renderCard(card, panelHeight)
                }
            }
        }
    }
}

internal fun calmVisibleEntities(card: SensorCard, options: DashboardCard?, entities: Map<String, HaEntity>): List<HaEntity> =
    card.ids.mapIndexedNotNull { index, id ->
        entities[id]?.takeIf { options?.itemVisibility?.get(index.toString())?.rule.isVisible(entities) }
    }

internal fun calmSummary(entities: List<HaEntity>): String {
    if (entities.isEmpty()) return "No visible readings"
    val lights = entities.filter { it.domain == "light" }
    if (lights.isNotEmpty()) {
        val on = lights.count { it.state == "on" }
        return when {
            on > 0 -> "$on ${if (on == 1) "light" else "lights"} on"
            lights.all { it.state == "off" } -> "Lights off"
            else -> "Lights unavailable"
        }
    }
    return entities.firstOrNull { !it.isAction }?.reading ?: "${entities.size} actions"
}

internal fun calmTitle(card: SensorCard, visible: List<HaEntity>, names: Map<String, String>): String =
    card.title.ifBlank { visible.firstOrNull()?.let { names[it.id] ?: it.name } ?: "Card" }

/** Unlocked takes priority; an uncertain lock must never be presented as securely locked. */
internal fun calmLockState(card: SensorCard, options: DashboardCard?, entities: Map<String, HaEntity>): String? {
    val states = card.ids.mapIndexedNotNull { index, id ->
        if (id.startsWith("lock.") && options?.itemVisibility?.get(index.toString())?.rule.isVisible(entities))
            entities[id]?.state ?: "unknown" else null
    }
    if (states.isEmpty()) return null
    return when {
        "unlocked" in states -> "unlocked"
        "jammed" in states -> "jammed"
        states.all { it == "locked" } -> "locked"
        else -> states.firstOrNull { it != "locked" } ?: "unknown"
    }
}

// Semantic lock colors stay stable while decorative interface colors cycle.
internal fun calmLockColor(state: String): Color = when (state) {
    "locked" -> Color(0xFF638C6A)
    "unlocked", "jammed" -> Color(0xFFA65C57)
    "locking", "unlocking", "opening" -> Color(0xFF9C855B)
    else -> Color(0xFF7C8783)
}
