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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
private data class ArtworkSlide(val uri: String, val label: ArtworkLabel)

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
) {
    if (!settings.enabled) return
    val context = LocalContext.current.applicationContext
    val native = remember(visibleApps, watchNext) {
        watchNextArtwork(visibleApps.keys, watchNext).mapNotNull { card ->
            card.posterUri?.let { ArtworkSlide(it, ArtworkLabel(card.title, visibleApps[card.packageName].orEmpty(), card.releaseYear)) }
        }
    }
    val changes by artworkCatalogChanges.collectAsState()
    val localChanges by localArtworkChanges.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!active) return@Box
        key(settings) {
            val slides = if (settings.useLocal) {
                val local by produceState(emptyList<ArtworkSlide>(), localChanges) {
                    value = withContext(Dispatchers.IO) {
                        LocalArtwork(context).slides().map {
                            ArtworkSlide("hal-artwork:$it", ArtworkLabel(it.substringAfter('_').substringBeforeLast('.'), "Local artwork"))
                        }
                    }
                }
                local
            } else {
                val catalog by produceState<List<ArtworkSlide>?>(null, changes) {
                    val snapshot = ArtworkCatalog(context, catalogHttpClient).cached()
                    value = snapshot?.takeIf { it.matches(settings) }?.artwork?.map {
                        ArtworkSlide(it.uri, ArtworkLabel(it.title, it.service, it.year))
                    }.orEmpty()
                }
                val cached = catalog
                if (cached == null) emptyList()
                else remember(native, cached) { (cached + native).distinctBy { it.uri }.take(ARTWORK_LIMIT) }
            }
            key(slides) { ArtworkRotation(slides, onArtworkChanged) }
        }
    }
}

@Composable
private fun ArtworkRotation(slides: List<ArtworkSlide>, onArtworkChanged: (ArtworkLabel?) -> Unit) {
    if (slides.isEmpty()) return
    val context = LocalContext.current.applicationContext
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    val opacity = remember { Animatable(0f) }
    DisposableEffect(Unit) { onDispose { onArtworkChanged(null) } }

    LaunchedEffect(slides) {
        val client = artworkClient(context)
        val prefs = LauncherPrefs(context)
        val queue = ArrayDeque<ArtworkSlide>()
        var shownUri: String? = null
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
            opacity.animateTo(1f, tween(FADE_DURATION))
            current = slide.second
            incoming = null
            shownUri = slide.first.uri
            prefs.lastDashboardArtwork = slide.first.uri
            onArtworkChanged(slide.first.label)
        }
    }

    current?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    incoming?.let {
        Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }, contentScale = ContentScale.Crop)
    }
    if (current != null || incoming != null) {
        val dark = LocalLauncherTheme.current == LauncherTheme.GlassDark
        val shade = remember(dark) {
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = if (dark) 0.93f else 0.82f),
                0.48f to Color.Black.copy(alpha = if (dark) GlassDarkArtworkShade else 0.66f),
                1f to Color.Black.copy(alpha = if (dark) 0.95f else 0.88f),
            )
        }
        Box(Modifier.fillMaxSize().background(shade))
    }
}

private var sharedArtworkClient: OkHttpClient? = null

@Synchronized
private fun artworkClient(context: Context): OkHttpClient = sharedArtworkClient ?: OkHttpClient.Builder()
    .cache(Cache(File(context.cacheDir, "dashboard-artwork"), 32L * 1024 * 1024))
    .callTimeout(8, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build().also { sharedArtworkClient = it }

private fun loadBackdrop(context: Context, client: OkHttpClient, uri: String): ImageBitmap? {
    val bytes = try {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "hal-artwork" -> LocalArtwork(context).read(parsed.schemeSpecificPart)
            "https" -> {
                val request = Request.Builder().url(uri).build()
                fun read(request: Request) = try {
                    client.newCall(request).execute().use { response ->
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
