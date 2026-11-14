package com.evofactory.hal

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun isHaMediaPlayer(id: String) = id.startsWith("media_player.")

@Composable
internal fun MediaPlayerReading(
    id: String,
    entity: HaEntity?,
    name: String?,
    session: HaSession?,
    compact: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val attributes = entity?.attributes
    fun attribute(key: String): String? = (attributes?.opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

    val state = entity?.state
    val player = name ?: entity?.name ?: id
    val stateLabel = when (state) {
        null, "", "unknown" -> "Unknown"
        "unavailable" -> "Unavailable"
        else -> entity.reading
    }
    val hasMedia = state !in setOf(null, "", "unknown", "unavailable", "off", "standby")
    val title = attribute("media_title").takeIf { hasMedia }
    val series = attribute("media_series_title").takeIf { hasMedia && it != title }
    val artist = (attribute("media_artist") ?: attribute("media_album_artist")).takeIf { hasMedia }
    val album = attribute("media_album_name").takeIf { hasMedia && it != title && it != artist }
    val source = listOfNotNull(attribute("app_name"), attribute("source"), attribute("media_channel"))
        .distinct().joinToString(" · ")
    val artwork = attribute("entity_picture").takeIf { hasMedia }
    val primary = title ?: series ?: source.takeIf(String::isNotEmpty) ?: player
    val subtitle = listOfNotNull(artist ?: series, album).distinct().filter { it != primary }
    val status = if (primary == player) stateLabel else "$player · $stateLabel"
    val details = source.takeUnless { it == primary || it.isEmpty() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val size = if (compact) 48.dp else 76.dp
            val image = key(session, artwork) {
                val loaded by produceState<ImageBitmap?>(null) {
                    val bytes = artwork?.let { session?.mediaArtwork(it) } ?: return@produceState
                    value = withContext(Dispatchers.Default) { decodeMediaArtwork(bytes) }
                }
                loaded
            }
            Box(
                Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(
                    Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.32f), colors.surface)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(image, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                } else {
                    MdiIcon(entity?.icon ?: "mdi:cast", size = if (compact) 22.dp else 32.dp, color = colors.primary, fallback = "cast")
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    primary,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = if (compact) subtitle.joinToString(" · ").takeIf(String::isNotEmpty)?.let(::listOf).orEmpty() else subtitle
                for (line in secondary) {
                    Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact && details != null) {
                    Text(details, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val actionIcon = when (haPrimaryService(id, state.orEmpty(), attributes?.optInt("supported_features") ?: 0)) {
                "media_pause" -> "pause"
                "media_play" -> "play"
                "turn_on", "turn_off" -> "power"
                else -> null
            }
            actionIcon?.let { MdiIcon(it, size = 18.dp, color = colors.primary) }
        }
        if (!compact) {
            entity?.mediaPlayback?.let { MediaProgress(it) }
        }
    }
}

@Composable
private fun MediaProgress(playback: MediaPlayback) {
    val duration = playback.sample.duration
    val seconds = key(playback) {
        val position by produceState(playback.positionAt(mediaClockMillis())) {
            while (playback.playing && value < duration) {
                delay(250)
                value = playback.positionAt(mediaClockMillis())
            }
        }
        position
    }
    val fraction = (seconds / duration).toFloat().coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))
            .background(colors.onSurfaceVariant.copy(alpha = 0.25f))
            .drawBehind { drawRect(colors.primary, size = Size(size.width * fraction, size.height)) }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(mediaTime(seconds), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(mediaTime(duration), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

internal fun mediaClockMillis(): Long = System.nanoTime() / 1_000_000

internal data class MediaPlayback private constructor(
    val sample: Sample,
    val playing: Boolean,
    private val anchorPosition: Double,
    private val anchorTime: Long,
) {
    data class Sample(val duration: Double, val position: Double, val updatedAt: String?, val track: List<String?>)

    fun positionAt(now: Long): Double {
        val elapsed = if (playing) (now - anchorTime).coerceAtLeast(0) / 1000.0 else 0.0
        return (anchorPosition + elapsed).coerceIn(0.0, sample.duration)
    }

    companion object {
        fun observe(
            state: String,
            attributes: JSONObject,
            previous: MediaPlayback?,
            now: Long,
            wallTime: Long,
        ): MediaPlayback? {
            if (state in setOf("", "unknown", "unavailable", "off", "standby")) return null
            val duration = attributes.optDouble("media_duration").takeIf { it.isFinite() && it > 0 } ?: return null
            val position = attributes.optDouble("media_position").takeIf { it.isFinite() && it >= 0 } ?: return null
            val updated = attributes.opt("media_position_updated_at") as? String
            val track = listOf("media_content_id", "media_title", "media_artist", "media_series_title", "media_season", "media_episode")
                .map { attributes.opt(it)?.takeUnless { value -> value == JSONObject.NULL }?.toString() }
            val sample = Sample(duration, position, updated, track)
            val playing = state == "playing"
            if (previous != null && previous.sample == sample) {
                // Unrelated updates keep the clock running. Pause and resume can arrive without a new position.
                if (previous.playing == playing) return previous
                return previous.copy(playing = playing, anchorPosition = previous.positionAt(now), anchorTime = now)
            }

            val timestamp = updated?.takeIf { playing && it != previous?.sample?.updatedAt }?.let {
                try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (_: DateTimeParseException) { null }
            }
            val age = timestamp?.let { (wallTime.toDouble() - it) / 1000 }
            // Use receipt time when the timestamp is absent, reused, or inconsistent with this position.
            val reportedElapsed = age?.takeIf { it >= 0 && it <= duration - position + 2 } ?: 0.0
            return MediaPlayback(sample, playing, position + reportedElapsed, now)
        }
    }
}

private fun mediaTime(seconds: Double): String {
    val total = seconds.toLong()
    val minutes = total / 60
    return if (minutes < 60) String.format(Locale.ROOT, "%d:%02d", minutes, total % 60)
    else String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, total % 60)
}

private fun decodeMediaArtwork(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
