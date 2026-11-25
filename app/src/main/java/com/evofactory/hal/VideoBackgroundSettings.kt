package com.evofactory.hal

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal const val VIDEO_LIBRARY_LIMIT = 50
internal const val VIDEO_PLAYLIST_LIMIT = 24

internal data class VideoPlaylist(val id: String, val title: String, val videoIds: List<String>, val enabled: Boolean = false)

internal data class BackgroundVideo(
    val id: String, val title: String, val url: String,
    val background: Boolean = false, val screensaver: Boolean = false, val randomStart: Boolean = false,
)
internal data class VideoBackgroundSettings(
    val revision: Long = 0,
    val videos: List<BackgroundVideo> = emptyList(),
    val shuffle: Boolean = false,
    val switchSeconds: Int = 0,
    val dashboardDim: Int = 66,
    val screensaverDim: Int = 72,
    val playlists: List<VideoPlaylist> = emptyList(),
    val focusedCardOpacity: Int? = null,
    val unfocusedCardOpacity: Int? = null,
    val dashboardForecast: ForecastDisplay = ForecastDisplay.Off,
    val screensaverForecast: ForecastDisplay = ForecastDisplay.Off,
    val cardForecast: ForecastDisplay = dashboardForecast,
    val popupCardOpacity: Int? = focusedCardOpacity,
    val popupCardColumns: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().put("revision", revision).put("schema", 4)
        .put("shuffle", shuffle).put("switchSeconds", switchSeconds)
        .put("dashboardDim", dashboardDim).put("screensaverDim", screensaverDim)
        .put("focusedCardOpacity", focusedCardOpacity ?: JSONObject.NULL)
        .put("unfocusedCardOpacity", unfocusedCardOpacity ?: JSONObject.NULL)
        .put("popupCardOpacity", popupCardOpacity ?: JSONObject.NULL)
        .put("popupCardColumns", popupCardColumns)
        .put("dashboardForecast", dashboardForecast.value).put("screensaverForecast", screensaverForecast.value).put("cardForecast", cardForecast.value)
        .put("playlists", JSONArray(playlists.map {
            JSONObject().put("id", it.id).put("title", it.title).put("videoIds", JSONArray(it.videoIds)).put("enabled", it.enabled)
        }))
        .put("videos", JSONArray(videos.map { video ->
            JSONObject().put("id", video.id).put("title", video.title).put("url", video.url)
                .put("background", video.background).put("screensaver", video.screensaver).put("randomStart", video.randomStart)
        }))
}

internal fun videoBackgroundSettingsFromJson(json: JSONObject): VideoBackgroundSettings {
    val revision = json.getLong("revision").also { require(it >= 0) { "Invalid video library revision." } }
    val source = json.getJSONArray("videos")
    val legacy = !json.has("schema")
    val schema = if (legacy) 1 else json.getInt("schema")
    require(schema in 1..4) { "Unsupported video settings version." }
    require(source.length() <= VIDEO_LIBRARY_LIMIT) { "Keep at most $VIDEO_LIBRARY_LIMIT videos in the library." }
    val videos = (0 until source.length()).map { index ->
        val item = source.getJSONObject(index)
        val id = item.getString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid video ID." }
        val title = item.getString("title").trim()
        require(title.length in 1..160) { "Give every video a name of up to 160 characters." }
        val url = item.getString("url").trim()
        require(validBackgroundVideoUrl(url)) { "Use a direct HTTP or HTTPS video URL without an embedded username or password." }
        fun flag(key: String) = if (legacy) json.getBoolean(key) else item.getBoolean(key)
        BackgroundVideo(id, title, url, flag("background"), flag("screensaver"), flag("randomStart"))
    }
    require(videos.map { it.id }.distinct().size == videos.size) { "Video IDs must be unique." }
    val playlists = if (schema < 4) {
        if (videos.isEmpty()) emptyList() else listOf(VideoPlaylist("existing-videos", "Existing videos", videos.map { it.id }, true))
    } else {
        val sourcePlaylists = json.getJSONArray("playlists")
        require(sourcePlaylists.length() <= VIDEO_PLAYLIST_LIMIT) { "Keep at most $VIDEO_PLAYLIST_LIMIT playlists." }
        val known = videos.map { it.id }.toSet()
        (0 until sourcePlaylists.length()).map { index ->
            val item = sourcePlaylists.getJSONObject(index)
            val id = item.getString("id")
            val title = item.getString("title").trim()
            require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid playlist ID." }
            require(title.length in 1..160) { "Give each playlist a name of up to 160 characters." }
            val members = item.getJSONArray("videoIds")
            require(members.length() <= VIDEO_LIBRARY_LIMIT) { "Too many videos in a playlist." }
            val ids = (0 until members.length()).map { members.getString(it) }
            require(ids.distinct().size == ids.size && ids.all { it in known }) { "A playlist references an unknown or duplicate video." }
            VideoPlaylist(id, title, ids, item.getBoolean("enabled"))
        }.also { require(it.map { p -> p.id }.distinct().size == it.size) { "Playlist IDs must be unique." } }
    }
    fun integer(key: String, default: Int, range: IntRange): Int {
        if (legacy) return default
        val value = json.getDouble(key)
        require(value.isFinite() && value % 1.0 == 0.0 && value >= range.first && value <= range.last) { "Invalid $key value." }
        return value.toInt()
    }
    fun opacity(key: String): Int? = if (schema < 4 || json.isNull(key)) null else integer(key, 50, 0..100)
    fun forecast(key: String): ForecastDisplay = if (!json.has(key)) ForecastDisplay.Off else
        requireNotNull(ForecastDisplay.entries.firstOrNull { it.value == (json.opt(key) as? String) }) { "Invalid weather forecast choice." }
    return VideoBackgroundSettings(revision, videos, if (legacy) false else json.getBoolean("shuffle"),
        integer("switchSeconds", 0, 0..86400).also { require(it == 0 || it >= 10) { "Switch videos at least 10 seconds apart." } },
        integer("dashboardDim", 66, 0..100), integer("screensaverDim", 72, 0..100), playlists,
        opacity("focusedCardOpacity"), opacity("unfocusedCardOpacity"), forecast("dashboardForecast"), forecast("screensaverForecast"), if (json.has("cardForecast")) forecast("cardForecast") else forecast("dashboardForecast"),
        if (json.has("popupCardOpacity")) opacity("popupCardOpacity") else opacity("focusedCardOpacity"),
        if (json.has("popupCardColumns")) integer("popupCardColumns", 1, 1..2) else 1)
}

internal fun VideoBackgroundSettings.videosFor(screensaver: Boolean): List<BackgroundVideo> {
    val active = playlists.filter { it.enabled }.flatMap { it.videoIds }.toSet()
    return videos.filter { it.id in active && if (screensaver) it.screensaver else it.background }
}

/** Keep the original gradients at the defaults, with truly clear/black endpoints. */
internal fun backgroundShadeAlpha(base: Float, percent: Int, reference: Int): Float {
    val level = percent.coerceIn(0, 100)
    return if (level <= reference) base * level / reference else base + (1f - base) * (level - reference) / (100 - reference)
}

internal fun validBackgroundVideoUrl(url: String): Boolean = try {
    val uri = URI(url)
    url.length in 1..4096 && uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535)
} catch (_: Exception) { false }

/** A known seekable duration is required; keep a short tail so a random start is useful. */
internal fun backgroundVideoStart(duration: Long, seekable: Boolean, random: Boolean, fraction: Double): Long {
    if (!random || !seekable || duration <= 0) return 0
    val upper = (duration - minOf(10_000L, duration / 10)).coerceAtLeast(1)
    return (upper.toDouble() * fraction.coerceIn(0.0, 0.999999999)).toLong().coerceIn(0, duration - 1)
}

internal fun backgroundVideoWanted(settings: VideoBackgroundSettings, screensaver: Boolean, foreground: Boolean, decoderReserved: Boolean): Boolean =
    foreground && !decoderReserved && settings.videosFor(screensaver).isNotEmpty()

/** Ownership can transfer before the previous Compose host disposes its effects. */
internal class BackgroundVideoOwnership {
    private var owner: Any? = null
    private var playback: VideoBackgroundSettings? = null

    fun acquire(token: Any, settings: VideoBackgroundSettings, hasSession: Boolean): Boolean {
        owner = token
        val restart = playback != settings || !hasSession
        playback = settings
        return restart
    }

    fun relinquish(token: Any): Boolean {
        if (owner !== token) return false
        owner = null
        return true
    }

    fun released() { playback = null }
}
