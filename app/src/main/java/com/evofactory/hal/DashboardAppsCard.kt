package com.evofactory.hal

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun DashboardAppsCard(
    card: DashboardCard,
    installed: List<InstalledApp>,
    target: FocusRequester,
    height: Dp,
    lastApp: String?,
    neighbor: (FocusDirection) -> FocusRequester,
    onAppFocused: (String) -> Unit,
    onOpenAllApps: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember(installed) { installed.associateBy { it.key } }
    val keys = card.apps.orEmpty().filter { it == ALL_APPS_TILE_KEY || it in apps }
        .ifEmpty { listOf(ALL_APPS_TILE_KEY) }
    val columns = card.appsPerRow
    val rows = keys.chunked(columns)
    val requesters = remember(keys) { keys.associateWith { FocusRequester() } }
    val entry = lastApp?.takeIf { it in keys } ?: keys.firstOrNull()
    var menuApp by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        val gap = minOf(8.dp, maxHeight / (rows.size * 2))
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            if (card.card.title.isNotBlank()) Text(card.card.title)
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                rows.forEachIndexed { rowIndex, row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEachIndexed { column, id ->
                            key(id) {
                                val requester = requesters.getValue(id)
                                val focusModifier = Modifier.fillMaxSize()
                                    .then(if (id == entry) Modifier.focusRequester(target) else Modifier)
                                    .focusRequester(requester)
                                    .onFocusChanged { if (it.isFocused) onAppFocused(id) }
                                    .focusProperties {
                                        left = row.getOrNull(column - 1)?.let(requesters::getValue) ?: neighbor(FocusDirection.Left)
                                        right = row.getOrNull(column + 1)?.let(requesters::getValue) ?: neighbor(FocusDirection.Right)
                                        up = rows.getOrNull(rowIndex - 1)?.getOrNull(column)?.let(requesters::getValue) ?: neighbor(FocusDirection.Up)
                                        down = rows.getOrNull(rowIndex + 1)?.let { next ->
                                            requesters.getValue(next[column.coerceAtMost(next.lastIndex)])
                                        } ?: neighbor(FocusDirection.Down)
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
