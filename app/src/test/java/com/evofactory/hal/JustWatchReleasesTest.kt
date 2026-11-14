package com.evofactory.hal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class JustWatchReleasesTest {
    @Test
    fun readsRecentSubscriptionOffersAndUsesParentSeries() {
        val values = JSONArray()
        fun store(value: Any): Int = values.length().also { values.put(value) }
        fun fields(vararg values: Pair<String, Any>) = JSONObject().apply {
            for ((name, value) in values) put(name, store(value))
        }
        fun reference(key: String) = fields("__ref" to key)

        val provider = fields("shortName" to "nfx", "clearName" to "Netflix")
        val otherProvider = fields("shortName" to "dnp")
        val show = fields("content({})" to fields(
            "fullPath" to "/dk/tv-series/example",
            "title" to "Example",
            "posterUrl" to "/poster/123/{profile}/example.{format}",
        ))
        val season = fields("show" to reference("Show:example"))

        fun edge(service: JSONObject, monetization: String) = store(fields(
            "newOffer({})" to fields("country" to "DK", "package" to service, "monetizationType" to monetization),
            "node" to season,
        ))
        val edges = JSONArray(listOf(edge(provider, "FLATRATE"), edge(provider, "RENT"), edge(otherProvider, "FLATRATE")))
        val connection = fields("edges" to edges)
        val root = fields(
            """newTitles({"country":"DK","pageType":"NEW","date":"2026-11-12"})""" to connection,
            """newTitles({"country":"DK","pageType":"NEW","date":"2026-10-01"})""" to connection,
            """newTitles({"country":"DK","pageType":"UPCOMING","date":"2026-11-13"})""" to connection,
        )
        store(fields("ROOT_QUERY" to root, "Show:example" to show))
        val html = """<script type="application/json" id="__NUXT_DATA__">$values</script>"""

        val results = streamingReleases(html, "DK", "nfx", LocalDate.parse("2026-11-13"))

        assertEquals(listOf(StreamingRelease(
            "/dk/tv-series/example",
            LocalDate.parse("2026-11-12"),
            "https://images.justwatch.com/poster/123/s718/example.jpg",
            "Example",
            "Netflix",
        )), results)
    }

    @Test
    fun findsLocalizedReleasePageAndTvSizedJpeg() {
        val index = """<link rel="alternate" href="https://www.justwatch.com/de/Neu" hreflang="de-DE">
            <link rel="alternate" href="https://www.justwatch.com/uk/new" hreflang="en-GB">"""
        val image = "https://images.justwatch.com/backdrop/123/s1440/example.jpg"

        assertEquals("/de/Neu", regionalReleasePage(index, "DE")?.encodedPath)
        assertEquals("/uk/new", regionalReleasePage(index, "GB")?.encodedPath)
        assertEquals(image, titleBackdrop("""<source srcset="$image">"""))
    }

    @Test
    fun keepsAtMostTwentyUniqueTitlesOrderedByStreamingDate() {
        val today = LocalDate.parse("2026-11-13")
        val older = (1..22).map { StreamingRelease("/dk/movie/title-$it", today.minusDays(it.toLong()), null) }
        val newest = older.last().copy(date = today)

        assertEquals(listOf(newest) + older.take(19), newestReleases(older + newest))
    }
}
