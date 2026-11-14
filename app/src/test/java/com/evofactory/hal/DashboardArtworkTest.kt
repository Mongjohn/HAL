package com.evofactory.hal

import android.media.tv.TvContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardArtworkTest {
    private val netflix = "com.netflix.ninja"
    private val disney = "com.disney.disneyplus"

    @Test
    fun watchNextRequiresVisibleStreamingAppAndSkipsPaidContent() {
        val cards = listOf(
            card(netflix, "movie"),
            card(netflix, "rental", TvContract.WatchNextPrograms.AVAILABILITY_PAID_CONTENT),
            card(netflix, "sports", type = TvContract.WatchNextPrograms.TYPE_EVENT),
            card(disney, "hidden-app"),
            card("local.game", "game"),
        )
        val result = watchNextArtwork(setOf(netflix, "local.game"), watchNextByApp(cards, emptyList()))

        assertEquals(listOf("https://example.test/movie.jpg"), result.map { it.posterUri })
        assertEquals(listOf("movie"), result.map { it.title })
    }

    @Test
    fun cachedArtworkMustMatchCountryAndSubscriptions() {
        val services = setOf("Netflix")
        val cached = ArtworkCatalogSnapshot("DK", services, 1_000L, emptyList())

        assertTrue(cached.matches(ArtworkSettings(true, "DK", services)))
        assertFalse(cached.matches(ArtworkSettings(true, "SE", services)))
        assertFalse(cached.matches(ArtworkSettings(true, "DK", setOf("Disney+"))))
    }
    private fun card(
        app: String,
        title: String,
        availability: Int? = null,
        type: Int = TvContract.WatchNextPrograms.TYPE_MOVIE,
    ) = WatchNextCard(
        title, app, null, "https://example.test/$title.jpg", title, true, 0,
        programType = type,
        availability = availability,
    )
}
