package com.evofactory.homeboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {
    private val now = 2_000_000_000_000L
    private val day = 24 * 3600 * 1000L

    private fun row(
        title: String,
        type: Int? = 0,
        age: Long = day,
        browsable: Boolean = true,
        position: Long = 0,
        duration: Long = 0,
        intent: String? = title,
        app: String = "app",
    ) = WatchItem(title, app, null, null, intent, type, browsable, now - age, position, duration)

    private fun titles(vararg rows: WatchItem) = continueWatching(rows.toList(), now).map { it.title }

    @Test
    fun onlyWhatWasStarted() {
        assertEquals(listOf("started"), titles(row("started"), row("next", type = 1), row("new", type = 2), row("list", type = 3), row("none", type = null)))
    }

    @Test
    fun lessThanTwoWeeksOld() {
        assertEquals(listOf("recent"), titles(row("recent", age = 14 * day - 1), row("old", age = 14 * day)))
    }

    @Test
    fun noDateIsLeftOut() {
        assertEquals(emptyList<String>(), titles(row("undated", age = now)))
    }

    @Test
    fun newestFirst() {
        assertEquals(listOf("b", "a"), titles(row("a", age = 3 * day), row("b", age = day)))
    }

    @Test
    fun nearlyFinishedIsLeftOut() {
        assertEquals(listOf("going"), titles(row("going", position = 94, duration = 100), row("done", position = 95, duration = 100)))
    }

    @Test
    fun switchedOffIsLeftOut() {
        assertEquals(emptyList<String>(), titles(row("off", browsable = false)))
    }

    @Test
    fun sameShowOnlyOnce() {
        assertEquals(listOf("a"), titles(row("a", intent = "x", age = day), row("b", intent = "x", age = 2 * day), row("a", intent = "y", age = 3 * day)))
    }

    @Test
    fun fiveNewestPerApp() {
        val many = List(7) { row("many$it", age = it + 1L, app = "many") }
        val other = row("other", age = 3 * day, app = "other")
        assertEquals(listOf("many0", "many1", "many2", "many3", "many4", "other"), titles(*(many + other).toTypedArray()))
    }

    private fun filled(vararg rows: WatchItem, order: List<String> = listOf("a", "b"), hidden: List<String> = emptyList()) =
        watchRow(rows.toList(), now, order, hidden).map { it.title }

    @Test
    fun suggestionsOnlyForAppsWithNothingStarted() {
        assertEquals(
            listOf("started", "card"),
            filled(row("started", app = "a"), row("skipped", type = null, app = "a"), row("card", type = null, app = "b")),
        )
    }

    @Test
    fun startedComesFirstThenAppOrder() {
        assertEquals(
            listOf("started", "a card", "b card"),
            filled(row("b card", type = null, app = "b"), row("a card", type = null, app = "a"), row("started", app = "c"), order = listOf("a", "b", "c")),
        )
    }

    @Test
    fun anOldStartThatDoesNotCountBringsSuggestions() {
        assertEquals(listOf("card"), filled(row("old", age = 15 * day, app = "a"), row("card", type = null, app = "a")))
    }

    @Test
    fun oldSuggestionsAreLeftOutButUndatedCardsStay() {
        assertEquals(listOf("card"), filled(row("old next", type = 1, age = 15 * day, app = "a"), row("card", type = null, age = now, app = "a")))
    }

    @Test
    fun datedSuggestionsComeBeforeCards() {
        assertEquals(listOf("next", "card"), filled(row("card", type = null, age = now, app = "a"), row("next", type = 1, app = "a")))
    }

    @Test
    fun fiveSuggestionsPerApp() {
        assertEquals(5, filled(*Array(7) { row("card$it", type = null, app = "a") }).size)
    }

    @Test
    fun onlyAppsInTheOrderGetSuggestions() {
        assertEquals(emptyList<String>(), filled(row("card", type = null, app = "not on the row")))
    }

    @Test
    fun hidingTheLastStartedDoesNotBringSuggestions() {
        val started = row("started", app = "a")
        assertEquals(emptyList<String>(), filled(started, row("card", type = null, app = "a"), hidden = listOf(started.hideKey)))
    }

    @Test
    fun hiddenSuggestionMakesRoomForTheNext() {
        val first = row("first", type = null, app = "a")
        assertEquals(listOf("second"), filled(first, row("second", type = null, app = "a"), hidden = listOf(first.hideKey)))
    }

    @Test
    fun timesInSecondsAreRead() {
        assertEquals(now, engagedMillis(now / 1000))
        assertEquals(now, engagedMillis(now))
        assertEquals(0L, engagedMillis(0))
    }
}
