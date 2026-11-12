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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.withTimeoutOrNull
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
private val watchNextServices = catalogServices.keys + setOf(
    "com.plexapp.android",
    "org.jellyfin.androidtv",
    "tv.emby.embyatv",
)

internal fun watchNextArtwork(visible: Set<String>, cards: Map<String, List<WatchNextCard>>): List<String> =
    cards.filterKeys { it in visible && it in watchNextServices }.values.flatten()
        .filter { it.programType == null || it.programType in mediaTypes }
        .filter { it.availability != TvContract.WatchNextPrograms.AVAILABILITY_PAID_CONTENT }
        .sortedByDescending { it.engagedMillis }
        .mapNotNull { it.posterUri }
        .distinct()
        .take(12)

@Composable
internal fun DashboardArtwork(
    settings: ArtworkSettings,
    token: String,
    visiblePackages: Set<String>,
    watchNext: Map<String, List<WatchNextCard>>,
    active: Boolean,
) {
    if (!settings.enabled) return
    val context = LocalContext.current.applicationContext
    val native = remember(visiblePackages, watchNext) { watchNextArtwork(visiblePackages, watchNext) }
    val region = settings.region
    val packages = remember(visiblePackages) { visiblePackages.intersect(catalogServices.keys) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!active) return@Box
        // A change of country, credential or dock visibility immediately discards the previous
        // frame and any fade in progress, before reading the new eligible set.
        key(region, packages, token) {
            val catalog by produceState(emptyList<String>()) {
                if (region.isEmpty() || token.isEmpty() || packages.isEmpty()) return@produceState
                val source = ArtworkCatalog(context)
                var snapshot = source.cached()
                var retryAt = 0L
                while (true) {
                    val now = System.currentTimeMillis()
                    val current = snapshot?.takeIf { it.region == region && now >= it.checkedAt }
                    value = current?.eligible(region, packages, now).orEmpty()
                    val refreshAt = current?.takeIf { it.packages == packages }?.let {
                        it.checkedAt + CATALOG_REFRESH_AGE
                    } ?: now
                    val nextAttempt = maxOf(refreshAt, retryAt)
                    if (now >= nextAttempt) {
                        // Drop expired data even while a slow refresh is in progress.
                        val result = async { source.refresh(region, packages, token) }
                        val expiresAt = current?.expiresAt
                        if (value.isNotEmpty() && expiresAt != null) {
                            val completed = withTimeoutOrNull(expiresAt - now) { result.join(); true }
                            if (completed == null) value = emptyList()
                        }
                        val refreshed = result.await()
                        if (refreshed != null) snapshot = refreshed
                        retryAt = if (refreshed == null) System.currentTimeMillis() + CATALOG_RETRY else 0L
                    } else {
                        val expiresAt = current?.expiresAt?.takeIf { it > now } ?: nextAttempt
                        delay(minOf(nextAttempt, expiresAt) - now)
                    }
                }
            }
            val slides = remember(native, catalog) {
                // Start with a personal pick, then mix in the catalog for variety.
                buildList {
                    repeat(maxOf(native.size, catalog.size)) { index ->
                        native.getOrNull(index)?.let(::add)
                        catalog.getOrNull(index)?.let(::add)
                    }
                }.distinct()
            }
            key(slides) { ArtworkRotation(slides) }
        }
    }
}

@Composable
private fun ArtworkRotation(slides: List<String>) {
    if (slides.isEmpty()) return
    val context = LocalContext.current.applicationContext
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    val opacity = remember { Animatable(0f) }

    LaunchedEffect(slides) {
        val client = artworkClient(context)
        var index = 0
        var shownUri: String? = null
        suspend fun next(): Pair<String, ImageBitmap>? {
            repeat(slides.size) {
                val uri = slides[index]
                index = (index + 1) % slides.size
                if (uri != shownUri) {
                    val image = withContext(Dispatchers.IO) { loadBackdrop(context, client, uri) }
                    if (image != null) return uri to image
                }
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
            shownUri = slide.first
        }
    }

    current?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    incoming?.let {
        Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }, contentScale = ContentScale.Crop)
    }
    if (current != null || incoming != null) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.82f),
            0.48f to Color.Black.copy(alpha = 0.66f),
            1f to Color.Black.copy(alpha = 0.88f),
        )))
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
    if (bounds.outWidth < 640 || bounds.outHeight <= 0 || bounds.outWidth / bounds.outHeight.toFloat() < 1.45f) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_ARTWORK_WIDTH || bounds.outHeight / sample > MAX_ARTWORK_HEIGHT) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
