package com.evofactory.hal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun DashboardAppsCard(
    card: DashboardCard,
    installed: List<InstalledApp>,
    entities: Map<String, HaEntity>,
    target: FocusRequester,
    homeTarget: FocusRequester,
    height: Dp,
    collapse: AppsCardCollapse,
    lastApp: String?,
    neighbor: (FocusDirection) -> FocusRequester,
    onAppFocused: (String) -> Unit,
    onOpenAllApps: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember(installed) { installed.associateBy { it.key } }
    val keys = card.apps.orEmpty().filter { it == ALL_APPS_TILE_KEY || it in apps }
        .ifEmpty { listOf(ALL_APPS_TILE_KEY) }
    val hidden = keys.filter { !card.itemVisibility[it]?.rule.isVisible(entities) }.toSet()
    val visible = keys.filterNot(hidden::contains)
    val columns = card.appsPerRow
    val rows = keys.filter { it !in hidden || card.itemVisibility[it]?.keepSpace == true }.chunked(columns)
    val requesters = remember(keys) { keys.associateWith { FocusRequester() } }
    val first = visible.firstOrNull()
    val entry = lastApp?.takeIf { it in visible } ?: first
    val currentVisible by rememberUpdatedState(visible)
    var focusedApp by remember { mutableStateOf<String?>(null) }
    var emptyFocused by remember { mutableStateOf(false) }
    var menuApp by remember { mutableStateOf<String?>(null) }
    val collapsed = collapse != AppsCardCollapse.Off && focusedApp == null && !emptyFocused && menuApp == null
    val collapseProgress by animateFloatAsState(
        if (collapsed) 1f else 0f,
        animationSpec = tween(180),
        label = "Apps card collapse",
    )
    LaunchedEffect(visible) {
        if (menuApp !in visible) menuApp = null
        if (focusedApp?.let { it !in visible } == true || emptyFocused && first != null) {
            focusedApp = null
            emptyFocused = false
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }

    fun verticalNeighbor(row: Int, column: Int, direction: FocusDirection): FocusRequester {
        val candidates = if (direction == FocusDirection.Up) (row - 1 downTo 0) else (row + 1 until rows.size)
        for (index in candidates) {
            val nearest = rows[index].indices.filter { rows[index][it] !in hidden }
                .minByOrNull { kotlin.math.abs(it - column) }
            if (nearest != null) return requesters.getValue(rows[index][nearest])
        }
        return neighbor(direction)
    }

    val shape = RoundedCornerShape(12.dp)
    val collapseUp = collapse == AppsCardCollapse.Up
    // Only move the drawing. Tile geometry and focus targets must stay available to the D-pad.
    Box(Modifier.fillMaxWidth().height(height)) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .drawWithContent {
                    val progress = if (collapse == AppsCardCollapse.Off) 0f else collapseProgress
                    if (progress == 0f) {
                        drawContent()
                    } else if (progress < 1f) {
                        val offset = size.height * progress * if (collapseUp) -1f else 1f
                        clipRect { translate(top = offset) { this@drawWithContent.drawContent() } }
                    }
                }
                .dashboardCard(MaterialTheme.colorScheme.surfaceVariant, shape, cyclePaints = true)
                .then(if (first == null) Modifier.focusRequester(target).focusRequester(homeTarget)
                    .onFocusChanged { if (it.isFocused || currentVisible.isEmpty()) emptyFocused = it.isFocused }
                    .focusProperties {
                        left = neighbor(FocusDirection.Left)
                        right = neighbor(FocusDirection.Right)
                        up = neighbor(FocusDirection.Up)
                        down = neighbor(FocusDirection.Down)
                    }.focusable() else Modifier)
                .then(if (emptyFocused) Modifier.border(2.dp, cycleUiColor(DashboardFocusColor), shape) else Modifier)
                .padding(10.dp),
        ) {
            val gap = minOf(8.dp, maxHeight / (rows.size.coerceAtLeast(1) * 2))
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                if (card.card.title.isNotBlank()) Text(card.card.title, modifier = Modifier.cycleUiColors())
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    rows.forEachIndexed { rowIndex, row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEachIndexed { column, id ->
                                if (id in hidden) {
                                    Spacer(Modifier.weight(1f))
                                } else key(id) {
                                    val requester = requesters.getValue(id)
                                    val focusModifier = Modifier.fillMaxSize()
                                        .then(if (id == entry) Modifier.focusRequester(target) else Modifier)
                                        .then(if (id == first) Modifier.focusRequester(homeTarget) else Modifier)
                                        .focusRequester(requester)
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                focusedApp = id
                                                onAppFocused(id)
                                            } else if (focusedApp == id && id in currentVisible) {
                                                focusedApp = null
                                            }
                                        }
                                        .focusProperties {
                                            left = row.take(column).lastOrNull { it !in hidden }
                                                ?.let(requesters::getValue) ?: neighbor(FocusDirection.Left)
                                            right = row.drop(column + 1).firstOrNull { it !in hidden }
                                                ?.let(requesters::getValue) ?: neighbor(FocusDirection.Right)
                                            up = verticalNeighbor(rowIndex, column, FocusDirection.Up)
                                            down = verticalNeighbor(rowIndex, column, FocusDirection.Down)
                                        }
                                    val app = apps[id]
                                    Box(Modifier.weight(1f).fillMaxSize().padding(2.dp)) {
                                        if (app != null) {
                                            AppTile(app, { context.launch(app) }, focusModifier, onLongClick = { menuApp = id }, fitCard = true)
                                            if (menuApp == id) {
                                                ContextMenu(app.label, buildList {
                                                    add(MenuAction("App info") { context.openAppInfo(app) })
                                                    if (context.canUninstall(app)) add(MenuAction("Uninstall") { context.uninstall(app) })
                                                }, onDismiss = { menuApp = null })
                                            }
                                        } else {
                                            DockShortcutTile("All apps", "apps", onOpenAllApps, focusModifier)
                                        }
                                    }
                                }
                            }
                            if (row.size < columns && rows.size > 1) {
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        if (collapse != AppsCardCollapse.Off) {
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .align(if (collapseUp) Alignment.TopCenter else Alignment.BottomCenter)
                    .graphicsLayer { alpha = ((collapseProgress - 0.65f) / 0.35f).coerceIn(0f, 1f) }
                    .semantics { hideFromAccessibility() }
                    .cycleUiColors()
                    .background(darkenCycleSurface(MaterialTheme.colorScheme.surface), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MdiIcon("mdi:apps", size = 14.dp, color = color)
                Text("Apps", style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

internal fun neighboringCard(card: DashboardCard, cards: List<DashboardCard>, direction: FocusDirection): DashboardCard? {
    fun distance(other: DashboardCard) = when (direction) {
        FocusDirection.Left -> card.x - (other.x + other.width)
        FocusDirection.Right -> other.x - (card.x + card.width)
        FocusDirection.Up -> card.y - (other.y + other.height)
        else -> other.y - (card.y + card.height)
    }
    fun crossGap(other: DashboardCard): Int =
        if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
            maxOf(card.y - other.y - other.height + 1, other.y - card.y - card.height + 1, 0)
        } else {
            maxOf(card.x - other.x - other.width + 1, other.x - card.x - card.width + 1, 0)
        }
    return cards.filter { it !== card && distance(it) >= 0 }
        .minWithOrNull(compareBy({ crossGap(it) != 0 }, { distance(it) }, { crossGap(it) }, { it.y }, { it.x }))
}
