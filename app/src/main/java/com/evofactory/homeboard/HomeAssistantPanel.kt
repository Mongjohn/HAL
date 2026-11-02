package com.evofactory.homeboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RETRY_DELAY_MS = 5_000L

private val SensorFadeHeight = 40.dp

/** At most this many action buttons in the top bar */
const val MAX_HA_ACTIONS = 3

enum class HaStatus { Connecting, Connected, Offline, TokenRejected }

/** One connection to Home Assistant, shared by the panel's sensors and the top bar's actions */
class HaConnection {
    // Last known states stay on screen while reconnecting
    val entities = mutableStateMapOf<String, HaEntity>()

    // Weather forecasts by entity id
    val hourly = mutableStateMapOf<String, List<HaForecast>>()
    val daily = mutableStateMapOf<String, List<HaForecast>>()
    var session by mutableStateOf<HaSession?>(null)
        internal set
    var status by mutableStateOf(HaStatus.Connecting)
        internal set
}

/**
 * Follows [entityIds] on [server]. Connected only while the launcher is on screen; reconnects on
 * its own, except when the token is rejected: then it waits for a new login or an app restart, so
 * a wrong login doesn't hammer Home Assistant.
 */
@Composable
fun rememberHaConnection(server: HaServer, entityIds: List<String>): HaConnection {
    val connection = remember(server) { HaConnection() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(server, entityIds) {
        if (!server.isSet || entityIds.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (connection.status == HaStatus.TokenRejected) return@repeatOnLifecycle
            while (true) {
                try {
                    haSession(server) {
                        followEntities(entityIds, connection.entities)
                        for (id in entityIds.filter(::isHaWeather)) {
                            followForecast(id, "hourly") { connection.hourly[id] = it }
                            followForecast(id, "daily") { connection.daily[id] = it }
                        }
                        connection.session = this
                        connection.status = HaStatus.Connected
                        try {
                            awaitCancellation()
                        } finally {
                            connection.session = null
                        }
                    }
                } catch (e: HaException) {
                    if (e.tokenRejected) {
                        connection.status = HaStatus.TokenRejected
                        return@repeatOnLifecycle
                    }
                    connection.status = HaStatus.Offline
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }
    return connection
}

/**
 * Home Assistant tiles: the weather, then sensor readings grouped by room. The remote can land on
 * them, though they do nothing yet. [sensorsFocus] selects the sensors; up from the top tile goes
 * to [upFocus]. A rejected login shows a button to [onLogIn] instead.
 */
@Composable
fun HomeAssistantPanel(
    connection: HaConnection,
    entityIds: List<String>,
    sensorsFocus: FocusRequester,
    upFocus: FocusRequester,
    onLogIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (connection.status == HaStatus.TokenRejected) {
        SettingsPrompt(
            "Home Assistant rejected the login",
            "Log in again",
            onLogIn,
            modifier,
            buttonModifier = Modifier.focusRequester(sensorsFocus).focusProperties { up = upFocus },
        )
        return
    }
    val entities = connection.entities
    // Sensors without a room first, as they have no title: under a room they'd look part of it.
    // Then rooms in the order of their first sensor. Rooms are known once connected.
    val rooms = entityIds.filterNot { isHaAction(it) || isHaWeather(it) }
        .groupBy { entities[it]?.area }
        .entries.sortedBy { it.key != null }
    val weatherIds = entityIds.filter(::isHaWeather)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        weatherIds.forEachIndexed { index, id ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableTile(if (index == 0) upFocus else null)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                WeatherReading(entities[id], connection.hourly[id], connection.daily[id])
            }
        }
        // Takes all the height left, however many sensors there are; those that don't fit fade out
        val cardColor = MaterialTheme.colorScheme.surfaceVariant
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(sensorsFocus)
                .selectableTile(if (weatherIds.isEmpty()) upFocus else null)
                .clip(RoundedCornerShape(12.dp))
                .background(cardColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // At full height, so the last rows are cut by the card rather than squeezed
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                // Compact: the weather and sensors share the height of the camera
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rooms.forEachIndexed { index, (room, ids) ->
                    if (room != null) {
                        Text(
                            room,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (index > 0) 4.dp else 0.dp),
                        )
                    }
                    ids.forEach { id -> SensorReading(id, entities[id]) }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(SensorFadeHeight)
                    .background(Brush.verticalGradient(listOf(cardColor.copy(alpha = 0f), cardColor))),
            )
        }
        val message = when (connection.status) {
            HaStatus.Connecting -> if (entities.isEmpty()) "Connecting to Home Assistant…" else null
            HaStatus.Offline -> "Home Assistant offline · retrying"
            HaStatus.TokenRejected, HaStatus.Connected -> null
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The remote can land on it and it shows; [up] overrides where up goes from here */
@Composable
private fun Modifier.selectableTile(up: FocusRequester?): Modifier {
    var focused by remember { mutableStateOf(false) }
    val border = MaterialTheme.colorScheme.border
    return this
        .onFocusChanged { focused = it.isFocused }
        .focusProperties { if (up != null) this.up = up }
        .focusable()
        .then(if (focused) Modifier.border(3.dp, border, RoundedCornerShape(12.dp)) else Modifier)
}

/**
 * Action buttons (scenes and scripts) for the top bar: round, as large as the cog, showing only
 * the icon. The name shows underneath while the remote is on one, in the margin below the bar.
 * Greyed out until connected, and while one runs; a failure shows as a toast.
 */
@Composable
fun HaActionButtons(connection: HaConnection, actionIds: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    actionIds.forEach { id ->
        key(id) {
            val entity = connection.entities[id]
            val session = connection.session
            val name = entity?.name ?: id
            val domainIcon = defaultHaIcon(id.substringBefore('.'), null, "")
            var focused by remember { mutableStateOf(false) }
            Box(Modifier.size(SettingsButtonSize)) {
                Surface(
                    onClick = {
                        if (session == null || running != null) return@Surface
                        running = id
                        scope.launch {
                            try {
                                session.run(id)
                            } catch (e: HaException) {
                                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                            } finally {
                                running = null
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = name },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = colors.surfaceVariant,
                        contentColor = if (session != null && running == null) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = colors.onSurface,
                        focusedContentColor = colors.inverseOnSurface,
                    ),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MdiIcon(entity?.icon ?: domainIcon, fallback = domainIcon, color = LocalContentColor.current)
                    }
                }
                if (focused) TopBarButtonLabel(name)
            }
        }
    }
}

@Composable
private fun SensorReading(id: String, entity: HaEntity?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MdiIcon(entity?.icon ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            entity?.name ?: id,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val reading = entity?.reading ?: "—"
        // No unit in a reading that's only a dash
        val unit = entity?.unit?.takeIf { reading.endsWith(" $it") }.orEmpty()
        Text(
            buildAnnotatedString {
                append(reading.removeSuffix(unit))
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                    append(unit)
                }
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}
