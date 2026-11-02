package com.evofactory.homeboard

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

private const val COMMAND_TIMEOUT_MS = 10_000L

// Longer than OkHttp's connect timeout, so an unreachable address reports why
private const val LOGIN_TIMEOUT_MS = 15_000L

// Identifies the TV to Home Assistant's login: shown as the client of its refresh token in the
// Home Assistant profile. Any URL will do, as long as the redirect address is on the same host.
private const val CLIENT_ID = "https://www.evofactory.com/homeboard/"
private const val REDIRECT_URI = "https://www.evofactory.com/homeboard/"

/** Can't reach or log in to Home Assistant, the connection dropped, or a command failed. */
class HaException(message: String, val tokenRejected: Boolean = false, cause: Throwable? = null) : Exception(message, cause) {
    init {
        AppLog.w("HomeAssistant", message, cause)
    }
}

/**
 * Where Home Assistant is and how the TV logs in: a long-lived access [token], or the
 * [refreshToken] from logging in with a username and password (the password isn't kept).
 */
data class HaServer(val url: String, val token: String = "", val refreshToken: String = "") {
    val isSet get() = url.isNotEmpty() && (token.isNotEmpty() || refreshToken.isNotEmpty())

    /** Access token for one connection: the long-lived token, or a new one (they last 30 minutes) */
    suspend fun accessToken(): String {
        if (refreshToken.isEmpty()) return token
        val reply = postForm(
            "$url/auth/token",
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to CLIENT_ID,
        )
        return reply.optString("access_token").ifEmpty { throw HaException("Unexpected reply from Home Assistant") }
    }
}

/** Where a login with username and password stands */
sealed interface HaLoginStep {
    /** Home Assistant wants the two-factor code; [error] if the last one was wrong */
    class NeedsCode(val flowId: String, val error: String? = null) : HaLoginStep

    class LoggedIn(val refreshToken: String) : HaLoginStep
}

/**
 * Logs in with a username and password, like Home Assistant's own apps. Throws [HaException] if
 * they're wrong; a user with two-factor authentication gets [HaLoginStep.NeedsCode].
 */
suspend fun haLogIn(baseUrl: String, username: String, password: String): HaLoginStep {
    val flow = postJson(
        "$baseUrl/auth/login_flow",
        JSONObject()
            .put("client_id", CLIENT_ID)
            .put("handler", JSONArray().put("homeassistant").put(JSONObject.NULL))
            .put("redirect_uri", REDIRECT_URI),
    )
    val flowId = flow.optString("flow_id").ifEmpty { throw HaException("Unexpected reply from Home Assistant") }
    val fields = JSONObject().put("username", username).put("password", password)
    return continueLogIn(baseUrl, flowId, fields)
}

/** Sends the two-factor code of a login that's waiting for it */
suspend fun haSendCode(baseUrl: String, flowId: String, code: String): HaLoginStep =
    continueLogIn(baseUrl, flowId, JSONObject().put("code", code))

/** Ends a username-and-password login: its refresh token stops working. Best effort. */
suspend fun haRevoke(server: HaServer) {
    if (server.refreshToken.isEmpty()) return
    try {
        postForm("${server.url}/auth/revoke", "token" to server.refreshToken)
    } catch (_: HaException) {
        // Unreachable: it stays listed in the Home Assistant profile, where it can be removed
    }
}

private suspend fun continueLogIn(baseUrl: String, flowId: String, fields: JSONObject): HaLoginStep {
    var step = postJson("$baseUrl/auth/login_flow/$flowId", fields.put("client_id", CLIENT_ID))
    // A user with several two-factor methods chooses one first: take the first
    if (step.optString("step_id") == "select_mfa_module") {
        val module = step.optJSONArray("data_schema")?.optJSONObject(0)?.optJSONArray("options")
            ?.optJSONArray(0)?.optString(0) ?: throw HaException("Unexpected reply from Home Assistant")
        step = postJson(
            "$baseUrl/auth/login_flow/$flowId",
            JSONObject().put("client_id", CLIENT_ID).put("multi_factor_auth_module", module),
        )
    }
    val error = step.optJSONObject("errors")?.optString("base")
    return when (step.optString("type")) {
        "create_entry" -> {
            // The result is a one-time code, exchanged for the refresh token
            val reply = postForm(
                "$baseUrl/auth/token",
                "grant_type" to "authorization_code",
                "code" to step.getString("result"),
                "client_id" to CLIENT_ID,
            )
            HaLoginStep.LoggedIn(
                reply.optString("refresh_token").ifEmpty { throw HaException("Unexpected reply from Home Assistant") },
            )
        }
        "form" -> when {
            step.optString("step_id") == "mfa" ->
                HaLoginStep.NeedsCode(flowId, if (error == "invalid_code") "Wrong code, try again" else null)
            error == "invalid_auth" -> throw HaException("Wrong username or password")
            else -> throw HaException("Login failed${error?.let { ": $it" } ?: ""}")
        }
        "abort" -> throw HaException(
            when (val reason = step.optString("reason")) {
                "too_many_retry" -> "Too many wrong codes: log in again"
                "login_expired" -> "The login took too long: log in again"
                else -> "Login stopped: $reason"
            },
        )
        else -> throw HaException("Unexpected reply from Home Assistant")
    }
}

private suspend fun postJson(url: String, body: JSONObject): JSONObject =
    post(url, body.toString().toRequestBody("application/json".toMediaType()))

private suspend fun postForm(url: String, vararg fields: Pair<String, String>): JSONObject =
    post(url, FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build())

private suspend fun post(url: String, body: okhttp3.RequestBody): JSONObject = withContext(Dispatchers.IO) {
    val baseUrl = url.substringBefore("/auth/")
    val request = Request.Builder().url(url).post(body).build()
    try {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            when {
                // Refresh token revoked or made for another client ID, or the user was removed or deactivated
                response.code == 400 && url.endsWith("/auth/token") ->
                    throw HaException("Home Assistant ended the TV's login: log in again", tokenRejected = true)
                response.code == 403 -> throw HaException("Home Assistant refused: too many failed logins, or user disabled")
                !response.isSuccessful -> throw HaException("No Home Assistant login at $baseUrl (HTTP ${response.code})")
            }
            try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (_: JSONException) {
                throw HaException("Unexpected reply from Home Assistant")
            }
        }
    } catch (e: IOException) {
        throw HaException(describeFailure(baseUrl, e, null), cause = e)
    }
}

/**
 * An entity's state and attributes (friendly name, unit…). Home Assistant's friendly name starts
 * with the device's name, e.g. "Kitchen Sensor Kitchen PM10" for "Kitchen PM10" on the device
 * "Kitchen Sensor": [entityName] is the name without it, when known. [area] is its room, if any.
 */
class HaEntity(
    val id: String,
    val state: String,
    val attributes: JSONObject,
    val entityName: String? = null,
    val area: String? = null,
) {
    val domain get() = id.substringBefore('.')
    val fullName: String get() = attributes.string("friendly_name") ?: id
    val name: String get() = entityName ?: fullName
    val unit: String? get() = attributes.string("unit_of_measurement")

    /** Material Design Icons name, as on the Home Assistant dashboard: the one set for it, or its type's */
    val icon: String get() = attributes.string("icon") ?: defaultHaIcon(domain, attributes.string("device_class"), state)

    /** Scenes and scripts: shown as buttons that run them */
    val isAction get() = domain in ActionDomains

    /** What can be shown above the app row: actions, weather, and sensors with a measurement */
    val isPickable get() = isAction || domain == "weather" ||
        (domain == "sensor" && (unit != null || attributes.has("state_class")))

    /** Weather: the current temperature, in [temperatureUnit] */
    val temperature: Double? get() = attributes.optDouble("temperature").takeUnless { it.isNaN() }
    val temperatureUnit: String? get() = attributes.string("temperature_unit")

    /** The reading as shown, e.g. "12.4 °C"; a dash while the sensor has no value */
    val reading: String
        get() = when {
            state in setOf("unavailable", "unknown", "") -> "—"
            domain == "weather" ->
                listOfNotNull(weatherLabel(state), temperature?.let { "${it.roundToInt()} ${temperatureUnit.orEmpty()}".trim() })
                    .joinToString(" · ")
            else -> listOfNotNull(state, unit).joinToString(" ")
        }
}

/** One step of a weather forecast: an hour or a day */
class HaForecast(json: JSONObject) {
    /** When it starts; null if Home Assistant gave no time this app understands */
    val time: ZonedDateTime? = json.string("datetime")?.let {
        try {
            OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
        } catch (_: DateTimeParseException) {
            null
        }
    }
    val condition: String? = json.string("condition")
    val temperature: Double? = json.optDouble("temperature").takeUnless { it.isNaN() }

    /** The day's lowest temperature, in daily forecasts */
    val lowTemperature: Double? = json.optDouble("templow").takeUnless { it.isNaN() }

    /** Chance of rain in %, or else the expected amount in mm (or inches); what the source gives */
    val rainChance: Double? = json.optDouble("precipitation_probability").takeUnless { it.isNaN() }
    val rain: Double? = json.optDouble("precipitation").takeUnless { it.isNaN() }
}

private val ActionDomains = setOf("scene", "script")

private class HaRegistryEntry(val name: String?, val area: String?)

/** The string at [key]; null if missing, null or empty */
private fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun isHaAction(entityId: String) = entityId.substringBefore('.') in ActionDomains

fun isHaWeather(entityId: String) = entityId.substringBefore('.') == "weather"

// The pings notice a server that vanished without closing the connection (e.g. Home Assistant
// restarting, Wi-Fi gone)
private val httpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
}

/**
 * Address as typed → base URL, e.g. "192.168.1.20" → "http://192.168.1.20:8123": http:// is
 * assumed without a scheme, and Home Assistant's port 8123 without a port either. Null if invalid.
 */
fun normalizeHaUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val withScheme = when {
        "://" in text -> text
        ':' in text -> "http://$text"
        else -> "http://${text.trimEnd('/')}:8123"
    }
    // Only the server: anything copied from the browser after it (e.g. /lovelace/0) goes
    val url = withScheme.toHttpUrlOrNull() ?: return null
    return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
}

/**
 * Connects to the WebSocket API of the Home Assistant at [server], logs in, runs [block], then
 * disconnects. Call it on the main thread: commands and events are handled there. Throws
 * [HaException] if Home Assistant can't be reached, rejects the login, or the connection drops.
 */
suspend fun <T> haSession(server: HaServer, block: suspend HaSession.() -> T): T {
    val baseUrl = server.url
    val token = server.accessToken()
    val incoming = Channel<JSONObject>(Channel.UNLIMITED)
    val request = Request.Builder().url("$baseUrl/api/websocket").build()
    val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // Parsed here, off the main thread: the full entity list can be large
            try {
                incoming.trySend(JSONObject(text))
            } catch (_: JSONException) {
                incoming.close(HaException("Unexpected reply from Home Assistant"))
                webSocket.cancel()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            incoming.close(HaException("Home Assistant closed the connection"))
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            incoming.close(HaException(describeFailure(baseUrl, t, response), cause = t))
        }
    })
    try {
        logIn(socket, incoming, token)
        return coroutineScope {
            val session = HaSession(socket)
            // A dropped connection ends this loop with an HaException, which cancels the block
            val reader = launch { for (message in incoming) session.dispatch(message) }
            block(session).also { reader.cancel() }
        }
    } finally {
        // Politely if still connected
        if (!socket.close(1000, null)) socket.cancel()
    }
}

private suspend fun logIn(socket: WebSocket, incoming: ReceiveChannel<JSONObject>, token: String) {
    val reply = withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
        var message = incoming.receive()
        if (message.optString("type") == "auth_required") {
            socket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            message = incoming.receive()
        }
        message
    } ?: throw HaException("No answer from Home Assistant")
    when (reply.optString("type")) {
        "auth_ok" -> Unit
        "auth_invalid" -> throw HaException("Home Assistant rejected the token", tokenRejected = true)
        else -> throw HaException("Unexpected reply from Home Assistant")
    }
}

private fun describeFailure(baseUrl: String, t: Throwable, response: Response?): String = when {
    response != null -> "No Home Assistant at $baseUrl (HTTP ${response.code})"
    t is UnknownHostException -> "Unknown address: $baseUrl"
    t is ConnectException || t is SocketTimeoutException -> "Can't reach Home Assistant at $baseUrl"
    t is SSLException -> "HTTPS problem: ${t.message}"
    else -> "Connection lost: ${t.message ?: t.javaClass.simpleName}"
}

/** A logged-in connection, see [haSession]. Use it on the main thread only. */
class HaSession internal constructor(private val socket: WebSocket) {
    private var lastId = 0
    private val results = mutableMapOf<Int, CompletableDeferred<Any>>()
    private val subscriptions = mutableMapOf<Int, (JSONObject) -> Unit>()

    /** Sends a command and returns its result; throws [HaException] if Home Assistant reports an error */
    suspend fun command(type: String, fields: JSONObject.() -> Unit = {}): Any = send(++lastId, type, fields)

    /** Starts a subscription: [onEvent] gets each of its events for as long as the session lasts */
    suspend fun subscribe(type: String, fields: JSONObject.() -> Unit = {}, onEvent: (JSONObject) -> Unit) {
        val id = ++lastId
        // Registered first: the first event can follow the result immediately
        subscriptions[id] = onEvent
        try {
            send(id, type, fields)
        } catch (e: HaException) {
            subscriptions.remove(id)
            throw e
        }
    }

    /** All entities with their current state */
    suspend fun getStates(): List<HaEntity> {
        val registry = registry()
        val states = command("get_states") as? JSONArray ?: throw HaException("Unexpected reply from Home Assistant")
        return states.objects().map { state ->
            val id = state.getString("entity_id")
            val entry = registry[id]
            HaEntity(id, state.optString("state"), state.optJSONObject("attributes") ?: JSONObject(), entry?.name, entry?.area)
        }
    }

    /**
     * Each entity's name without its device's name, and its room, by entity id, from Home
     * Assistant's registries (as its own dashboards do). An entity's room is its device's, unless
     * set for the entity itself. Empty if this Home Assistant doesn't tell.
     */
    private suspend fun registry(): Map<String, HaRegistryEntry> {
        val entities = (optionalCommand("config/entity_registry/list_for_display") as? JSONObject)
            ?.optJSONArray("entities") ?: return emptyMap()
        val areaNames = (optionalCommand("config/area_registry/list") as? JSONArray).objects()
            .associate { it.optString("area_id") to it.string("name") }
        val deviceAreas = (optionalCommand("config/device_registry/list") as? JSONArray).objects()
            .associate { it.optString("id") to it.string("area_id") }
        return entities.objects().associate { entry ->
            val areaId = entry.string("ai") ?: entry.string("di")?.let { deviceAreas[it] }
            entry.optString("ei") to HaRegistryEntry(entry.string("en"), areaId?.let { areaNames[it] })
        }
    }

    private suspend fun optionalCommand(type: String): Any? = try {
        command(type)
    } catch (_: HaException) {
        null
    }

    /**
     * Keeps [entities] up to date with the given entities, starting with their current state.
     * Uses Home Assistant's compact format: "a" added (full state), "c" changes, "r" removed.
     */
    suspend fun followEntities(entityIds: List<String>, entities: MutableMap<String, HaEntity>) {
        val registry = registry()
        subscribe("subscribe_entities", { put("entity_ids", JSONArray(entityIds)) }) { event ->
            event.optJSONObject("a")?.let { added ->
                for (id in added.keys()) {
                    val state = added.getJSONObject(id)
                    entities[id] = HaEntity(
                        id,
                        state.optString("s"),
                        state.optJSONObject("a") ?: JSONObject(),
                        registry[id]?.name,
                        registry[id]?.area,
                    )
                }
            }
            event.optJSONObject("c")?.let { changed ->
                for (id in changed.keys()) {
                    val old = entities[id] ?: continue
                    val diff = changed.getJSONObject(id)
                    val attributes = JSONObject(old.attributes.toString())
                    val plus = diff.optJSONObject("+")
                    plus?.optJSONObject("a")?.let { for (key in it.keys()) attributes.put(key, it.get(key)) }
                    diff.optJSONObject("-")?.optJSONArray("a")?.let { for (i in 0 until it.length()) attributes.remove(it.getString(i)) }
                    entities[id] = HaEntity(id, plus?.optString("s", old.state) ?: old.state, attributes, old.entityName, old.area)
                }
            }
            event.optJSONArray("r")?.let { for (i in 0 until it.length()) entities.remove(it.getString(i)) }
        }
    }

    /**
     * Keeps [onForecast] up to date with a weather entity's forecast of [type]: "hourly" or
     * "daily". Home Assistant sends it again whenever it's updated. False if this weather has no
     * forecast of that type (each weather service offers its own).
     */
    suspend fun followForecast(entityId: String, type: String, onForecast: (List<HaForecast>) -> Unit): Boolean = try {
        subscribe("weather/subscribe_forecast", {
            put("entity_id", entityId)
            put("forecast_type", type)
        }) { event ->
            onForecast(event.optJSONArray("forecast").objects().map(::HaForecast))
        }
        true
    } catch (_: HaException) {
        false
    }

    /** Runs a scene or script */
    suspend fun run(entityId: String) {
        command("call_service") {
            put("domain", entityId.substringBefore('.'))
            put("service", "turn_on")
            put("target", JSONObject().put("entity_id", entityId))
        }
    }

    private suspend fun send(id: Int, type: String, fields: JSONObject.() -> Unit): Any {
        val result = CompletableDeferred<Any>()
        results[id] = result
        try {
            socket.send(JSONObject().put("id", id).put("type", type).apply(fields).toString())
            // A timeout is reported as an HaException: a TimeoutCancellationException would look
            // like the caller being cancelled
            return withTimeoutOrNull(COMMAND_TIMEOUT_MS) { result.await() }
                ?: throw HaException("No answer from Home Assistant")
        } finally {
            results.remove(id)
        }
    }

    internal fun dispatch(message: JSONObject) {
        val id = message.optInt("id")
        when (message.optString("type")) {
            "result" -> {
                val result = results[id] ?: return
                if (message.optBoolean("success")) {
                    result.complete(message.opt("result") ?: JSONObject.NULL)
                } else {
                    val error = message.optJSONObject("error")?.optString("message")
                    result.completeExceptionally(HaException(error ?: "Home Assistant reported an error"))
                }
            }
            "event" -> subscriptions[id]?.invoke(message.getJSONObject("event"))
        }
    }
}
