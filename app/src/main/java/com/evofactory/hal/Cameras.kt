package com.evofactory.hal

import android.media.MediaCodecList
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** An RTSP camera. Its address may include its login, so it's never logged or shown as is. */
data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val useTcp: Boolean = true,
    // A lighter stream of the same camera, if it has one: plays live in the Cameras tab
    val substreamUrl: String = "",
    // Each stream's audio tracks; off, the player never sees them. Sound plays in full screen, from the main stream.
    val useAudio: Boolean = true,
    val useSubstreamAudio: Boolean = true,
    // Each stream shown at 16:9 whatever its shape: substreams are often squeezed, e.g. 640×480 from a 16:9 camera
    val stretch: Boolean = false,
    val stretchSubstream: Boolean = true,
) {
    /** Address without the login, for display: e.g. 192.168.1.10 */
    val host: String get() = Uri.parse(url).host.orEmpty()
}

fun camerasToJson(cameras: List<Camera>): String = JSONArray(
    cameras.map {
        JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("tcp", it.useTcp).put("sub", it.substreamUrl)
            .put("audio", it.useAudio).put("subAudio", it.useSubstreamAudio)
            .put("stretch", it.stretch).put("subStretch", it.stretchSubstream)
    },
).toString()

fun camerasFromJson(json: String): List<Camera> {
    val array = JSONArray(json)
    return List(array.length()) { i ->
        array.getJSONObject(i).let {
            Camera(
                it.getString("id"),
                it.getString("name"),
                it.getString("url"),
                it.optBoolean("tcp", true),
                it.optString("sub"),
                it.optBoolean("audio", true),
                it.optBoolean("subAudio", true),
                it.optBoolean("stretch", false),
                it.optBoolean("subStretch", true),
            )
        }
    }
}

// How long a camera's name shows over the picture after switching to it
private const val NAME_SHOWN_MS = 2_000L

// The channel up and down reminder: how long it shows, and how often at most
private const val CHANNEL_HINT_SHOWN_MS = 3_000L
private const val CHANNEL_HINT_INTERVAL_MS = 60 * 60_000L

// When it last showed, for as long as the app runs
private var lastChannelHint = 0L

/**
 * The camera shown on the home screen. The remote's channel up and down switch to the next or
 * previous camera, wherever the selection is (see MainActivity), and the new camera's name shows
 * briefly over the picture. OK shows it full screen, with the same player and stream, where the
 * number buttons and channel up and down switch cameras too.
 */
@Composable
fun MainCamera(
    camera: Camera,
    cameras: List<Camera>,
    minimizeStreams: Boolean,
    onSelect: (Camera) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var nameShown by remember { mutableStateOf(false) }
    var firstCamera by remember { mutableStateOf(true) }
    var fullScreen by remember { mutableStateOf(false) }

    // The reminder that channel up and down switch cameras: as the selection lands on the camera,
    // for a few seconds, and at most once an hour
    var channelHint by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        channelHint = false
        if (!focused || cameras.size < 2) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        if (lastChannelHint != 0L && now - lastChannelHint < CHANNEL_HINT_INTERVAL_MS) return@LaunchedEffect
        lastChannelHint = now
        channelHint = true
        delay(CHANNEL_HINT_SHOWN_MS)
        channelHint = false
    }

    LaunchedEffect(camera.id) {
        // Only when switching, not when the home screen opens
        if (firstCamera) {
            firstCamera = false
            return@LaunchedEffect
        }
        nameShown = true
        delay(NAME_SHOWN_MS)
        nameShown = false
    }

    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                // On release: opened on the press, the full screen window would get the release and close
                if (event.key in OkKeys) {
                    if (event.type == KeyEventType.KeyUp) fullScreen = true
                    return@onPreviewKeyEvent true
                }
                false
            }
            .focusable(),
    ) {
        CameraView(
            url = camera.url,
            useTcp = camera.useTcp,
            modifier = Modifier.fillMaxSize(),
            minimizeStreams = minimizeStreams,
            useAudio = camera.useAudio,
            fullScreenSound = true,
            stretch = camera.stretch,
            fullScreen = fullScreen,
            onCloseFullScreen = { fullScreen = false },
            onCameraButton = { index -> cameras.getOrNull(index)?.let(onSelect) },
            onChannel = { step -> steppedCamera(cameras, camera.id, step)?.let(onSelect) },
        )
        AnimatedVisibility(
            visible = nameShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Text(
                camera.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.cycleUiColors()
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (focused) Box(Modifier.matchParentSize().cycleUiColors().border(DashboardFocusStroke, CameraShape))
        AnimatedVisibility(
            visible = channelHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        ) {
            ChannelMark(Modifier)
        }
    }
}

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * A camera's main stream full screen, opened from the Cameras tab; OK or Back closes it, a number
 * button switches to that camera. On older TVs the tab's substreams stop for it, and it waits for
 * them to let go of their decoders.
 */
@Composable
fun FullScreenCamera(camera: Camera, minimizeStreams: Boolean, onCameraButton: (Int) -> Unit, onChannel: (Int) -> Unit, onClose: () -> Unit) {
    CameraView(
        url = camera.url,
        useTcp = camera.useTcp,
        minimizeStreams = minimizeStreams,
        useAudio = camera.useAudio,
        stretch = camera.stretch,
        fullScreen = true,
        onCloseFullScreen = onClose,
        onCameraButton = onCameraButton,
        onChannel = onChannel,
        waitForOtherPlayers = minimizeStreams,
    )
}

/** Only shows that the remote's channel up and down switch cameras, like its CH rocker; the remote never lands on it */
@Composable
private fun ChannelMark(modifier: Modifier) {
    Column(
        modifier = modifier.cycleUiColors()
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MdiIcon("mdi:chevron-up", size = 28.dp, color = Color.White)
        Text("CH", style = MaterialTheme.typography.labelMedium, color = Color.White)
        MdiIcon("mdi:chevron-down", size = 28.dp, color = Color.White)
    }
}

/** Only shows that left and right switch to another camera or alert; the remote never lands on it. A dark copy behind keeps it readable over any picture. */
@Composable
fun SwitchChevron(icon: String, modifier: Modifier) {
    Box(modifier = modifier.padding(horizontal = 4.dp).cycleUiColors()) {
        MdiIcon(icon, modifier = Modifier.offset(1.dp, 1.dp), size = 36.dp, color = Color.Black.copy(alpha = 0.7f))
        MdiIcon(icon, size = 36.dp, color = Color.White)
    }
}

/**
 * All the cameras on two rows that scroll sideways: the first two cameras in the first column, the
 * next two in the second, and so on. With [playSubstreams], cameras with a substream play it live, as
 * many as the TV can decode at once; the others show the last picture seen when they were on the home
 * screen, with a red X. Selecting one of those takes the decoder of the playing camera selected
 * longest ago. OK picks a camera, to show full screen. [spareDecoder] plays one fewer, leaving a
 * decoder for the camera shown full screen.
 */
@Composable
fun CameraGrid(
    cameras: List<Camera>,
    playSubstreams: Boolean,
    minimizeStreams: Boolean,
    spareDecoder: Boolean,
    onSelect: (Camera) -> Unit,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
    // Moves the selection to the camera with this id, then calls [onCameraSelected]
    selectCamera: String? = null,
    onCameraSelected: () -> Unit = {},
    // The camera the selection is on, as it moves
    onCameraFocused: (String) -> Unit = {},
) {
    if (cameras.isEmpty()) {
        SettingsPrompt(null, "Set up cameras", onSetUp, modifier.cycleUiColors())
        return
    }
    val liveSlots = remember { LiveSlots(maxLiveVideos()) }
    // Only once the selection stays: passing over cameras shouldn't start and stop their streams
    var selectedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        delay(SELECTED_LIVE_DELAY_MS)
        liveSlots.select(id)
    }
    TwoRowGrid(
        cameras,
        key = { it.id },
        onSelect = onSelect,
        modifier = modifier,
        selectKey = selectCamera,
        onSelected = onCameraSelected,
        focusBorderColor = cycleUiColor(MaterialTheme.colorScheme.border),
        onFocus = {
            selectedId = it.id
            onCameraFocused(it.id)
        },
    ) { camera ->
        val live = playSubstreams && camera.substreamUrl.isNotEmpty()
        var playing by remember(camera.id) { mutableStateOf(false) }
        if (live) {
            DisposableEffect(camera.id) {
                liveSlots.want(camera.id)
                onDispose { liveSlots.leave(camera.id) }
            }
        }
        Box(Modifier.fillMaxSize()) {
            val streaming = live && liveSlots.has(camera.id, spare = if (spareDecoder) 1 else 0)
            if (streaming) {
                CameraView(
                    url = camera.substreamUrl,
                    useTcp = camera.useTcp,
                    modifier = Modifier.fillMaxSize(),
                    compact = true,
                    fallbackPictureUrl = camera.url,
                    minimizeStreams = minimizeStreams,
                    useAudio = camera.useSubstreamAudio,
                    stretch = camera.stretchSubstream,
                    onLiveChange = { playing = it },
                )
            } else {
                Box(Modifier.matchParentSize().cycleUiColors().background(Color.Black))
                val substreamSnapshot = cameraSnapshot(camera.substreamUrl)
                val snapshot = substreamSnapshot ?: cameraSnapshot(camera.url)
                if (snapshot != null) {
                    // The tile is 16:9 already
                    val stretched = if (substreamSnapshot != null) camera.stretchSubstream else camera.stretch
                    Image(
                        snapshot.image,
                        contentDescription = null,
                        contentScale = if (stretched) ContentScale.FillBounds else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().cycleUiColors(),
                    )
                } else {
                    Text(
                        "No picture yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).cycleUiColors(),
                    )
                }
            }
            // Name on a shade at the bottom, readable over any picture
            Text(
                camera.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .cycleUiColors()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    // Room for the mark
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 34.dp),
            )
            // Over the name's shade, which would dim it
            if (!streaming || !playing) NotLiveMark(Modifier.align(Alignment.BottomEnd).padding(6.dp).cycleUiColors())
        }
    }
}

private const val SELECTED_LIVE_DELAY_MS = 500L

/**
 * Who gets to play live, at most [max] less any kept spare. Free decoders go to the first to want
 * one. A camera selected without one takes it from the playing camera selected longest ago, those
 * never selected first. Leaving lets the next one in.
 */
internal class LiveSlots(private val max: Int) {
    // In the order they asked
    private val wanting = mutableStateListOf<String>()

    // The camera selected longest ago first
    private val playing = mutableStateListOf<String>()

    fun want(id: String) {
        wanting += id
        fill()
    }

    fun leave(id: String) {
        wanting -= id
        playing -= id
        fill()
    }

    fun select(id: String) {
        if (id !in wanting) return
        playing -= id
        playing += id
        while (playing.size > max) playing.removeAt(0)
    }

    // Never selected: they go before any selected one
    private fun fill() {
        for (id in wanting) {
            if (playing.size >= max) return
            if (id !in playing) playing.add(0, id)
        }
    }

    // A spare decoder comes from the camera selected longest ago
    fun has(id: String, spare: Int = 0) = playing.indexOf(id).let { it >= 0 && it >= playing.size - (max - spare) }
}

/**
 * How many videos can play at once. Hardware decoders are few, as few as 2 on some TVs, and each
 * player past that takes the decoder from another, so none plays.
 */
private fun maxLiveVideos(): Int = listOfNotNull(maxDecoders(MimeTypes.VIDEO_H265), maxDecoders(MimeTypes.VIDEO_H264)).minOrNull() ?: 1

/** How many videos of this type the TV says it can decode at once, on the decoder players pick first; null if it has none */
private fun maxDecoders(type: String): Int? =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .firstOrNull { !it.isEncoder && type in it.supportedTypes }
        ?.getCapabilitiesForType(type)?.maxSupportedInstances

/** 16:9 tiles on two rows that scroll sideways: the first two items in the first column, and so on. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> TwoRowGrid(
    items: List<T>,
    key: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    // Moves the selection to the item with this key, then calls [onSelected]
    selectKey: String? = null,
    onSelected: () -> Unit = {},
    // The selection moved to this item
    onFocus: (T) -> Unit = {},
    focusBorderColor: Color = MaterialTheme.colorScheme.border,
    tile: @Composable (T) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    // The grid would keep to the item it starts on, with a new first item out of sight before it
    val firstKey = items.firstOrNull()?.let(key)
    val wasAtStart = remember(firstKey) {
        Snapshot.withoutReadObservation { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 }
    }
    LaunchedEffect(firstKey) { if (wasAtStart) gridState.scrollToItem(0) }
    LaunchedEffect(selectKey) {
        val wanted = selectKey ?: return@LaunchedEffect
        val index = items.indexOfFirst { key(it) == wanted }
        if (index >= 0) {
            if (gridState.layoutInfo.visibleItemsInfo.none { it.key == wanted }) gridState.scrollToItem(index)
            withFrameNanos { } // let the grid lay out, so the tile is composed and focusable
            runCatching { requesters[wanted]?.requestFocus() }
        }
        onSelected()
    }
    BoxWithConstraints(modifier = modifier) {
        val cellHeight = (maxHeight - EdgePadding) / 2
        val cellWidth = cellHeight * 16f / 9f
        val column = cellWidth + EdgePadding
        // Scrolls once the selection would go past either edge of the screen
        val fullColumns = ((maxWidth - EdgePadding) / column).toInt().coerceAtLeast(1)
        val firstPx = with(LocalDensity.current) { EdgePadding.toPx() }
        val lastPx = with(LocalDensity.current) { (EdgePadding + column * (fullColumns - 1)).toPx() }
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(firstPx, lastPx) { scrollOutsideOf(firstPx, lastPx) }) {
            LazyHorizontalGrid(
                state = gridState,
                rows = GridCells.Fixed(2),
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = EdgePadding),
                horizontalArrangement = Arrangement.spacedBy(EdgePadding),
                verticalArrangement = Arrangement.spacedBy(EdgePadding),
            ) {
                items(items, key = key) { item ->
                    Card(
                        onClick = { onSelect(item) },
                        modifier = Modifier
                            .width(cellWidth)
                            .height(cellHeight)
                            .focusRequester(remember(key(item)) { requesters.getOrPut(key(item)) { FocusRequester() } })
                            .onFocusChanged { if (it.hasFocus) onFocus(item) },
                        shape = CardDefaults.shape(CameraShape),
                        // A border shows the selection instead of growing into the neighbours
                        scale = CardDefaults.scale(focusedScale = 1f),
                        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, focusBorderColor), shape = CameraShape)),
                    ) {
                        tile(item)
                    }
                }
            }
        }
    }
}


internal fun cameraDecoderSummary(): String = listOf("H.265" to MimeTypes.VIDEO_H265, "H.264" to MimeTypes.VIDEO_H264)
    .joinToString(" · ") { (label, type) -> "$label: ${maxDecoders(type) ?: "none"}" }
