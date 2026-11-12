package com.evofactory.hal

import android.media.tv.TvContract
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardArtworkTest {
    private val netflix = "com.netflix.ninja"
    private val disney = "com.disney.disneyplus"
    private val prime = "com.amazon.amazonvideo.livingroom"

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

        assertEquals(listOf("https://example.test/movie.jpg"), result)
    }

    @Test
    fun cachedArtworkMustStillMatchDockAndCountryAndBeUnexpired() {
        val shared = CatalogArtwork("/shared.jpg", setOf(netflix, disney))
        val hidden = CatalogArtwork("/hidden.jpg", setOf(prime))
        val cached = ArtworkCatalogSnapshot("DK", setOf(netflix, disney, prime), 1_000L, listOf(shared, hidden))

        assertEquals(listOf(shared.uri), cached.eligible("DK", setOf(disney), cached.expiresAt - 1))
        assertEquals(emptyList<String>(), cached.eligible("DK", emptySet(), 1_001L))
        assertEquals(emptyList<String>(), cached.eligible("SE", setOf(disney), 1_001L))
        assertEquals(emptyList<String>(), cached.eligible("DK", setOf(disney), cached.expiresAt))
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
