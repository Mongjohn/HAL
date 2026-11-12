package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNextTest {
    private fun card(title: String, app: String = "video", link: String = title, browsable: Boolean = true, engagedMillis: Long = 0) =
        WatchNextCard(title, app, null, null, link, browsable, engagedMillis)

    @Test
    fun keepsEachAppsWatchNextSeparateAndOrdersByRecentEngagement() {
        val first = card("First", engagedMillis = 100)
        val second = card("Second")
        val other = card("Other", app = "music")

        val byApp = watchNextByApp(listOf(second, other, first), emptyList())

        assertEquals(listOf(first, second), byApp["video"])
        assertEquals(listOf(other), byApp["music"])
    }

    @Test
    fun skipsHiddenUnbrowsableUnlinkedAndDuplicateCards() {
        val hidden = card("Hidden")
        val visible = card("Visible")
        val byApp = watchNextByApp(
            listOf(hidden, card("Disabled", browsable = false), card("No link", link = ""), visible, card("Duplicate", link = visible.intentUri)),
            listOf(hidden.hideKey),
        )

        assertEquals(listOf(visible), byApp["video"])
    }
}
