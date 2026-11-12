package com.evofactory.hal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit

private const val TAG = "WatchNext"
private const val MAX_CARDS_PER_APP = 30
private const val MAX_POSTER_BYTES = 8 * 1024 * 1024
private const val POSTER_WIDTH = 480

/** An app's program in Android TV's Watch Next feed. */
data class WatchNextCard(
    val title: String,
    val packageName: String,
    val episode: String?,
    val posterUri: String?,
    val intentUri: String,
    val browsable: Boolean,
    val engagedMillis: Long,
    val programType: Int? = null,
    val availability: Int? = null,
) {
    val key: String get() = intentUri
    // Match the key used by the old Watch Next row, including devices that reported seconds.
    val hideKey: String get() = "$packageName|$title|${episode.orEmpty()}|$engagedMillis"
}

fun watchNextByApp(rows: List<WatchNextCard>, hidden: List<String>): Map<String, List<WatchNextCard>> {
    val hiddenKeys = hidden.toSet()
    return rows.filter { it.browsable && it.intentUri.isNotBlank() && it.hideKey !in hiddenKeys }
        .groupBy { it.packageName }
        .mapValues { (_, cards) -> cards.sortedByDescending { it.engagedMillis }.distinctBy { it.key }.take(MAX_CARDS_PER_APP) }
}

fun Context.canReadWatchNext() = checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

/**
 * The Watch Next table is read with the TV listings permission. Sort and filter locally because
 * Android reserves SQL selections for callers with privileged TV provider access.
 */
fun loadWatchNext(context: Context): List<WatchNextCard> {
    return try {
        readWatchNext(context)
    } catch (e: RuntimeException) {
        AppLog.w(TAG, "Couldn't read Watch Next", e)
        emptyList()
    }
}

private fun readWatchNext(context: Context): List<WatchNextCard> = buildList {
    context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { cursor ->
        fun text(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
        fun number(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
        while (cursor.moveToNext()) {
            val title = text(TvContract.WatchNextPrograms.COLUMN_TITLE)?.takeIf { it.isNotBlank() } ?: continue
            val intent = text(TvContract.WatchNextPrograms.COLUMN_INTENT_URI)?.takeIf { it.isNotBlank() } ?: continue
            val app = text(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME) ?: continue
            val season = text(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER)
            val episode = text(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
            val engaged = number(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) ?: 0
            add(WatchNextCard(
                title = title,
                packageName = app,
                episode = if (season != null && episode != null) "S$season E$episode" else null,
                posterUri = text(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI)
                    ?: text(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI),
                intentUri = intent,
                browsable = number(TvContract.WatchNextPrograms.COLUMN_BROWSABLE) != 0L,
                engagedMillis = if (engaged in 1 until 100_000_000_000L) engaged * 1000 else engaged,
                programType = number(TvContract.WatchNextPrograms.COLUMN_TYPE)?.toInt(),
                availability = number(TvContract.WatchNextPrograms.COLUMN_AVAILABILITY)?.toInt(),
            ))
        }
    }
}

/** Resumes in the app that published it; if that fails, at least opens the app. */
private fun Context.openWatchNext(item: WatchNextCard, app: InstalledApp) {
    val opened = try {
        val intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME)
        if (intent.selector != null ||
            intent.component?.packageName?.let { it != app.key } == true ||
            intent.`package`?.let { it != app.key } == true
        ) {
            false
        } else {
            intent.setPackage(app.key)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            true
        }
    } catch (e: URISyntaxException) {
        AppLog.w(TAG, "Invalid Watch Next link")
        false
    } catch (e: RuntimeException) {
        // e.g. the app was just removed, or doesn't let others open this
        AppLog.w(TAG, "Couldn't open Watch Next card")
        false
    }
    if (!opened) launch(app)
}

private val posterClient by lazy {
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
}

// Decoded posters survive refreshes, so returning to the launcher is instant
private val posterCache = LruCache<String, ImageBitmap>(MAX_CARDS_PER_APP)

/** Web or content:// address; null if there's nothing to show. Slow: call off the main thread. */
private fun loadPoster(context: Context, uri: String): ImageBitmap? {
    posterCache[uri]?.let { return it }
    val bytes = try {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "http" || parsed.scheme == "https") {
            posterClient.newCall(Request.Builder().url(uri).build()).execute().use { response ->
                if (response.isSuccessful) response.body.byteStream().use(::readLimited) else null
            }
        } else if (parsed.scheme == "content" || parsed.scheme == "android.resource") {
            context.contentResolver.openInputStream(parsed)?.use(::readLimited)
        } else null
    } catch (e: IOException) {
        AppLog.d(TAG, "No poster: $e")
        null
    } catch (e: RuntimeException) {
        // A bad address, or a provider that refuses us
        AppLog.d(TAG, "No poster: $e")
        null
    } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= POSTER_WIDTH) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val poster = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap() ?: return null
    posterCache.put(uri, poster)
    return poster
}

/** null if it's larger than any poster should be */
internal fun readLimited(stream: InputStream, maxBytes: Int = MAX_POSTER_BYTES): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > maxBytes) return null
    }
}

/**
 * Shows the dock in [first] and expands below it for [second] while [revealed].
 * The alerts in [above] slide into the dock's space while [aboveRevealed].
 */
@Composable
fun SlidingRows(
    revealed: Boolean,
    aboveRevealed: Boolean,
    above: @Composable () -> Unit,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    var height by remember { mutableIntStateOf(0) }
    val shift by animateIntAsState(if (aboveRevealed) -height else 0, label = "Alerts row")
    val extraHeight by animateIntAsState(if (revealed) height else 0, label = "Watch Next row")
    Box(modifier = Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, (placeable.height + extraHeight).coerceAtMost(constraints.maxHeight)) {
            placeable.place(0, 0)
        }
    }) {
        Box(modifier = Modifier.offset { IntOffset(0, if (shift < 0) -height - shift else 2 * height) }) { above() }
        Box(modifier = Modifier.offset { IntOffset(0, -shift) }.onSizeChanged { height = it.height }) { first() }
        Box(modifier = Modifier.offset { IntOffset(0, height) }) { second() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchNextRow(
    items: List<WatchNextCard>,
    app: InstalledApp,
    // Down from the app tile comes here.
    firstCardFocus: FocusRequester,
    // Up from any card returns to the source app.
    upFocus: FocusRequester,
    onActiveChange: (Boolean) -> Unit,
    onHide: (WatchNextCard) -> Unit,
    // Go back to the app tiles: Back, or nothing left in the row
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    val active = focusedKey != null || menuFor != null || pendingFocus != null

    LaunchedEffect(active) {
        onActiveChange(active)
        // Slides away, and starts from the most recent next time
        if (!active) listState.scrollToItem(0)
    }

    // Worked out before the card is removed, when focus is still known to be on it
    val lostFocus = remember(items) { focusedKey != null && items.none { it.key == focusedKey } }
    LaunchedEffect(items) {
        if (!lostFocus) return@LaunchedEffect
        val next = items.getOrNull(focusedIndex) ?: items.lastOrNull()
        if (next != null) pendingFocus = next.key else onLeave()
    }

    LaunchedEffect(pendingFocus, items) {
        val key = pendingFocus ?: return@LaunchedEffect
        withFrameNanos { } // let the row lay out, so the card is composed and focusable
        runCatching { requesters[key]?.requestFocus() }
        pendingFocus = null
    }

    if (items.isEmpty()) return

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = tileWidth(maxWidth)
        val firstPositionPx = with(LocalDensity.current) { (EdgePadding + (cardWidth + TileSpacing) * FocusSlot).toPx() }
        val lastPositionPx = with(LocalDensity.current) { (EdgePadding + (cardWidth + TileSpacing) * LastFocusSlot).toPx() }
        val scrollSpec = remember(firstPositionPx, lastPositionPx) { scrollOutsideOf(firstPositionPx, lastPositionPx) }

        CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) onLeave()
                    true
                },
                // The same height as the app tiles, so the two rows take turns in one space
                contentPadding = PaddingValues(horizontal = EdgePadding, vertical = TileGrowRoom),
                horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    val requester = requesters.getOrPut(item.key) { FocusRequester() }
                    WatchNextTile(
                        item = item,
                        width = cardWidth,
                        loadArtwork = active,
                        onClick = { context.openWatchNext(item, app) },
                        onLongClick = { menuFor = item.key },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier)
                            .focusRequester(requester)
                            // Past the ends the search would otherwise jump up to the app tiles
                            .focusProperties {
                                up = upFocus
                                down = FocusRequester.Cancel
                                if (index == 0) left = FocusRequester.Cancel
                                if (index == items.lastIndex) right = FocusRequester.Cancel
                            }
                            .onFocusChanged { state ->
                                if (state.hasFocus) {
                                    focusedKey = item.key
                                    focusedIndex = index
                                } else if (focusedKey == item.key) {
                                    focusedKey = null
                                }
                            },
                    ) {
                        if (menuFor == item.key) {
                            ContextMenu(
                                title = item.title,
                                actions = listOf(
                                    MenuAction("Hide") {
                                        val rest = items - item
                                        val next = rest.getOrNull(index) ?: rest.lastOrNull()
                                        pendingFocus = next?.key
                                        onHide(item)
                                        if (next == null) onLeave()
                                    },
                                ),
                                onDismiss = {
                                    // Runs before the chosen action, which may ask to focus another card
                                    menuFor = null
                                    pendingFocus = item.key
                                },
                            )
                        }
                    }
                }
            }
        }
}
}

@Composable
private fun WatchNextTile(
    item: WatchNextCard,
    width: Dp,
    loadArtwork: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val poster = if (loadArtwork) {
        val image by produceState<ImageBitmap?>(initialValue = null, item.posterUri) {
            val uri = item.posterUri ?: return@produceState
            value = withContext(Dispatchers.IO) { loadPoster(context, uri) }
        }
        image
    } else null
    Box(modifier = modifier.width(width).aspectRatio(16f / 9f)) {
        Card(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                poster?.let {
                    Image(
                        bitmap = it,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Over the picture, so the card is no taller than an app tile
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.episode?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        overlay()
    }
}
