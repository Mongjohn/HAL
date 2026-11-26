package com.evofactory.hal

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Diagnostic counters contain no entity IDs, URLs or credentials; snapshots do not reset them. */
internal object HalPerformance {
    val appQueries = AtomicLong()
    val watchNextQueries = AtomicLong()
    val imageLoads = AtomicLong()
    val imageHits = AtomicLong()
    val imageJoins = AtomicLong()
    val queueDepth = AtomicLong()
    val queueHighWater = AtomicLong()
    val launchableFocusedAt = AtomicLong()

    fun queued() {
        val depth = queueDepth.incrementAndGet()
        queueHighWater.accumulateAndGet(depth, ::maxOf)
    }

    fun snapshot(): JSONObject = JSONObject()
        .put("appQueries", appQueries.get()).put("watchNextQueries", watchNextQueries.get())
        .put("imageLoads", imageLoads.get()).put("imageHits", imageHits.get()).put("imageJoins", imageJoins.get())
        .put("haQueueDepth", queueDepth.get()).put("haQueueHighWater", queueHighWater.get())
        .put("launchableFocusedUptimeMs", launchableFocusedAt.get())
}
