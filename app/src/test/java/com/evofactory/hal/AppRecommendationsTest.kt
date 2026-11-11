package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRecommendationsTest {
    private fun card(title: String, app: String = "video", link: String = title, browsable: Boolean = true) =
        AppRecommendation(title, app, null, null, link, browsable, weight = 0)

    @Test
    fun keepsEachAppsRecommendationsSeparateAndInChannelOrder() {
        val first = card("First")
        val second = card("Second")
        val other = card("Other", app = "music")

        val byApp = recommendationsByApp(listOf(first, other, second), emptyList())

        assertEquals(listOf(first, second), byApp["video"])
        assertEquals(listOf(other), byApp["music"])
    }

    @Test
    fun skipsHiddenUnbrowsableUnlinkedAndDuplicateCards() {
        val hidden = card("Hidden")
        val visible = card("Visible")
        val byApp = recommendationsByApp(
            listOf(hidden, card("Disabled", browsable = false), card("No link", link = ""), visible, card("Duplicate", link = visible.intentUri)),
            listOf(hidden.hideKey),
        )

        assertEquals(listOf(visible), byApp["video"])
    }
}
