package com.evofactory.hal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedRequestCacheTest {
    @Test fun simultaneousConsumersShareOneRequestAndOneCancellationKeepsOtherWaiterAlive() = runBlocking {
        val cache = SharedRequestCache<String, String>(10) { it.length.toLong() }
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        suspend fun load(): String { calls.incrementAndGet(); started.complete(Unit); finish.await(); return "value" }
        val one = async(start = CoroutineStart.UNDISPATCHED) { cache.get("key", ::load) }
        started.await()
        val two = async(start = CoroutineStart.UNDISPATCHED) { cache.get("key", ::load) }
        one.cancelAndJoin()
        finish.complete(Unit)
        assertEquals("value", two.await())
        assertEquals(1, calls.get())
        assertEquals("value", cache.get("key") { fail("Cache missed"); null })
    }

    @Test fun byteBudgetEvictsLeastRecentlyUsedAndOversizedValuesAreNotRetained() = runBlocking {
        val cache = SharedRequestCache<String, String>(6) { it.length.toLong() }
        cache.get("a") { "aaa" }; cache.get("b") { "bbb" }
        cache.peek("a")
        cache.get("c") { "ccc" }
        assertNull(cache.peek("b")); assertEquals("aaa", cache.peek("a"))
        assertEquals("1234567", cache.get("big") { "1234567" })
        assertNull(cache.peek("big"))
        cache.invalidate { it == "a" }
        assertNull(cache.peek("a")); assertEquals("ccc", cache.peek("c"))
    }

    @Test fun cancelledLastWaiterStopsUnderlyingWorkAndFailureCanRetry() = runBlocking {
        val cache = SharedRequestCache<String, String>(10) { it.length.toLong() }
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val one = async(start = CoroutineStart.UNDISPATCHED) { cache.get("one") {
            started.complete(Unit)
            try { CompletableDeferred<Unit>().await(); "never" } finally { cancelled.complete(Unit) }
        } }
        started.await(); one.cancelAndJoin(); cancelled.await()
        assertEquals("retry", cache.get("one") { "retry" })
    }

    @Test fun accountAndSizeKeysNeverShareProtectedResults() = runBlocking {
        val cache = SharedRequestCache<MediaImageKey, String>(100) { it.length.toLong() }
        val first = MediaImageKey("https://ha", "account1", "https://ha/image", 128)
        val other = first.copy(account = "account2")
        val larger = first.copy(size = 256)
        val changedTrack = first.copy(content = listOf("another-track"))
        cache.get(first) { "one" }
        assertNull(cache.peek(other)); assertNull(cache.peek(larger)); assertNull(cache.peek(changedTrack))
    }

    @Test fun forecastDemandPreservesSeparateModesAndOptionalTodaySummary() {
        assertEquals(ForecastDemand(), forecastDemand(ForecastDisplay.Off, ForecastDisplay.Off, todaySummary = true))
        assertEquals(ForecastDemand(hourly = true), forecastDemand(ForecastDisplay.Hourly))
        assertEquals(ForecastDemand(true, true), forecastDemand(ForecastDisplay.Hourly, todaySummary = true))
        assertEquals(ForecastDemand(true, true), forecastDemand(ForecastDisplay.Hourly) + forecastDemand(ForecastDisplay.Daily))
    }
}
