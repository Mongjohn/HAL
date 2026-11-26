package com.evofactory.hal

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A separate configuration API. Public reads never contain saved integration/stream credentials. */
internal class WebConfiguration(
    private val prefs: LauncherPrefs,
    private val deviceStatus: () -> JSONObject,
    private val changed: () -> Unit,
    private val action: (String, String) -> Unit,
    private val prune: (List<Camera>) -> Unit = {},
    private val integrations: ConfigurationIntegrations = ConfigurationIntegrations(),
) {
    constructor(context: Context, changed: () -> Unit, action: (String, String) -> Unit) : this(
        LauncherPrefs(context), {
            JSONObject().put("helperEnabled", context.isHomeOverrideServiceOn())
                .put("helperRunning", HomeOverrideService.running).put("watchNextAllowed", context.canReadWatchNext())
                .put("version", BuildConfig.VERSION_NAME).put("buildCode", BuildConfig.VERSION_CODE).put("builtAt", BuildConfig.BUILD_TIME)
                .put("decoders", runCatching { cameraDecoderSummary() }.getOrDefault("Unavailable")).put("screensaver", context.webScreensaverStatus())
                .put("logBytes", AppLog.sizeBytes())
                .put("performance", HalPerformance.snapshot())
        }, changed, action, { cameras -> pruneCameraSnapshots(context, cameras.flatMap { listOf(it.url, it.substreamUrl) }) })
    private val lock = Mutex()
    private var revision = UUID.randomUUID().toString()
    private var snapshot = ""
    private val flows = mutableMapOf<String, Triple<String, String, Long>>()

    private fun sections(): JSONObject {
        val video = prefs.videoBackgrounds
        val artwork = prefs.artworkSettings
        return JSONObject()
            .put("launcher", JSONObject().put("catchHomeKey", prefs.catchHomeKey).put("reopenAfterHome", prefs.reopenAfterHome))
            .put("appearance", JSONObject().put("theme", prefs.appearanceTheme.name)
                .put("baseOpacity", prefs.dashboardCardOpacity ?: JSONObject.NULL)
                .put("popupColumns", video.popupCardColumns)
                .put("popupOpacity", video.popupCardOpacity ?: JSONObject.NULL)
                .put("focusedOpacity", video.focusedCardOpacity ?: JSONObject.NULL).put("unfocusedOpacity", video.unfocusedCardOpacity ?: JSONObject.NULL)
                .put("collapse", prefs.appsCardCollapse.name).put("cycleColors", prefs.cycleUiColors))
            .put("artwork", JSONObject().put("enabled", artwork.enabled).put("useLocal", artwork.useLocal)
                .put("region", artwork.region).put("services", JSONArray(artwork.services.toList())))
            .put("weather", JSONObject().put("dashboard", video.dashboardForecast.value)
                .put("screensaver", video.screensaverForecast.value).put("card", video.cardForecast.value))
            .put("homeassistant", JSONObject().put("url", publicIntegrationUrl(prefs.haUrl)).put("username", prefs.haUsername)
                .put("connected", prefs.haToken.isNotEmpty() || prefs.haRefreshToken.isNotEmpty())
                .put("method", if (prefs.haToken.isNotEmpty()) "token" else "password").put("topBar", JSONArray(prefs.haTopBar)))
    }

    private fun publicSections(): JSONObject {
        val cameras = prefs.cameras
        val ids = cameras.map { it.id }.toSet()
        val frigate = prefs.frigate
        return sections().put("cameras", JSONObject()
            .put("damaged", prefs.cameraSettingsDamaged)
            .put("items", JSONArray(cameras.map { c -> JSONObject()
                .put("id", c.id).put("name", c.name).put("tcp", c.useTcp)
                .put("audio", c.useAudio).put("subAudio", c.useSubstreamAudio)
                .put("stretch", c.stretch).put("subStretch", c.stretchSubstream)
                .put("url", "").put("sub", "").put("urlSaved", c.url.isNotEmpty()).put("subSaved", c.substreamUrl.isNotEmpty()) }))
            .put("selected", prefs.selectedCamera.takeIf { it in ids }.orEmpty())
            .put("showHome", prefs.showHomeCamera).put("gridLive", prefs.cameraGridLive)
            .put("minimizeStreams", prefs.minimizeVideoStreams).put("seconds", prefs.screensaverSeconds)
            .put("hidden", JSONArray(prefs.screensaverHiddenCameras.filter { it in ids })).put("showProgress", prefs.screensaverShowProgress))
            .put("frigate", JSONObject().put("url", publicIntegrationUrl(frigate.url)).put("username", frigate.username)
                .put("passwordSaved", frigate.password.isNotEmpty()).put("certificateSaved", frigate.certPin.isNotEmpty()))
            .put("maintenance", JSONObject().put("logHours", prefs.logHoursKept).put("showMemory", prefs.showFreeMemory))
    }

    private fun updateRevision(sections: JSONObject) {
        // Structured private values avoid ambiguous concatenation and detect legacy URL credential changes.
        val frigate = prefs.frigate
        val current = JSONObject().put("sections", sections)
            .put("ha", JSONObject().put("url", prefs.haUrl).put("token", prefs.haToken).put("refresh", prefs.haRefreshToken))
            .put("frigate", JSONObject().put("url", frigate.url).put("username", frigate.username).put("password", frigate.password).put("pin", frigate.certPin))
            .put("cameras", JSONArray(camerasToJson(prefs.cameras))).toString()
        if (current != snapshot) { snapshot = current; revision = UUID.randomUUID().toString() }
    }

    private fun requireCurrent(expected: String) {
        updateRevision(publicSections())
        require(revision == expected) { "Settings changed while connecting. Reload this section before saving." }
    }

    private suspend fun read(): JSONObject {
        val sections = publicSections()
        updateRevision(sections)
        val version = revision
        val status = withContext(Dispatchers.IO) { deviceStatus() }
        return JSONObject().put("revision", version).put("sections", sections)
            .put("choices", JSONObject().put("services", JSONArray(streamingServices.map { it.name }))
                .put("cameraSeconds", JSONArray(SCREENSAVER_SECONDS)).put("maxActions", MAX_HA_ACTIONS))
            .put("status", status.put("homeAutoOff", prefs.replaceHomeAutoOff))
    }

    suspend fun request(body: JSONObject?, canCommit: () -> Boolean = { true }): JSONObject = lock.withLock {
        val before = read()
        if (body == null) return@withLock before
        require(body.getString("revision") == before.getString("revision")) { "Settings changed on the TV or in another browser. Reload this section before saving." }
        requireCurrent(body.getString("revision"))
        require(canCommit()) { "The editor session ended. Reopen it before saving." }
        val section = body.getString("section")
        val value = body.getJSONObject("values")
        when (section) {
            "launcher" -> {
                val catch = value.getBoolean("catchHomeKey")
                val reopen = value.getBoolean("reopenAfterHome")
                require(!(catch || reopen) || prefs.homeOverrideAccepted || value.optBoolean("consent")) { "Accept the Home helper explanation first." }
                if (catch || reopen) prefs.homeOverrideAccepted = true
                prefs.catchHomeKey = catch; prefs.reopenAfterHome = reopen; prefs.replaceHomeAutoOff = false
            }
            "appearance" -> {
                val theme = enumValue<LauncherTheme>(value, "theme")
                val collapse = enumValue<AppsCardCollapse>(value, "collapse")
                val base = value.opacity("baseOpacity"); val focused = value.opacity("focusedOpacity"); val unfocused = value.opacity("unfocusedOpacity")
                val popup = if (value.has("popupOpacity")) value.opacity("popupOpacity") else prefs.videoBackgrounds.popupCardOpacity
                val popupColumns = if (value.has("popupColumns")) value.integer("popupColumns", 1..2) else prefs.videoBackgrounds.popupCardColumns
                val cycle = value.getBoolean("cycleColors")
                prefs.saveAppearance(theme, collapse, base, cycle, prefs.videoBackgrounds.let {
                    it.copy(revision = it.revision + 1, focusedCardOpacity = focused, unfocusedCardOpacity = unfocused, popupCardOpacity = popup, popupCardColumns = popupColumns)
                })
            }
            "weather" -> {
                val modes = listOf("dashboard", "screensaver", "card").map { key ->
                    requireNotNull(ForecastDisplay.entries.firstOrNull { it.value == value.getString(key) }) { "Choose Off, Hourly, 5-day or Hourly + 5-day." }
                }
                prefs.videoBackgrounds = prefs.videoBackgrounds.let { it.copy(revision = it.revision + 1,
                    dashboardForecast = modes[0], screensaverForecast = modes[1], cardForecast = modes[2]) }
            }
            "artwork" -> {
                val services = value.getJSONArray("services").strings()
                val region = value.getString("region")
                val local = value.getBoolean("useLocal")
                require(services.all { name -> streamingServices.any { it.name == name } }) { "Unknown streaming service." }
                require(region.isBlank() && (services.isEmpty() || local) || artworkRegion(region) != null) { "Enter a valid two-letter streaming country." }
                prefs.artworkSettings = ArtworkSettings(value.getBoolean("enabled"), artworkRegion(region).orEmpty(), services.toSet(), local)
            }
            "cameras" -> saveCameras(value)
            "homeassistant" -> {
                val actions = value.getJSONArray("topBar").strings()
                require(actions.size <= MAX_HA_ACTIONS && actions.distinct() == actions && actions.all { isHaAction(it) && it.matches(Regex("[a-z_]+\\.[a-z0-9_]+")) }) { "Choose distinct supported header actions within the limit." }
                prefs.saveHaChoice(prefs.haEntities, actions)
            }
            "ha-login" -> return@withLock login(value, before.getString("revision"), canCommit)
            "ha-logout" -> {
                clearMediaImages()
                val old = HaServer(prefs.haUrl, prefs.haToken, prefs.haRefreshToken)
                prefs.saveHaLogin(old.copy(token = "", refreshToken = ""))
                changed()
                integrations.revokeHa(old)
            }
            "frigate" -> saveFrigate(value, before.getString("revision"), canCommit)
            "maintenance" -> {
                val hours = value.integer("logHours", LOG_HOURS_MIN..LOG_HOURS_MAX)
                val memory = value.getBoolean("showMemory")
                prefs.logHoursKept = hours; prefs.showFreeMemory = memory; AppLog.keepHours(hours)
            }
            "action" -> {
                val name = value.getString("name")
                require(name in listOf("accessibility", "screensaver", "restart", "camera-test", "watch-next")) { "Unknown TV action." }
                val id = value.optString("cameraId")
                require(name != "camera-test" || prefs.cameras.any { it.id == id }) { "Select a saved camera to test." }
                action(name, id)
            }
            else -> throw IllegalArgumentException("Unknown settings section.")
        }
        changed()
        JSONObject().put("saved", true).put("configuration", read())
    }

    private fun saveCameras(value: JSONObject) {
        val current = prefs.cameras.associateBy { it.id }
        val items = value.getJSONArray("items")
        require(items.length() <= 100) { "Keep at most 100 cameras." }
        val cameras = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val id = item.getString("id").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
            val name = item.getString("name").trim().also { require(it.length in 1..160) { "Give every camera a name." } }
            fun stream(key: String, old: String): String = item.getString(key).trim().ifEmpty { if (item.optBoolean("clearSub") && key == "sub") "" else old }
            val url = stream("url", current[id]?.url.orEmpty())
            val sub = stream("sub", current[id]?.substreamUrl.orEmpty())
            require(validCameraStream(url) && (sub.isEmpty() || validCameraStream(sub))) { "Camera streams must use valid RTSP addresses." }
            Camera(id, name, url, item.getBoolean("tcp"), sub, item.getBoolean("audio"), item.getBoolean("subAudio"), item.getBoolean("stretch"), item.getBoolean("subStretch"))
        }
        require(cameras.map { it.id }.distinct().size == cameras.size) { "Camera IDs must be unique." }
        val selected = value.getString("selected")
        val hidden = value.getJSONArray("hidden").strings()
        require(selected.isEmpty() || cameras.any { it.id == selected }) { "Selected camera no longer exists." }
        require(hidden.all { id -> cameras.any { it.id == id } }) { "Unknown hidden camera." }
        val seconds = value.integer("seconds", 5..300).also { require(it in SCREENSAVER_SECONDS) }
        val showHome = value.getBoolean("showHome"); val grid = value.getBoolean("gridLive")
        val minimize = value.getBoolean("minimizeStreams"); val progress = value.getBoolean("showProgress")
        prefs.cameras = cameras; prefs.selectedCamera = selected; prefs.showHomeCamera = showHome
        prefs.cameraGridLive = grid; prefs.minimizeVideoStreams = minimize; prefs.screensaverSeconds = seconds
        prefs.screensaverHiddenCameras = hidden; prefs.screensaverShowProgress = progress
        prune(cameras)
    }

    private suspend fun login(value: JSONObject, expectedRevision: String, canCommit: () -> Boolean): JSONObject {
        val typedUrl = requireNotNull(normalizeHaUrl(value.getString("url"))) { "Enter a valid Home Assistant address." }
        val username = value.optString("username").trim()
        val old = HaServer(prefs.haUrl, prefs.haToken, prefs.haRefreshToken)
        val pendingUrl = flows[value.optString("flow")]?.first
        val url = if (pendingUrl != null && typedUrl == publicIntegrationUrl(pendingUrl)) pendingUrl
            else if (typedUrl == publicIntegrationUrl(old.url) && value.optString("token").isEmpty() && value.optString("password").isEmpty()) old.url else typedUrl
        var created: HaServer? = null
        val server = if (value.getString("method") == "token") {
            val token = value.optString("token").trim().ifEmpty { if (url == old.url) old.token else "" }
            require(token.isNotEmpty()) { "Enter a token for this server." }
            HaServer(url, token = token)
        } else {
            val flow = value.optString("flow")
            val password = value.optString("password")
            val step = if (flow.isNotEmpty()) {
                val pending = requireNotNull(flows.remove(flow)) { "Login expired. Start again." }
                require(pending.first == url && pending.second == username && System.currentTimeMillis() - pending.third < 300_000) { "Login expired. Start again." }
                integrations.sendCode(url, flow, value.getString("code"))
            } else if (password.isNotEmpty()) integrations.loginHa(url, username, password) else null
            when (step) {
                is HaLoginStep.NeedsCode -> {
                    flows.entries.removeAll { System.currentTimeMillis() - it.value.third > 300_000 }
                    require(flows.size < 16) { "Too many pending logins. Wait five minutes." }
                    flows[step.flowId] = Triple(url, username, System.currentTimeMillis())
                    return JSONObject().put("needsCode", true).put("flow", step.flowId).put("message", step.error ?: "Enter your authenticator code.")
                }
                is HaLoginStep.LoggedIn -> HaServer(url, refreshToken = step.refreshToken).also { created = it }
                null -> {
                    require(url == old.url && username == prefs.haUsername && old.refreshToken.isNotEmpty()) { "Enter your username and password." }
                    old
                }
            }
        }
        try {
            integrations.verifyHa(server)
            requireCurrent(expectedRevision)
            require(canCommit()) { "The editor session ended while connecting. Reopen it before saving." }
            if (server != old) clearMediaImages()
            prefs.saveHaLogin(server, username)
            changed()
        } catch (failure: Exception) {
            created?.let { fresh -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { integrations.revokeHa(fresh) } }
            throw failure
        }
        if (old.refreshToken != server.refreshToken) haRevoke(old)
        return JSONObject().put("saved", true).put("configuration", read())
    }

    private suspend fun saveFrigate(value: JSONObject, expectedRevision: String, canCommit: () -> Boolean) {
        if (value.optBoolean("remove")) { prefs.frigate = FrigateServer(); return }
        val old = prefs.frigate
        val typedUrl = requireNotNull(normalizeFrigateUrl(value.getString("url"))) { "Enter a valid Frigate address." }
        val username = value.getString("username").trim()
        val url = if (typedUrl == publicIntegrationUrl(old.url) && value.getString("password").isEmpty()) old.url else typedUrl
        val password = value.getString("password").ifEmpty { if (url == old.url && username == old.username) old.password else "" }
        require(username.isNotEmpty() && password.isNotEmpty()) { "Enter the Frigate login." }
        val trust = value.optBoolean("trustCertificate")
        require(url.startsWith("http://") || trust || url == old.url && old.certPin.isNotEmpty()) { "Confirm trusting the certificate presented by this server." }
        val server = FrigateServer(url, username, password, if (!trust && url == old.url) old.certPin else "")
        val pin = integrations.verifyFrigate(server)
        requireCurrent(expectedRevision)
        require(canCommit()) { "The editor session ended while connecting. Reopen it before saving." }
        prefs.frigate = server.copy(certPin = pin.ifEmpty { server.certPin })
    }
}

internal fun JSONObject.integer(key: String, range: IntRange): Int {
    val number = getDouble(key)
    require(number.isFinite() && number % 1.0 == 0.0 && number >= range.first && number <= range.last) { "Invalid $key value." }
    return number.toInt()
}
internal fun JSONObject.opacity(key: String): Int? = if (isNull(key)) null else integer(key, 0..100)
private inline fun <reified T : Enum<T>> enumValue(json: JSONObject, key: String): T =
    requireNotNull(enumValues<T>().firstOrNull { it.name == json.getString(key) }) { "Invalid $key choice." }
internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
internal fun validCameraStream(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    url.length <= 4096 && uri.scheme?.lowercase() in setOf("rtsp", "rtsps") && !uri.host.isNullOrEmpty() && (uri.port == -1 || uri.port in 1..65535) && uri.fragment == null
}.getOrDefault(false)

/** Integration addresses can contain legacy HTTP basic-auth credentials. */
internal fun publicIntegrationUrl(url: String): String = url.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")?.build()?.toString()?.trimEnd('/').orEmpty()

/** Real integration work is injected in tests to exercise changes that occur during network waits. */
internal open class ConfigurationIntegrations {
    open suspend fun loginHa(url: String, username: String, password: String): HaLoginStep = haLogIn(url, username, password)
    open suspend fun sendCode(url: String, flow: String, code: String): HaLoginStep = haSendCode(url, flow, code)
    open suspend fun verifyHa(server: HaServer) { haSession(server) { getStates() } }
    open suspend fun revokeHa(server: HaServer) { haRevoke(server) }
    open suspend fun verifyFrigate(server: FrigateServer): String {
        val client = FrigateClient(server)
        client.alerts()
        return client.seenCertPin
    }
}
