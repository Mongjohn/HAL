package com.evofactory.hal

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class ArtworkSettings(
    val enabled: Boolean = false,
    val region: String = "",
    val services: Set<String> = emptySet(),
    val useLocal: Boolean = false,
)

internal fun artworkRegion(text: String): String? =
    text.trim().uppercase(Locale.ROOT).takeIf { it in Locale.getISOCountries() }

internal data class StreamingService(val name: String, val providers: List<String>, val packages: Set<String>)

internal val streamingServices = listOf(
    StreamingService("Netflix", listOf("nfx", "nfa"), setOf("com.netflix.ninja")),
    StreamingService("Disney+", listOf("dnp"), setOf("com.disney.disneyplus")),
    StreamingService("Prime Video", listOf("prv", "amp", "pva"), setOf("com.amazon.amazonvideo.livingroom")),
    StreamingService("Apple TV", listOf("atp"), setOf("com.apple.atve.androidtv.appletv")),
    StreamingService("HBO Max", listOf("mxx"), setOf("com.wbd.stream", "com.hbo.hbonow")),
    StreamingService("Paramount+", listOf("pmp", "ppe", "ppp"), setOf("com.cbs.ott")),
    StreamingService("Hulu", listOf("hlu"), setOf("com.hulu.livingroomplus")),
    StreamingService("Peacock", listOf("pct", "pcp"), setOf("com.peacocktv.peacockandroid")),
    StreamingService("Viaplay", listOf("vip"), setOf("com.viaplay.android")),
    StreamingService("TV 2 Play", listOf("tvt"), setOf("dk.tv2.tv2play", "dk.tv2.tv2playtv")),
    StreamingService("SkyShowtime", listOf("sst"), setOf("com.skyshowtime.skyshowtime.google")),
    StreamingService("Crunchyroll", listOf("cru"), setOf("com.crunchyroll.crunchyroid")),
)
internal val catalogServices = streamingServices.flatMap { it.packages }.toSet()
internal const val ARTWORK_LIMIT = 20
internal const val CATALOG_INTERVAL = 24 * 60 * 60 * 1000L
private const val MAX_PAGE_BYTES = 3 * 1024 * 1024
private val catalogFileLock = Any()

internal data class CatalogArtwork(val path: String, val uri: String, val title: String, val service: String)

internal fun newestReleases(releases: List<StreamingRelease>): List<StreamingRelease> =
    releases.sortedByDescending { it.date }.distinctBy { it.path }.take(ARTWORK_LIMIT)

internal data class ArtworkCatalogSnapshot(
    val region: String,
    val services: Set<String>,
    val checkedAt: Long,
    val artwork: List<CatalogArtwork>,
    val hasLabels: Boolean = true,
) {
    fun matches(settings: ArtworkSettings) = region == settings.region && services == settings.services
}

internal class ArtworkCatalog(private val context: Context, private val client: OkHttpClient) {
    private val cache = AtomicFile(File(context.filesDir, "recent-streaming-artwork.json"))

    suspend fun cached(): ArtworkCatalogSnapshot? = withContext(Dispatchers.IO) {
        try {
            val data = synchronized(catalogFileLock) { cache.readFully() }
            val json = JSONObject(data.decodeToString())
            val entries = json.getJSONArray("artwork")
            val services = json.getJSONArray("services")
            ArtworkCatalogSnapshot(
                region = json.getString("region"),
                services = (0 until services.length()).map(services::getString).toSet(),
                checkedAt = json.getLong("checkedAt"),
                artwork = (0 until entries.length()).mapNotNull { index ->
                    val entry = entries.getJSONObject(index)
                    val uri = entry.getString("uri").takeIf(::catalogImage) ?: return@mapNotNull null
                    CatalogArtwork(entry.getString("path"), uri, entry.optString("title"), entry.optString("service"))
                }.distinctBy { it.path }.take(ARTWORK_LIMIT),
                hasLabels = json.optBoolean("hasLabels"),
            )
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    suspend fun refresh(settings: ArtworkSettings): Boolean = withContext(Dispatchers.IO) {
        try {
            val indexUrl = justWatch.resolve("/us/new")!!
            val releasePage = if (settings.region == "US") indexUrl else {
                regionalReleasePage(get(indexUrl), settings.region) ?: return@withContext false
            }
            val today = LocalDate.now()
            val providers = streamingServices.filter { it.name in settings.services }
                .flatMap { it.providers }.distinct()
            val releases = providers.chunked(4).flatMap { batch ->
                coroutineScope {
                    batch.map { provider ->
                        async {
                            val url = releasePage.newBuilder()
                                .addQueryParameter("providers", provider)
                                .addQueryParameter("monetization_types", "flatrate")
                                .build()
                            streamingReleases(get(url), settings.region, provider, today)
                        }
                    }.awaitAll().flatten()
                }
            }
            val artwork = newestReleases(releases).chunked(4).flatMap { batch ->
                coroutineScope {
                    batch.map { release ->
                        async {
                            val image = try {
                                titleBackdrop(get(justWatch.resolve(release.path)!!))
                            } catch (_: IOException) {
                                null
                            } ?: release.posterUrl
                            image?.let { CatalogArtwork(release.path, it, release.title, release.service) }
                        }
                    }.awaitAll().filterNotNull()
                }
            }
            coroutineContext.ensureActive()
            // The settings can change while a job is fetching pages.
            if (LauncherPrefs(context).artworkSettings != settings) return@withContext true
            if (releases.isNotEmpty() && artwork.isEmpty()) return@withContext false
            save(ArtworkCatalogSnapshot(settings.region, settings.services, System.currentTimeMillis(), artwork))
            true
        } catch (_: IOException) {
            false
        } catch (_: JSONException) {
            false
        }
    }

    private fun save(snapshot: ArtworkCatalogSnapshot) {
        val data = JSONObject()
            .put("region", snapshot.region)
            .put("services", JSONArray(snapshot.services))
            .put("checkedAt", snapshot.checkedAt)
            .put("hasLabels", true)
            .put("artwork", JSONArray(snapshot.artwork.map {
                JSONObject().put("path", it.path).put("uri", it.uri)
                    .put("title", it.title).put("service", it.service)
            }))
        synchronized(catalogFileLock) {
            val stream = cache.startWrite()
            try {
                stream.write(data.toString().encodeToByteArray())
                cache.finishWrite(stream)
            } catch (e: IOException) {
                cache.failWrite(stream)
                throw e
            }
        }
    }

    private suspend fun get(url: HttpUrl): String {
        coroutineContext.ensureActive()
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("JustWatch returned ${response.code}")
            response.body.byteStream().use { readLimited(it, MAX_PAGE_BYTES) }?.decodeToString()
                ?: throw IOException("JustWatch page exceeded the size limit")
        }
    }
}

internal val catalogHttpClient = OkHttpClient.Builder()
    .callTimeout(10, TimeUnit.SECONDS)
    .followRedirects(false)
    .build()
