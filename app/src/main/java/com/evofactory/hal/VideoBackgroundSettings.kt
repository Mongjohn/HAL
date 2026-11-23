package com.evofactory.hal

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal const val VIDEO_LIBRARY_LIMIT = 50

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
) {
    fun toJson(): JSONObject = JSONObject().put("revision", revision).put("schema", 3)
        .put("shuffle", shuffle).put("switchSeconds", switchSeconds)
        .put("dashboardDim", dashboardDim).put("screensaverDim", screensaverDim)
        .put("videos", JSONArray(videos.map { video ->
            JSONObject().put("id", video.id).put("title", video.title).put("url", video.url)
                .put("background", video.background).put("screensaver", video.screensaver).put("randomStart", video.randomStart)
        }))
}

internal fun videoBackgroundSettingsFromJson(json: JSONObject): VideoBackgroundSettings {
    val revision = json.getLong("revision").also { require(it >= 0) { "Invalid video library revision." } }
    val source = json.getJSONArray("videos")
    val legacy = !json.has("schema")
    require(legacy || json.getInt("schema") in 2..3) { "Unsupported video settings version." }
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
    fun integer(key: String, default: Int, range: IntRange): Int {
        if (legacy) return default
        val value = json.getDouble(key)
        require(value.isFinite() && value % 1.0 == 0.0 && value >= range.first && value <= range.last) { "Invalid $key value." }
        return value.toInt()
    }
    return VideoBackgroundSettings(revision, videos, if (legacy) false else json.getBoolean("shuffle"),
        integer("switchSeconds", 0, 0..86400).also { require(it == 0 || it >= 10) { "Switch videos at least 10 seconds apart." } },
        integer("dashboardDim", 66, 0..100), integer("screensaverDim", 72, 0..100))
}

internal fun VideoBackgroundSettings.videosFor(screensaver: Boolean): List<BackgroundVideo> =
    videos.filter { if (screensaver) it.screensaver else it.background }

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
    foreground && !decoderReserved && settings.videos.any { if (screensaver) it.screensaver else it.background }

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
