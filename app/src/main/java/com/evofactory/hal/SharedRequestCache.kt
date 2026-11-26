package com.evofactory.hal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Byte-bounded completed results and coalesced misses, with cancellation per active waiter. */
internal class SharedRequestCache<K, V : Any>(private val budget: Long, private val bytes: (V) -> Long) {
    private class Flight<V>(val result: Deferred<V?>, var waiters: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(4)
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private val flights = mutableMapOf<K, Flight<V>>()
    private var used = 0L

    @Synchronized fun peek(key: K): V? = values[key]

    suspend fun get(key: K, load: suspend () -> V?): V? {
        val flight = synchronized(this) {
            values[key]?.let { HalPerformance.imageHits.incrementAndGet(); return it }
            flights[key]?.let { HalPerformance.imageJoins.incrementAndGet() }
            flights.getOrPut(key) { Flight(scope.async { permits.withPermit {
                HalPerformance.imageLoads.incrementAndGet()
                load()
            } }) }.also { it.waiters++ }
        }
        try {
            val value = flight.result.await()
            synchronized(this) {
                // An invalidated/inactive request cannot repopulate a newer cache generation.
                if (value != null && flights[key] === flight && !values.containsKey(key)) {
                    val size = bytes(value).coerceAtLeast(0)
                    if (size <= budget) {
                        values[key] = value; used += size
                        while (used > budget) { val first = values.entries.first(); used -= bytes(first.value); values.remove(first.key) }
                    }
                }
            }
            return value
        } finally {
            synchronized(this) {
                flight.waiters--
                if (flight.waiters == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }

    @Synchronized fun invalidate(predicate: (K) -> Boolean) {
        values.keys.filter(predicate).forEach { used -= bytes(values.remove(it)!!) }
        flights.keys.filter(predicate).forEach { flights.remove(it)?.result?.cancel() }
    }
}
