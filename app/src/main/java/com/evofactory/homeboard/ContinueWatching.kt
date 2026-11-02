package com.evofactory.homeboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
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
import androidx.compose.foundation.layout.height
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

private const val TAG = "ContinueWatching"
private const val MAX_AGE_MILLIS = 14 * 24 * 3600 * 1000L
private const val MAX_PROGRESS = 0.95f
private const val MAX_PER_APP = 5
private const val MAX_ITEMS = 20
private const val MAX_CHANNEL_CARDS = 30
private const val MAX_POSTER_BYTES = 8 * 1024 * 1024
private const val POSTER_WIDTH = 480

// Times below this (year 1973 in milliseconds) were written in seconds, as some apps do
private const val SMALLEST_MILLIS = 100_000_000_000L

/** A row of Android's Watch Next list or a card of a home screen channel, published by another app. */
data class WatchItem(
    val title: String,
    val packageName: String?,
    val episode: String?,
    val posterUri: String?,
    val intentUri: String?,
    val type: Int?,
    val browsable: Boolean,
    val engagedMillis: Long,
    val positionMillis: Long,
    val durationMillis: Long,
) {
    /** Stays the same when the show is watched further */
    val key: String get() = "$packageName|$title|${episode.orEmpty()}"

    /** Hiding lasts until the show is watched again */
    val hideKey: String get() = "$key|$engagedMillis"

    /** 0 to 1; null when the app gives no position or length */
    val progress: Float?
        get() = if (durationMillis > 0 && positionMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else null
}

/** Read from the row's raw time, see [SMALLEST_MILLIS] */
fun engagedMillis(raw: Long): Long = if (raw in 1 until SMALLEST_MILLIS) raw * 1000 else raw

/**
 * What the row shows, newest first: shows you started less than two weeks ago, never suggestions.
 * A row without a date is left out since its age is unknown, and a nearly finished one because
 * that's usually a film whose end credits were skipped. Each app gets [MAX_PER_APP] at most, so
 * one with many can't crowd out the others.
 */
fun continueWatching(rows: List<WatchItem>, now: Long): List<WatchItem> = rows
    .filter { row ->
        row.browsable &&
            row.type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE &&
            row.engagedMillis > 0 &&
            now - row.engagedMillis < MAX_AGE_MILLIS &&
            (row.progress ?: 0f) < MAX_PROGRESS
    }
    .sortedByDescending { it.engagedMillis }
    .distinctBy { it.key }
    .distinctBy { it.intentUri ?: it.key }
    .groupBy { it.packageName }
    .flatMap { (_, rows) -> rows.take(MAX_PER_APP) }
    .sortedByDescending { it.engagedMillis }
    .take(MAX_ITEMS)

/**
 * The whole row: what you started, then, for each app in [appOrder] with nothing started lately,
 * what it suggests instead. [hidden] holds [WatchItem.hideKey]s; hiding an app's last started show
 * doesn't bring in its suggestions.
 */
fun watchRow(rows: List<WatchItem>, now: Long, appOrder: List<String>, hidden: List<String>): List<WatchItem> {
    val startedApps = continueWatching(rows, now).map { it.packageName }.toSet()
    val shown = rows.filter { it.hideKey !in hidden }
    val suggestions = shown
        .filter { row ->
            row.browsable &&
                row.type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE &&
                // A channel's cards have no type and usually no date
                (row.type == null || (row.engagedMillis > 0 && now - row.engagedMillis < MAX_AGE_MILLIS))
        }
        // Dated ones first, newest first; the rest keep the app's own order
        .sortedByDescending { it.engagedMillis }
        .groupBy { it.packageName }
    val filled = appOrder.filter { it !in startedApps }.flatMap { app ->
        suggestions[app].orEmpty().distinctBy { it.key }.distinctBy { it.intentUri ?: it.key }.take(MAX_PER_APP)
    }
    return (continueWatching(shown, now) + filled).take(MAX_ITEMS)
}

fun Context.canReadWatchNext() = checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

/**
 * Other apps' Watch Next rows, then the cards of their home screen channels. Without the
 * system-only permission to read all TV data, Android refuses any selection or sort order, so the
 * whole tables are read and filtered here.
 */
fun loadWatchNext(context: Context): List<WatchItem> {
    val rows = mutableListOf<WatchItem>()
    try {
        readPrograms(context, TvContract.WatchNextPrograms.CONTENT_URI, rows)
        val perApp = mutableMapOf<String, Int>()
        context.contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(TvContract.Channels._ID)
            val packageName = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_PACKAGE_NAME)
            while (cursor.moveToNext()) {
                val app = cursor.getString(packageName) ?: continue
                // Some apps leave many old copies of their channels: enough is enough
                if (perApp.getOrDefault(app, 0) >= MAX_CHANNEL_CARDS) continue
                val before = rows.size
                readPrograms(context, TvContract.buildPreviewProgramsUriForChannel(cursor.getLong(id)), rows)
                perApp[app] = perApp.getOrDefault(app, 0) + rows.size - before
            }
        }
    } catch (e: RuntimeException) {
        // The provider is another app and may refuse or be missing on some TVs: then there's no row
        AppLog.w(TAG, "Couldn't read Watch Next", e)
    }
    return rows
}

/** The two tables share these columns, except that channel cards have no Watch Next type. */
private fun readPrograms(context: Context, uri: Uri, into: MutableList<WatchItem>) {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        fun text(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
        fun number(column: String) = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
        while (cursor.moveToNext()) {
            val title = text(TvContract.WatchNextPrograms.COLUMN_TITLE) ?: continue
            val season = text(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER)
            val episode = text(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
            into += WatchItem(
                title = title,
                packageName = text(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME),
                episode = if (season != null && episode != null) "S$season E$episode" else null,
                posterUri = text(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI)
                    ?: text(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI),
                intentUri = text(TvContract.WatchNextPrograms.COLUMN_INTENT_URI),
                type = number(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)?.toInt(),
                browsable = number(TvContract.WatchNextPrograms.COLUMN_BROWSABLE) != 0L,
                engagedMillis = engagedMillis(number(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) ?: 0),
                positionMillis = number(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS) ?: 0,
                durationMillis = number(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS) ?: 0,
            )
        }
    }
}

/** Resumes in the app that published it; if that fails, at least opens the app. */
private fun Context.openWatchItem(item: WatchItem, apps: List<InstalledApp>) {
    val opened = try {
        item.intentUri?.let { startActivity(Intent.parseUri(it, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } != null
    } catch (e: URISyntaxException) {
        AppLog.w(TAG, "Bad Watch Next link", e)
        false
    } catch (e: RuntimeException) {
        // e.g. the app was just removed, or doesn't let others open this
        AppLog.w(TAG, "Couldn't open Watch Next item", e)
        false
    }
    if (opened) return
    val app = apps.find { it.key == item.packageName }
    if (app != null) launch(app) else Toast.makeText(this, "Couldn't open ${item.title}", Toast.LENGTH_LONG).show()
}

private val posterClient by lazy {
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
}

// Decoded posters survive refreshes, so returning to the launcher is instant
private val posterCache = LruCache<String, ImageBitmap>(MAX_ITEMS + 10)

/** Web or content:// address; null if there's nothing to show. Slow: call off the main thread. */
private fun loadPoster(context: Context, uri: String): ImageBitmap? {
    posterCache[uri]?.let { return it }
    val bytes = try {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "http" || parsed.scheme == "https") {
            posterClient.newCall(Request.Builder().url(uri).build()).execute().use { response ->
                if (response.isSuccessful) response.body.byteStream().use(::readLimited) else null
            }
        } else {
            context.contentResolver.openInputStream(parsed)?.use(::readLimited)
        }
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
private fun readLimited(stream: InputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > MAX_POSTER_BYTES) return null
    }
}

/**
 * The bottom of the home screen: shows [first], and slides up to [second] in the same space while
 * [revealed], or down to [above] while [aboveRevealed]. Only this area moves; what's above it stays
 * where it is. [above] waits under the screen unless it's showing, as above the area it would sit
 * behind the camera, where the remote could land on it.
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
    val shift by animateIntAsState(
        when {
            aboveRevealed -> -height
            revealed -> height
            else -> 0
        },
        label = "Home rows",
    )
    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
        Box(modifier = Modifier.offset { IntOffset(0, if (shift < 0) -height - shift else 2 * height) }) { above() }
        Box(modifier = Modifier.offset { IntOffset(0, -shift) }.onSizeChanged { height = it.height }) { first() }
        Box(modifier = Modifier.offset { IntOffset(0, height - shift) }) { second() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingRow(
    items: List<WatchItem>,
    apps: List<InstalledApp>,
    // Down from an app tile comes here: the first card, the most recently watched
    firstCardFocus: FocusRequester,
    // Up from any card: the app tiles, which sit behind the camera while this row shows
    upFocus: FocusRequester,
    onActiveChange: (Boolean) -> Unit,
    onHide: (WatchItem) -> Unit,
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
                    WatchCard(
                        item = item,
                        appLabel = apps.find { it.key == item.packageName }?.label,
                        width = cardWidth,
                        onClick = { context.openWatchItem(item, apps) },
                        onLongClick = { menuFor = item.key },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier)
                            .focusRequester(requester)
                            // Past the ends the search would otherwise jump up to the app tiles
                            .focusProperties {
                                up = upFocus
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
private fun WatchCard(
    item: WatchItem,
    appLabel: String?,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val poster by produceState<ImageBitmap?>(initialValue = null, item.posterUri) {
        val uri = item.posterUri ?: return@produceState
        value = withContext(Dispatchers.IO) { loadPoster(context, uri) }
    }
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
                    Text(
                        listOfNotNull(appLabel, item.episode, timeLeft(item)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.progress?.let { progress ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }
        overlay()
    }
}

private fun timeLeft(item: WatchItem): String? {
    if (item.progress == null) return null
    val minutes = (item.durationMillis - item.positionMillis).coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min left" else "$minutes min left"
}
