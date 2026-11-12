package com.evofactory.hal

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class ArtworkSettings(val enabled: Boolean = false, val region: String = "")

internal fun artworkRegion(text: String): String? =
    text.trim().uppercase(Locale.ROOT).takeIf { it in Locale.getISOCountries() }

// Match the app to base subscriptions only. Resolve provider IDs in each country and media type.
internal val catalogServices = mapOf(
    "com.netflix.ninja" to setOf("Netflix", "Netflix Standard with Ads"),
    "com.disney.disneyplus" to setOf("Disney Plus", "Disney+"),
    "com.amazon.amazonvideo.livingroom" to setOf("Amazon Prime Video", "Amazon Prime Video with Ads"),
    "com.apple.atve.androidtv.appletv" to setOf("Apple TV+", "Apple TV Plus", "Apple TV"),
    "com.wbd.stream" to setOf("Max", "HBO Max"),
    "com.hbo.hbonow" to setOf("Max", "HBO Max"),
    "com.cbs.ott" to setOf("Paramount Plus", "Paramount+"),
    "com.hulu.livingroomplus" to setOf("Hulu"),
    "com.peacocktv.peacockandroid" to setOf("Peacock Premium", "Peacock Premium Plus"),
    "com.viaplay.android" to setOf("Viaplay"),
    "dk.tv2.tv2play" to setOf("TV 2 Play", "TV2 Play"),
    "dk.tv2.tv2playtv" to setOf("TV 2 Play", "TV2 Play"),
    "com.skyshowtime.skyshowtime.google" to setOf("SkyShowtime"),
    "com.crunchyroll.crunchyroid" to setOf("Crunchyroll"),
)

private val backdropPath = Regex("/[a-zA-Z0-9_-]+[.]jpg")
private const val CATALOG_MAX_AGE = 24 * 60 * 60 * 1000L
internal const val CATALOG_REFRESH_AGE = 23 * 60 * 60 * 1000L
internal const val CATALOG_RETRY = 15 * 60 * 1000L

internal data class CatalogArtwork(val path: String, val packages: Set<String>) {
    val uri get() = "https://image.tmdb.org/t/p/w1280$path"
}

internal data class ArtworkCatalogSnapshot(
    val region: String,
    val packages: Set<String>,
    val checkedAt: Long,
    val artwork: List<CatalogArtwork>,
) {
    val expiresAt get() = checkedAt + CATALOG_MAX_AGE

    fun eligible(region: String, visible: Set<String>, now: Long): List<String> =
        if (this.region == region && now >= checkedAt && now < expiresAt) {
            artwork.filter { entry -> entry.packages.any { it in visible } }.map { it.uri }
        } else emptyList()
}

internal class ArtworkCatalog(context: Context) {
    private val cache = AtomicFile(File(context.cacheDir, "artwork-catalog.json"))
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun cached(): ArtworkCatalogSnapshot? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(cache.readFully().decodeToString())
            ArtworkCatalogSnapshot(
                region = json.getString("region"),
                packages = json.getJSONArray("packages").strings(),
                checkedAt = json.getLong("checkedAt"),
                artwork = json.getJSONArray("artwork").objects().mapNotNull { entry ->
                    val path = entry.getString("path").takeIf(backdropPath::matches) ?: return@mapNotNull null
                    CatalogArtwork(path, entry.getJSONArray("packages").strings())
                },
            )
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    suspend fun refresh(region: String, packages: Set<String>, token: String): ArtworkCatalogSnapshot? =
        withContext(Dispatchers.IO) {
            try {
                val artwork = coroutineScope {
                    val movies = async { discover("movie", region, packages, token) }
                    val shows = async { discover("tv", region, packages, token) }
                    (movies.await() + shows.await()).groupBy { it.path }.map { (path, matches) ->
                        CatalogArtwork(path, matches.flatMap { it.packages }.toSet())
                    }
                }
                val snapshot = ArtworkCatalogSnapshot(region, packages, System.currentTimeMillis(), artwork)
                coroutineContext.ensureActive()
                save(snapshot)
                snapshot
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }

    private fun save(snapshot: ArtworkCatalogSnapshot) {
        val data = JSONObject()
            .put("region", snapshot.region)
            .put("packages", JSONArray(snapshot.packages))
            .put("checkedAt", snapshot.checkedAt)
            .put("artwork", JSONArray(snapshot.artwork.map {
                JSONObject().put("path", it.path).put("packages", JSONArray(it.packages))
            }))
        try {
            val stream = cache.startWrite()
            try {
                stream.write(data.toString().encodeToByteArray())
                cache.finishWrite(stream)
            } catch (e: IOException) {
                cache.failWrite(stream)
                throw e
            }
        } catch (_: IOException) {
            // The live results can still be used if the cache directory cannot be written.
        }
    }

    private suspend fun discover(
        media: String,
        region: String,
        packages: Set<String>,
        token: String,
    ): List<CatalogArtwork> {
        val available = get("watch/providers/$media", token, "watch_region" to region)
            .getJSONArray("results").objects()
        val providers = available.mapNotNull { entry ->
            val id = entry.optInt("provider_id").takeIf { it > 0 } ?: return@mapNotNull null
            val name = entry.optString("provider_name")
            val matches = packages.filter { name in catalogServices.getValue(it) }.toSet()
            if (matches.isEmpty()) null else id to matches
        }.toMap()
        if (providers.isEmpty()) return emptyList()

        val results = get(
            "discover/$media",
            token,
            "watch_region" to region,
            "with_watch_providers" to providers.keys.joinToString("|"),
            "with_watch_monetization_types" to "flatrate",
            "include_adult" to "false",
            "sort_by" to "popularity.desc",
        ).getJSONArray("results").objects()
        return results.mapNotNull { entry ->
            val path = entry.optString("backdrop_path").takeIf(backdropPath::matches) ?: return@mapNotNull null
            val id = entry.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            id to path
        }.distinctBy { it.first }.take(12).mapNotNull { (id, path) ->
            // Verify that a dock service itself has the subscription offer in the selected country.
            val offers = get("$media/$id/watch/providers", token).getJSONObject("results")
                .optJSONObject(region)?.optJSONArray("flatrate")?.objects().orEmpty()
            val matches = offers.flatMap { providers[it.optInt("provider_id")].orEmpty() }.toSet()
            if (matches.isEmpty()) null else CatalogArtwork(path, matches)
        }
    }

    private suspend fun get(path: String, token: String, vararg params: Pair<String, String>): JSONObject {
        coroutineContext.ensureActive()
        val url = "https://api.themoviedb.org/3/".toHttpUrl().newBuilder().addPathSegments(path)
        params.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val request = Request.Builder().url(url.build()).header("Authorization", "Bearer $token").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TMDB returned ${response.code}")
            val data = response.body.byteStream().use { readLimited(it, 512 * 1024) }
                ?: throw IOException("TMDB response exceeded size limit")
            JSONObject(data.decodeToString())
        }
    }
}

private fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)
private fun JSONArray.strings() = (0 until length()).map(::getString).toSet()
