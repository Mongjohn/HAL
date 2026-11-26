package com.evofactory.hal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

private const val HA_RETRY_MS = 5_000L
private val haVisibleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private data class HaSubscription(
    val server: HaServer, val ids: List<String>, val dashboards: List<String>, val weather: Boolean,
)

/** One live connection across an activity/dream transfer, with bounded hidden teardown. */
private class VisibleHaConnection(private val config: HaSubscription) {
    val connection = HaConnection()
    private val owners = mutableMapOf<Any, ForecastDemand>()
    private val forecast = MutableStateFlow(ForecastDemand())
    private var runner: Job? = null
    private var stopping: Job? = null

    fun visible(owner: Any, visible: Boolean, demand: ForecastDemand = ForecastDemand()) {
        if (visible) {
            owners[owner] = demand
            forecast.value = owners.values.fold(ForecastDemand()) { a, b -> a + b }
            stopping?.cancel()
            stopping = null
            if (runner == null && connection.status != HaStatus.TokenRejected) {
                connection.keepDashboards(config.dashboards)
                if (!config.server.isSet || (config.ids.isEmpty() && config.dashboards.isEmpty() && !config.weather)) return
                connection.status = HaStatus.Connecting
                runner = haVisibleScope.launch { follow() }
            }
        } else if (owners.remove(owner) != null) {
            forecast.value = owners.values.fold(ForecastDemand()) { a, b -> a + b }
            if (owners.isNotEmpty()) return
            stopping = haVisibleScope.launch {
                delay(750)
                runner?.cancel()
                runner = null
                if (connection.status != HaStatus.TokenRejected) connection.status = HaStatus.Offline
            }
        }
    }

    private suspend fun follow() {
        while (true) {
            var reload = false
            try {
                haSession(config.server) {
                    val saved = CompletableDeferred<Unit>()
                    if (config.dashboards.isNotEmpty()) {
                        followDashboardEdits(config.dashboards.map(::dashboardUrlPath).toSet()) { saved.complete(Unit) }
                    }
                    val boards = config.dashboards.map { parseDashboard(dashboardConfig(dashboardUrlPath(it))) }
                    config.dashboards.forEachIndexed { index, key -> connection.setDashboard(key, boards[index]) }
                    val configured = (config.ids + boards.flatMap { it?.entityIds.orEmpty() }).distinct()
                    val weather = if (config.weather) weatherEntityIds() else emptyList()
                    // Includes visibility-only references; followEntities reconciles the first snapshot.
                    followEntities((configured + weather).distinct(), connection.entities)
                    connection.session = this
                    connection.status = HaStatus.Connected
                    connection.connectedAt = System.currentTimeMillis()
                    connection.problem = null
                    connection.failedConnections = 0
                    try {
                        coroutineScope {
                            val session = this@haSession
                            val subscriptions = mutableMapOf<Pair<String, String>, Int?>()
                            val forecastJob = launch {
                                forecast.collect { demand ->
                                    val wanted = (configured.filter(::isHaWeather) + weather).distinct()
                                        .flatMap { id -> demand.types.map { id to it } }.toSet()
                                    for (key in subscriptions.keys.toList() - wanted) {
                                        subscriptions.remove(key)?.let { session.unsubscribe(it) }
                                    }
                                    for (key in wanted - subscriptions.keys) {
                                        subscriptions[key] = session.forecastSubscription(key.first, key.second) {
                                            if (key.second == "hourly") connection.hourly[key.first] = it else connection.daily[key.first] = it
                                        }
                                    }
                                }
                            }
                            try { saved.await(); reload = true } finally { forecastJob.cancel() }
                        }
                    } finally {
                        // A new visible lease can start while an old cancelled socket is closing.
                        if (connection.session === this) {
                            connection.session = null
                            connection.connectedAt = null
                        }
                    }
                }
            } catch (e: HaException) {
                connection.problem = e.message
                connection.failedConnections++
                if (e.tokenRejected) {
                    connection.status = HaStatus.TokenRejected
                    return
                }
                connection.status = HaStatus.Offline
            }
            if (!reload) delay(HA_RETRY_MS)
        }
    }
}

private object SharedHaConnection {
    private var config: HaSubscription? = null
    private var current: VisibleHaConnection? = null
    fun forConfig(next: HaSubscription): VisibleHaConnection {
        if (config != next) { config = next; current = VisibleHaConnection(next) }
        return current!!
    }
}

/** Lifecycle leases leave data/focus intact and do not start a second socket for the dream. */
@Composable
internal fun rememberHaConnection(server: HaServer, entityIds: List<String>, dashboards: List<String>, includeScreensaverWeather: Boolean = false,
    forecastDemand: ForecastDemand = ForecastDemand()): HaConnection {
    val config = remember(server, entityIds, dashboards, includeScreensaverWeather) {
        HaSubscription(server, entityIds.distinct().sorted(), dashboards.distinct().sorted(), includeScreensaverWeather)
    }
    val host = remember(config) { SharedHaConnection.forConfig(config) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(host, lifecycle, forecastDemand) {
        val owner = Any()
        val observer = LifecycleEventObserver { _, _ -> host.visible(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), forecastDemand) }
        lifecycle.addObserver(observer)
        host.visible(owner, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), forecastDemand)
        onDispose { lifecycle.removeObserver(observer); host.visible(owner, false) }
    }
    return host.connection
}
