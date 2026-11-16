package com.evofactory.hal

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

internal val justWatch = "https://www.justwatch.com/".toHttpUrl()

private val pageData = Regex("""<script\b[^>]*\bid="__NUXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
private val alternatePage = Regex("""<link\b[^>]*href="(https://www\.justwatch\.com/[^"]+)"\s+hreflang="[^"]+"[^>]*>""")
private val backdrop = Regex("""https://images\.justwatch\.com/backdrop/\d+/s1440/[^"'<>\s?]+\.jpg""")
private val posterImage = Regex("""https://images\.justwatch\.com/poster/\d+/s718/[^/"'<>\s?]+\.jpg""")

internal data class StreamingRelease(
    val path: String,
    val date: LocalDate,
    val posterUrl: String?,
    val title: String = "",
    val service: String = "",
)

private fun regionPath(region: String) = if (region == "GB") "uk" else region.lowercase()

internal fun regionalReleasePage(index: String, region: String): HttpUrl? =
    alternatePage.findAll(index).mapNotNull { justWatch.resolve(it.groupValues[1]) }
        .firstOrNull { it.pathSegments.first() == regionPath(region) && it.pathSegments.size == 2 }

internal fun titleBackdrop(html: String): String? = backdrop.find(html)?.value

internal fun titleReleaseYear(html: String, path: String): Int? {
    val payload = pageData.find(html)?.groupValues?.get(1) ?: return null
    val values = try {
        JSONArray(payload)
    } catch (_: JSONException) {
        return null
    }
    return (0 until values.length()).asSequence().mapNotNull(values::optJSONObject)
        .filter { values.opt(it.optInt("fullPath", -1)) == path }
        .mapNotNull { content ->
            (values.opt(content.optInt("originalReleaseYear", -1)) as? Number)?.toInt()
                ?.takeIf { it in 1000..9999 }
        }.firstOrNull()
}

internal fun catalogImage(url: String): Boolean = backdrop.matches(url) || isCatalogPoster(url)

internal fun isCatalogPoster(url: String): Boolean = posterImage.matches(url)

/** JustWatch renders its release feed into Nuxt's indexed JSON and Apollo's normalized cache. */
internal fun streamingReleases(html: String, region: String, provider: String, today: LocalDate): List<StreamingRelease> {
    val payload = pageData.find(html)?.groupValues?.get(1) ?: throw JSONException("Missing JustWatch page data")
    val values = JSONArray(payload)
    val cache = (0 until values.length()).asSequence().mapNotNull(values::optJSONObject)
        .firstOrNull { it.has("ROOT_QUERY") } ?: throw JSONException("Missing JustWatch release cache")

    fun value(obj: JSONObject, field: String): Any? = values.opt(obj.optInt(field, -1))
    fun field(obj: JSONObject, name: String): Any? =
        obj.keys().asSequence().firstOrNull { it == name || it.startsWith("$name(") }?.let { value(obj, it) }
    fun resolve(raw: Any?): JSONObject? {
        val obj = raw as? JSONObject ?: return null
        val reference = value(obj, "__ref") as? String ?: return obj
        return value(cache, reference) as? JSONObject
    }
    val root = value(cache, "ROOT_QUERY") as? JSONObject ?: throw JSONException("Missing JustWatch release query")
    val releases = mutableListOf<StreamingRelease>()
    for (query in root.keys()) {
        if (!query.startsWith("newTitles(")) continue
        val args = JSONObject(query.substringAfter('(').dropLast(1))
        if (args.optString("country") != region || args.optString("pageType") != "NEW") continue
        val dateText = args.optString("date")
        if (!dateText.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) continue
        val date = try {
            LocalDate.parse(dateText)
        } catch (_: java.time.format.DateTimeParseException) {
            continue
        }
        if (date < today.minusDays(14) || date > today) continue
        val connection = resolve(value(root, query)) ?: continue
        val edges = value(connection, "edges") as? JSONArray ?: continue
        for (i in 0 until edges.length()) {
            val edge = resolve(values.opt(edges.optInt(i, -1))) ?: continue
            val offer = resolve(field(edge, "newOffer")) ?: continue
            if (value(offer, "country") != region || value(offer, "monetizationType") != "FLATRATE") continue
            val service = resolve(value(offer, "package")) ?: continue
            if (value(service, "shortName") != provider) continue
            val node = resolve(value(edge, "node")) ?: continue
            val title = resolve(value(node, "show")) ?: node
            val content = resolve(field(title, "content")) ?: continue
            val path = value(content, "fullPath") as? String ?: continue
            // Keep title requests on JustWatch and collapse season/episode offers to their series.
            val url = justWatch.resolve(path) ?: continue
            if (!path.startsWith("/${regionPath(region)}/") || url.host != justWatch.host ||
                url.pathSegments.size != 3 || url.pathSegments.last().isEmpty() ||
                url.query != null || url.fragment != null
            ) continue
            val posterUrl = (value(content, "posterUrl") as? String)
                ?.replace("{profile}", "s718")?.replace("{format}", "jpg")
                ?.let { "https://images.justwatch.com$it" }
                ?.takeIf(::isCatalogPoster)
            val titleName = value(content, "title") as? String ?: ""
            val serviceName = (value(service, "clearName") as? String)?.takeIf { it.isNotBlank() }
                ?: streamingServices.firstOrNull { provider in it.providers }?.name.orEmpty()
            releases += StreamingRelease(url.encodedPath, date, posterUrl, titleName, serviceName)
        }
    }
    return releases
}
