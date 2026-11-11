package com.evofactory.hal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// The one margin used everywhere: screen edges, between the top bar, the camera, the side panel
// and the app row, and between app tiles
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

internal val MoveHighlight = Color(0xFF60A5FA)

private const val NO_ALERTS_TILE_KEY = "no-recent-alerts"

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

/** Row scroll position, so it can be put back exactly after the row changes. */
private data class ScrollPosition(val index: Int, val offset: Int)

/**
 * Tile to focus once the row has updated. With [keep] the row is first put back at that scroll
 * position, so the selection stays in the same place on screen; otherwise the row scrolls only
 * if the tile is off screen.
 */
private data class FocusRequest(val key: String, val keep: ScrollPosition? = null)

/** Stands in for the tiles until the apps are known, with the selection already on the first one */
@Composable
fun AppRowPlaceholder() {
    val firstTile = remember { FocusRequester() }
    var selected by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val width = tileWidth(maxWidth)
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding, vertical = TileGrowRoom),
            horizontalArrangement = Arrangement.spacedBy(TileSpacing),
        ) {
            items(VisibleTiles.toInt() + 1) { index ->
                Card(
                    onClick = {},
                    modifier = Modifier
                        .width(width)
                        .aspectRatio(16f / 9f)
                        .then(
                            if (index == 0) {
                                Modifier.focusRequester(firstTile).onGloballyPositioned {
                                    if (!selected) selected = runCatching { firstTile.requestFocus() }.isSuccess
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

/** Always the height of a row of tiles, so the rest of the screen is laid out right before any tile is there */
@Composable
fun AppRowSpace(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.heightIn(min = tileWidth(maxWidth) * 9f / 16f + TileGrowRoom * 2)) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    apps: List<InstalledApp>,
    hiddenApps: List<InstalledApp>,
    showHidden: Boolean,
    dockKeys: List<String>,
    onOpenAllApps: () -> Unit,
    onShowHiddenChange: (Boolean) -> Unit,
    onHide: (InstalledApp) -> Unit,
    onUnhide: (InstalledApp) -> Unit,
    onReorder: (List<String>) -> Unit,
    // Where up goes from the tile on screen in this 0-based slot
    upFocus: (slot: Int) -> FocusRequester,
    recommendationApps: Set<String>,
    recommendationsFocus: FocusRequester,
    onRecommendationSource: (String?, FocusRequester?) -> Unit,
    // Focuses the tile that was left
    rowFocus: FocusRequester,
    // Each time this goes up, the selection goes to the first tile
    goToFirstTile: Int,
    onFirstTileSelected: (Boolean) -> Unit,
    // An app tile other than the first is selected
    onOtherTileSelected: (Boolean) -> Unit,
    // Shows the "No Recent Alerts" tile in front of the apps, which isn't their first tile
    showNoAlertsTile: Boolean,
    onNoAlertsClick: () -> Unit,
    onNoAlertsSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val initialKey = dockKeys.firstOrNull()
    var pendingFocus by remember { mutableStateOf(initialKey?.let { FocusRequest(it) }) }
    // Selected as soon as it's laid out, before anything is drawn: nothing else gets the selection first
    var firstFocus by remember { mutableStateOf(initialKey) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    // Where the row was when the menu opened: menu actions put it back there
    var menuScroll by remember { mutableStateOf(ScrollPosition(0, 0)) }
    // While moving a tile the row shows this working copy: OK saves it, Back cancels
    var moveOrder by remember(dockKeys) { mutableStateOf<List<String>?>(null) }
    var movingKey by remember(dockKeys) { mutableStateOf<String?>(null) }
    // The tile sent to Android's uninstall, and where to go if it does disappear
    var uninstalling by remember { mutableStateOf<Pair<String, FocusRequest?>?>(null) }

    // Hidden apps always come last, after the apps you've arranged
    val shown = (moveOrder ?: dockKeys) + if (showHidden) hiddenApps.map { it.key } else emptyList()
    val appsByKey = remember(apps, hiddenApps) { (apps + hiddenApps).associateBy { it.key } }
    val hiddenKeys = remember(hiddenApps) { hiddenApps.map { it.key }.toSet() }

    var selectedKey by remember { mutableStateOf<String?>(null) }
    val firstDockKey = shown.firstOrNull()
    val firstTileSelected = selectedKey != null && selectedKey == firstDockKey
    val otherTileSelected = selectedKey != null && !firstTileSelected
    SideEffect {
        onFirstTileSelected(firstTileSelected)
        onOtherTileSelected(otherTileSelected)
    }
    val tilesBeforeApps = if (showNoAlertsTile) 1 else 0
    LaunchedEffect(showNoAlertsTile) {
        // The row keeps to the tile it started on: bring the new one in front of it into view
        if (showNoAlertsTile && listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset == 0) listState.scrollToItem(0)
    }
    LaunchedEffect(goToFirstTile) {
        if (goToFirstTile > 0) pendingFocus = firstDockKey?.let { FocusRequest(it, ScrollPosition(0, 0)) }
    }

    fun currentScroll() = ScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)

    LaunchedEffect(shown) {
        val (key, next) = uninstalling ?: return@LaunchedEffect
        if (key !in shown) {
            uninstalling = null
            pendingFocus = next
        }
    }

    LaunchedEffect(pendingFocus, shown, tilesBeforeApps) {
        val request = pendingFocus ?: return@LaunchedEffect
        val appIndex = shown.indexOf(request.key)
        if (appIndex < 0) return@LaunchedEffect
        val index = appIndex + tilesBeforeApps
        when {
            request.keep != null -> listState.scrollToItem(request.keep.index, request.keep.offset)
            listState.layoutInfo.visibleItemsInfo.none { it.index == index } ->
                // Putting the tile FocusSlot places earlier at the start lands this one in the middle slot
                listState.scrollToItem((index - FocusSlot).coerceAtLeast(0))
        }
        withFrameNanos { } // let the row lay out there, so the tile is composed and focusable
        runCatching { focusRequesters[request.key]?.requestFocus() }
        pendingFocus = null
    }

    fun shift(delta: Int) {
        val list = moveOrder ?: return
        val from = list.indexOf(movingKey ?: return)
        val to = from + delta
        // Only among visible apps: hidden ones stay at the end
        if (from < 0 || to !in list.indices) return
        moveOrder = list.toMutableList().apply { add(to, removeAt(from)) }
        scope.launch { listState.animateScrollToItem((to + tilesBeforeApps - FocusSlot).coerceAtLeast(0)) }
    }

    fun endMove(save: Boolean) {
        val key = movingKey ?: return
        moveOrder?.let { if (save) onReorder(it) }
        // Saved: the row stays as it is. Cancelled: the tile jumps back, follow it.
        pendingFocus = FocusRequest(key, keep = if (save) currentScroll() else null)
        moveOrder = null
        movingKey = null
    }

    fun onMoveKey(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        return when (event.key) {
            Key.DirectionLeft -> { if (down) shift(-1); true }
            Key.DirectionRight -> { if (down) shift(1); true }
            Key.DirectionUp, Key.DirectionDown -> true
            // Act on key release so the press doesn't also "click" the tile
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (!down) endMove(save = true); true }
            Key.Back, Key.Escape -> { if (!down) endMove(save = false); true }
            else -> false
        }
    }

    // Menu: Move, Hide (Unhide on a hidden tile), Info, Uninstall if possible, then Show / Don't show hidden apps
    fun menuActions(app: InstalledApp): List<MenuAction> = buildList {
        val keep = menuScroll
        if (app.key in hiddenKeys) {
            add(MenuAction("Unhide") {
                onUnhide(app)
                // It moves to the end of the visible apps: follow it
                pendingFocus = FocusRequest(app.key)
            })
        } else {
            add(MenuAction("Move") {
                moveOrder = dockKeys
                movingKey = app.key
                pendingFocus = FocusRequest(app.key, keep)
            })
            add(MenuAction("Hide") {
                // The next tile slides into this one's place and takes the selection
                val rest = shown - app.key
                val index = shown.indexOf(app.key)
                val nextKey = rest.getOrNull(index) ?: rest.getOrNull(index - 1)
                pendingFocus = nextKey?.let { FocusRequest(it, keep) }
                onHide(app)
            })
        }
        add(MenuAction("Info") { context.openAppInfo(app) })
        if (context.canUninstall(app)) {
            add(MenuAction("Uninstall") {
                val index = shown.indexOf(app.key)
                val nextKey = shown.getOrNull(index + 1) ?: shown.getOrNull(index - 1)
                uninstalling = app.key to nextKey?.let { FocusRequest(it, keep) }
                context.uninstall(app)
            })
        }
        add(showHiddenAction(showHidden, hiddenApps.size) {
            onShowHiddenChange(!showHidden)
            pendingFocus = if (showHidden && app.key in hiddenKeys) {
                // This tile is about to disappear: go to the last visible app
                dockKeys.lastOrNull()?.let { FocusRequest(it) }
            } else {
                FocusRequest(app.key, keep)
            }
        })
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tileWidth = tileWidth(maxWidth)
        // Only scrolls when the selection leaves its slots; at either end of the row the list can't
        // scroll further, so the selection moves towards the edge instead
        val firstPositionPx = with(LocalDensity.current) { (EdgePadding + (tileWidth + TileSpacing) * FocusSlot).toPx() }
        val lastPositionPx = with(LocalDensity.current) { (EdgePadding + (tileWidth + TileSpacing) * LastFocusSlot).toPx() }
        val scrollSpec = remember(firstPositionPx, lastPositionPx) { scrollOutsideOf(firstPositionPx, lastPositionPx) }

        CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
            LazyRow(
                state = listState,
                // Coming back down from the cog lands on the tile that was left, not the one nearest the cog
                modifier = Modifier.focusRequester(rowFocus).focusRestorer(),
                // Vertical padding leaves room for the focused tile to grow without being clipped
                contentPadding = PaddingValues(horizontal = EdgePadding, vertical = TileGrowRoom),
                horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                if (showNoAlertsTile) {
                    item(key = NO_ALERTS_TILE_KEY) {
                        DockShortcutTile(
                            title = "No Recent Alerts",
                            icon = "mdi:cctv",
                            width = tileWidth,
                            onClick = onNoAlertsClick,
                            modifier = Modifier
                                .onFocusChanged {
                                    onNoAlertsSelected(it.hasFocus)
                                    if (it.hasFocus) onRecommendationSource(null, null)
                                }
                                .focusProperties {
                                    up = upFocus(listState.slotOnScreen(NO_ALERTS_TILE_KEY))
                                    down = FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                },
                        )
                    }
                }
                items(shown, key = { it }) { key ->
                    val requester = focusRequesters.getOrPut(key) { FocusRequester() }
                    val moving = key == movingKey
                    val hasRecommendations = !moving && key in recommendationApps
                    val tileFocus = Modifier
                        .onFocusChanged {
                            if (it.hasFocus) {
                                selectedKey = key
                                if (key == ALL_APPS_TILE_KEY) onRecommendationSource(null, null)
                                else onRecommendationSource(key, requester)
                            } else if (selectedKey == key) selectedKey = null
                        }
                        .focusRequester(requester)
                        .then(
                            if (key == firstFocus) Modifier.onGloballyPositioned {
                                if (runCatching { requester.requestFocus() }.isSuccess) {
                                    firstFocus = null
                                    pendingFocus = null
                                }
                            } else Modifier,
                        )
                        .focusProperties {
                            up = upFocus(listState.slotOnScreen(key))
                            down = if (hasRecommendations) recommendationsFocus else FocusRequester.Cancel
                            if (key == shown.first() && !showNoAlertsTile) left = FocusRequester.Cancel
                            if (key == shown.last()) right = FocusRequester.Cancel
                        }
                    if (key == ALL_APPS_TILE_KEY) {
                        DockShortcutTile(
                            title = "All apps",
                            icon = "mdi:apps",
                            width = tileWidth,
                            onClick = onOpenAllApps,
                            modifier = tileFocus,
                        )
                        return@items
                    }
                    val app = appsByKey[key] ?: return@items
                    AppTile(
                        app = app,
                        moving = moving,
                        hidden = app.key in hiddenKeys,
                        onClick = { context.launch(app) },
                        onLongClick = {
                            menuScroll = currentScroll()
                            menuFor = app.key
                        },
                        modifier = Modifier
                            .width(tileWidth)
                            .then(tileFocus)
                            .then(if (hasRecommendations) Modifier.semantics { stateDescription = "Recommendations below" } else Modifier)
                            .then(if (moving) Modifier.onPreviewKeyEvent(::onMoveKey) else Modifier),
                    ) {
                        if (hasRecommendations && selectedKey == key) {
                            MdiIcon(
                                "mdi:chevron-down",
                                modifier = Modifier.align(Alignment.BottomCenter)
                                    .padding(bottom = 4.dp)
                                    .background(Color.Black.copy(alpha = 0.8f), CircleShape),
                                color = Color.White,
                            )
                        }
                        if (menuFor == app.key) {
                            ContextMenu(
                                title = app.label,
                                actions = menuActions(app),
                                onDismiss = {
                                    // Runs before the chosen action, which may ask to focus another tile
                                    menuFor = null
                                    pendingFocus = FocusRequest(app.key, menuScroll)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Show hidden apps (3)" or "Don't show hidden apps": shared by tile menus and settings. */
fun showHiddenAction(showHidden: Boolean, hiddenCount: Int, onClick: () -> Unit) = MenuAction(
    label = when {
        showHidden -> "Don't show hidden apps"
        hiddenCount > 0 -> "Show hidden apps ($hiddenCount)"
        else -> "Show hidden apps"
    },
    onClick = onClick,
)

/** Says Frigate has nothing to show, where the row of alerts would otherwise be above the tiles */
@Composable
private fun DockShortcutTile(title: String, icon: String, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.width(width).aspectRatio(16f / 9f)) {
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
private fun AppTile(
    app: InstalledApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    moving: Boolean = false,
    hidden: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val image by produceState(initialValue = cachedTileImage(app), app.key) {
        if (value == null) value = withContext(Dispatchers.IO) { loadTileImage(context, app) }
    }

    Box(modifier = modifier.aspectRatio(16f / 9f)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            border = if (moving) {
                CardDefaults.border(focusedBorder = Border(BorderStroke(4.dp, MoveHighlight)))
            } else {
                CardDefaults.border()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            val content = Modifier.fillMaxSize().alpha(if (hidden) 0.35f else 1f)
            val tile = image
            when {
                tile == null -> Box(content.background(MaterialTheme.colorScheme.surfaceVariant))
                tile.isBanner -> Image(
                    bitmap = tile.bitmap,
                    contentDescription = app.label,
                    contentScale = ContentScale.Crop,
                    modifier = content,
                )
                // Phone apps have no banner: show their icon on a neutral tile
                else -> Box(modifier = content.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Image(bitmap = tile.bitmap, contentDescription = app.label, modifier = Modifier.size(56.dp))
                }
            }
        }
        if (hidden) HiddenBadge()
        if (moving) {
            MoveArrow("‹", Alignment.CenterStart)
            MoveArrow("›", Alignment.CenterEnd)
        }
        overlay()
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

@Composable
private fun BoxScope.HiddenBadge() {
    Text(
        "Hidden",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .align(Alignment.TopStart)
            .zIndex(1f) // the focused Card raises itself; stay above it
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Arrow on the tile's edge showing it can be moved left or right. */
@Composable
private fun BoxScope.MoveArrow(symbol: String, alignment: Alignment) {
    Box(
        modifier = Modifier
            .align(alignment)
            .zIndex(1f) // the focused Card raises itself; stay above it
            .padding(horizontal = 6.dp)
            .size(36.dp)
            .background(MoveHighlight, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge, color = Color.White)
    }
}
