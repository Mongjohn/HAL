package com.evofactory.hal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelAndJoin
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val SLIDE_DURATION = 45_000L
private const val FADE_DURATION = 1_200
private const val ARTWORK_BYTES = 8 * 1024 * 1024
private const val MAX_ARTWORK_WIDTH = 1920
private const val MAX_ARTWORK_HEIGHT = 1080
private val mediaTypes = setOf(
    TvContract.PreviewPrograms.TYPE_MOVIE,
    TvContract.PreviewPrograms.TYPE_TV_SERIES,
    TvContract.PreviewPrograms.TYPE_TV_SEASON,
    TvContract.PreviewPrograms.TYPE_TV_EPISODE,
)
private val watchNextServices = catalogServices + setOf(
    "com.plexapp.android",
    "org.jellyfin.androidtv",
    "tv.emby.embyatv",
)

internal data class ArtworkLabel(val title: String, val service: String, val year: Int? = null) {
    val captionTitle get() = if (year == null || title.isBlank()) title else "$title ($year)"
}
internal data class ArtworkSlide(val uri: String, val label: ArtworkLabel)

/** Share the decoded slideshow with Android's dream window, including an ongoing fade. */
internal data class ArtworkFrame(val current: ImageBitmap?, val incoming: ImageBitmap?, val opacity: State<Float>)

/** One decoded slideshow survives dashboard/dream handoff and ordinary background suspension. */
internal class ArtworkRotationState {
    var runner: Job? = null
    var current by mutableStateOf<ImageBitmap?>(null)
    var incoming by mutableStateOf<ImageBitmap?>(null)
    val opacity = Animatable(0f)
    val queue = ArrayDeque<ArtworkSlide>()
    var shownUri: String? = null
    var pendingSlide: ArtworkSlide? = null
    var slides = emptyList<ArtworkSlide>()
    var label by mutableStateOf<ArtworkLabel?>(null)
    val frame get() = ArtworkFrame(current, incoming, opacity.asState())
}

internal object SharedArtworkRotation {
    private var settings: ArtworkSettings? = null
    private var rotation = ArtworkRotationState()
    fun forSettings(next: ArtworkSettings): ArtworkRotationState {
        if (settings != next) {
            rotation.runner?.cancel()
            settings = next
            rotation = ArtworkRotationState()
        }
        return rotation
    }
}

internal fun watchNextArtwork(visible: Set<String>, cards: Map<String, List<WatchNextCard>>): List<WatchNextCard> =
    cards.filterKeys { it in visible && it in watchNextServices }.values.flatten()
        .filter { it.programType == null || it.programType in mediaTypes }
        .filter { it.availability != TvContract.WatchNextPrograms.AVAILABILITY_PAID_CONTENT }
        .sortedByDescending { it.engagedMillis }
        .filter { it.posterUri != null }
        .distinctBy { it.posterUri }
        .take(12)

@Composable
internal fun DashboardArtwork(
    settings: ArtworkSettings,
    visibleApps: Map<String, String>,
    watchNext: Map<String, List<WatchNextCard>>,
    active: Boolean,
    onArtworkChanged: (ArtworkLabel?) -> Unit,
    onFrameChanged: (ArtworkFrame?) -> Unit = {},
    dimPercent: Int = 66,
    draw: () -> Boolean = { true },
    inputsReady: Boolean = true,
) {
    if (!settings.enabled || !inputsReady) return
    val context = LocalContext.current.applicationContext
    val native = remember(visibleApps, watchNext) {
        watchNextArtwork(visibleApps.keys, watchNext).mapNotNull { card ->
            card.posterUri?.let { ArtworkSlide(it, ArtworkLabel(card.title, visibleApps[card.packageName].orEmpty(), card.releaseYear)) }
        }
    }
    val changes by artworkCatalogChanges.collectAsState()
    val localChanges by localArtworkChanges.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(settings) {
            val slides = if (settings.useLocal) {
                val local by produceVisibleState<List<ArtworkSlide>?>(null, localChanges, once = true) {
                    value = withContext(Dispatchers.IO) {
                        LocalArtwork(context).slides().map {
                            ArtworkSlide("hal-artwork:$it", ArtworkLabel(it.substringAfter('_').substringBeforeLast('.'), "Local artwork"))
                        }
                    }
                }
                local
            } else {
                val catalog by produceVisibleState<List<ArtworkSlide>?>(null, changes, once = true) {
                    val snapshot = ArtworkCatalog(context, catalogHttpClient).cached()
                    value = snapshot?.takeIf { it.matches(settings) }?.artwork?.map {
                        ArtworkSlide(it.uri, ArtworkLabel(it.title, it.service, it.year))
                    }.orEmpty()
                }
                val cached = catalog
                if (cached == null) null
                else remember(native, cached) { (cached + native).distinctBy { it.uri }.take(ARTWORK_LIMIT) }
            }
            val rotation = remember(settings) { SharedArtworkRotation.forSettings(settings) }
            ArtworkRotation(slides, rotation, active, onArtworkChanged, onFrameChanged, dimPercent, draw)
        }
    }
}

@Composable
private fun ArtworkRotation(slides: List<ArtworkSlide>?, rotation: ArtworkRotationState, active: Boolean, onArtworkChanged: (ArtworkLabel?) -> Unit, onFrameChanged: (ArtworkFrame?) -> Unit, dimPercent: Int, draw: () -> Boolean) {
    val context = LocalContext.current.applicationContext
    var current by rotation::current
    var incoming by rotation::incoming
    val opacity = rotation.opacity
    val queue = rotation.queue
    var shownUri by rotation::shownUri
    var pendingSlide by rotation::pendingSlide

    VisibleEffect(slides, active, rotation) {
        if (!active || slides == null) return@VisibleEffect
        val runner = currentCoroutineContext().job
        val previous = rotation.runner
        rotation.runner = runner
        try {
            if (previous !== runner) previous?.cancelAndJoin()
            if (rotation.slides != slides) {
                queue.clear()
                rotation.slides = slides
                if (slides.isEmpty() || (shownUri != null && slides.none { it.uri == shownUri }) ||
                    (pendingSlide != null && slides.none { it.uri == pendingSlide?.uri })) {
                    current = null; incoming = null; shownUri = null; pendingSlide = null; rotation.label = null
                }
            }
            if (slides.isEmpty()) return@VisibleEffect
            val client = artworkClient(context)
            val prefs = LauncherPrefs(context)
            suspend fun finishFade() {
                opacity.animateTo(1f, tween(FADE_DURATION))
                current = incoming
                incoming = null
                pendingSlide?.let { slide ->
                    shownUri = slide.uri
                    prefs.lastDashboardArtwork = slide.uri
                    rotation.label = slide.label
                }
                pendingSlide = null
            }
            // A stopped window can interrupt a fade; both decoded frames remain immediately drawable.
            if (incoming != null) finishFade()
            suspend fun next(): Pair<ArtworkSlide, ImageBitmap>? {
                val attempted = mutableSetOf<String>()
                while (attempted.size < slides.size) {
                    if (queue.isEmpty()) {
                        queue.addAll(slides.shuffled())
                        // On startup or at the end of a pass, try the previous slide last.
                        val previousUri = shownUri ?: prefs.lastDashboardArtwork
                        val previous = queue.firstOrNull { it.uri == previousUri }
                        if (previous != null) {
                            queue.remove(previous)
                            queue.addLast(previous)
                        }
                    }
                    val slide = queue.removeFirst()
                    if (!attempted.add(slide.uri) || slide.uri == shownUri) continue
                    val image = withContext(Dispatchers.IO) { loadBackdrop(context, client, slide.uri) }
                    if (image != null) return slide to image
                }
                return null
            }
            while (true) {
                val pending = async { next() }
                if (current != null) delay(SLIDE_DURATION)
                val slide = pending.await()
                if (slide == null) {
                    // Keep an eligible slide visible if the other files are temporarily unavailable.
                    if (current == null) delay(SLIDE_DURATION)
                    continue
                }
                opacity.snapTo(0f)
                incoming = slide.second
                pendingSlide = slide.first
                finishFade()
            }
        } finally {
            if (rotation.runner === runner) rotation.runner = null
        }
    }

    val frame = remember(current, incoming) { ArtworkFrame(current, incoming, opacity.asState()) }
    SideEffect { onArtworkChanged(rotation.label); onFrameChanged(frame) }
    ArtworkBackground(frame, dimPercent = dimPercent, draw = draw)
}

@Composable
internal fun ArtworkBackground(frame: ArtworkFrame?, dimPercent: Int = 66, screensaver: Boolean = false, draw: () -> Boolean = { true }) {
    // Decide at draw time so a surface transfer/error can reveal the image in that same frame.
    Box(Modifier.fillMaxSize().drawWithContent { if (draw()) drawContent() }.background(Color.Black)) {
        frame?.current?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        frame?.incoming?.let {
            Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = frame.opacity.value }, contentScale = ContentScale.Crop)
        }
        if (frame?.current != null || frame?.incoming != null) {
            ArtworkShadeOverlay(dimPercent, screensaver)
        }
    }
}

@Composable
internal fun ArtworkShadeOverlay(dimPercent: Int = 66, screensaver: Boolean = false) {
    val shade = remember(dimPercent, screensaver) {
        fun alpha(base: Float) = backgroundShadeAlpha(if (screensaver) 0.18f + base * 0.82f else base,
            dimPercent, if (screensaver) 72 else 66)
        Brush.verticalGradient(0f to Color.Black.copy(alpha = alpha(0.82f)),
            0.48f to Color.Black.copy(alpha = alpha(ArtworkShade)), 1f to Color.Black.copy(alpha = alpha(0.88f)))
    }
    Box(Modifier.fillMaxSize().background(shade))
}

private var sharedArtworkClient: OkHttpClient? = null

@Synchronized
private fun artworkClient(context: Context): OkHttpClient = sharedArtworkClient ?: OkHttpClient.Builder()
    .cache(Cache(File(context.cacheDir, "dashboard-artwork"), 32L * 1024 * 1024))
    .callTimeout(8, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build().also { sharedArtworkClient = it }

private suspend fun loadBackdrop(context: Context, client: OkHttpClient, uri: String): ImageBitmap? {
    val bytes = try {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "hal-artwork" -> LocalArtwork(context).read(parsed.schemeSpecificPart)
            "https" -> {
                val request = Request.Builder().url(uri).build()
                suspend fun read(request: Request): ByteArray? = try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    client.newCall(request).readCancellable { response ->
                        if (response.isSuccessful) response.body.byteStream().use { readLimited(it, ARTWORK_BYTES) }
                        else null
                    }
                } catch (_: IOException) {
                    null
                }
                read(request) ?: read(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build())
            }
            "content", "android.resource" ->
                context.contentResolver.openInputStream(parsed)?.use { readLimited(it, ARTWORK_BYTES) }
            else -> null
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    } ?: return null
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val local = uri.startsWith("hal-artwork:")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
        (!local && (bounds.outWidth < 640 || (!isCatalogPoster(uri) && bounds.outWidth / bounds.outHeight.toFloat() < 1.45f)))
    ) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_ARTWORK_WIDTH || bounds.outHeight / sample > MAX_ARTWORK_HEIGHT) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
