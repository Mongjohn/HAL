package com.evofactory.hal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Screen edges and gaps around the top bar, camera, side panel and app row.
val EdgePadding = 24.dp

// Top of the top bar and its cog; windows with inputs open at this height, clear of the on-screen keyboard
val TopPadding = EdgePadding
internal val TileSpacing = EdgePadding

// Between items inside a panel (Home Assistant readings, camera thumbnails)
val PanelItemSpacing = 12.dp

// Above and below the app tiles, room for the selected tile to grow without being clipped; it
// counts towards the margins around the row
val TileGrowRoom = 12.dp

// 5 full tiles plus half of the next one, so it's obvious the row scrolls
private const val VisibleTiles = 5.5f

// The selection moves freely between these two of the 5 full tiles (0-based slots). Going past the
// last one, or back past the first one, scrolls the row instead by one tile.
internal const val FocusSlot = 2
internal const val LastFocusSlot = 3


/** 5.5 tiles fit across a screen this wide, see [VisibleTiles] */
internal fun tileWidth(maxWidth: Dp): Dp = (maxWidth - EdgePadding - TileSpacing * VisibleTiles.toInt()) / VisibleTiles

/** Scrolls only when the selected item would start before [firstPx] or after [lastPx] */
@OptIn(ExperimentalFoundationApi::class)
fun scrollOutsideOf(firstPx: Float, lastPx: Float) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = when {
        offset > lastPx -> offset - lastPx
        offset < firstPx -> offset - firstPx
        else -> 0f
    }
}

/** Where the item with this key is on screen, as a 0-based tile position */
internal fun LazyListState.slotOnScreen(key: Any): Int {
    val layout = layoutInfo
    val tile = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0
    return (tile.offset.toFloat() / (tile.size + layout.mainAxisItemSpacing)).roundToInt()
}

@Composable
internal fun DockShortcutTile(title: String, icon: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        border = CardDefaults.border(focusedBorder = DashboardFocusBorder),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MdiIcon(icon, size = 40.dp)
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
internal fun AppTile(
    app: InstalledApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    fitCard: Boolean = false,
) {
    val context = LocalContext.current
    val image by produceState(initialValue = cachedTileImage(app), app.key) {
        if (value == null) value = withContext(Dispatchers.IO) { loadTileImage(context, app) }
    }

    Box(modifier = if (fitCard) modifier else modifier.aspectRatio(16f / 9f)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            border = CardDefaults.border(focusedBorder = DashboardFocusBorder),
            scale = if (fitCard) CardDefaults.scale(focusedScale = 1f, pressedScale = 1f) else CardDefaults.scale(),
            modifier = Modifier.fillMaxSize(),
        ) {
            val content = Modifier.fillMaxSize()
            val tile = image
            when {
                tile == null -> Box(content.background(MaterialTheme.colorScheme.surfaceVariant))
                tile.isBanner -> Image(
                    bitmap = tile.bitmap,
                    contentDescription = app.label,
                    modifier = content.background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = if (fitCard) ContentScale.Fit else ContentScale.Crop,
                )
                // Phone apps have no banner: show their icon on a neutral tile
                else -> Box(modifier = content.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Image(bitmap = tile.bitmap, contentDescription = app.label, modifier = Modifier.size(56.dp))
                }
            }
        }
    }
}

@Composable
fun AllAppsDrawer(apps: List<InstalledApp>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = EdgePadding, vertical = TopPadding),
        ) {
            Text("All apps", style = MaterialTheme.typography.headlineSmall)
            if (apps.isEmpty()) {
                Text("No installed apps", modifier = Modifier.padding(top = PanelItemSpacing))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    contentPadding = PaddingValues(horizontal = TileGrowRoom, vertical = TileGrowRoom),
                    horizontalArrangement = Arrangement.spacedBy(PanelItemSpacing),
                    verticalArrangement = Arrangement.spacedBy(PanelItemSpacing),
                ) {
                    items(apps, key = { it.key }) { app ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AppTile(
                                app = app,
                                onClick = { context.launch(app) },
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (app.key == apps.first().key) Modifier.focusRequester(first) else Modifier),
                            )
                            Text(app.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    runCatching { first.requestFocus() }
                }
            }
        }
    }
}
