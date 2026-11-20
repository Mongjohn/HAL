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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun isHaMediaPlayer(id: String) = id.startsWith("media_player.")

internal val MediaArtworkNaturalSide = 76.dp
internal val MediaPanelVerticalPadding = 4.dp

@Composable
internal fun MediaPlayerReading(
    id: String,
    entity: HaEntity?,
    name: String?,
    session: HaSession?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    showPrimaryActionIcon: Boolean = true,
    showProgress: Boolean = !compact,
) {
    val colors = MaterialTheme.colorScheme
    val accent = cycleUiColor(colors.primary)
    val secondaryText = cycleUiColor(colors.onSurfaceVariant)
    val attributes = entity?.attributes
    fun attribute(key: String): String? = (attributes?.opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

    val state = entity?.state
    val player = name ?: entity?.name ?: id
    val stateLabel = when (state) {
        null, "", "unknown" -> "Unknown"
        "unavailable" -> "Unavailable"
        else -> entity.reading
    }
    val hasMedia = when (state) {
        null, "", "unknown", "unavailable", "off", "standby" -> false
        else -> true
    }
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

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(session, artwork, entity?.icon ?: "mdi:cast", compact)
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
            Text(status, style = MaterialTheme.typography.labelSmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact && details != null) {
                Text(details, style = MaterialTheme.typography.labelSmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showProgress) {
                entity?.mediaPlayback?.let { MediaProgress(it) }
            }
        }
        val actionIcon = when (haPrimaryService(id, state.orEmpty(), attributes?.optInt("supported_features") ?: 0)) {
            "media_pause" -> "pause"
            "media_play" -> "play"
            "turn_on", "turn_off" -> "power"
            else -> null
        }
        if (showPrimaryActionIcon) actionIcon?.let { MdiIcon(it, size = 18.dp, color = accent) }
    }
}

@Composable
private fun MediaArtwork(session: HaSession?, artwork: String?, fallbackIcon: String, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    val accent = cycleUiColor(colors.primary)
    val surface = cycleUiColor(darkenCycleSurface(colors.surface))
    val backing = remember(accent, surface) { Brush.linearGradient(listOf(accent.copy(alpha = 0.32f), surface)) }
    var decodeSize by remember { mutableIntStateOf(0) }
    val target = decodeSize
    val image = key(session, artwork) {
        val loaded by produceState<ImageBitmap?>(null, target) {
            if (session == null || artwork == null || target == 0) return@produceState
            val bytes = session.mediaArtwork(artwork) ?: return@produceState
            value = withContext(Dispatchers.Default) { decodeMediaArtwork(bytes, target) }
        }
        loaded
    }
    Box(
        Modifier.layout { measurable, constraints ->
            val side = mediaArtworkSide(
                constraints.maxWidth, constraints.maxHeight, (if (compact) 48.dp else MediaArtworkNaturalSide).roundToPx(), 108.dp.roundToPx(),
            )
            val square = measurable.measure(Constraints.fixed(side, side))
            layout(side, side) { square.placeRelative(0, 0) }
        }.onSizeChanged {
            decodeSize = mediaArtworkDecodeSize(it.width)
        }.clip(RoundedCornerShape(8.dp)).background(backing),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            MdiIcon(fallbackIcon, size = if (compact) 22.dp else 32.dp, color = accent, fallback = "cast")
        }
    }
}

internal fun mediaArtworkSide(width: Int, height: Int, naturalSide: Int, metadataWidth: Int): Int {
    val availableHeight = if (height == Constraints.Infinity) naturalSide else height
    // Leave room for the title, playback labels and action beside the artwork.
    return minOf(availableHeight, (width * 0.66f).toInt(), (width - metadataWidth).coerceAtLeast(width / 3))
}

internal fun mediaArtworkDecodeSize(width: Int): Int {
    if (width == 0) return 0
    // Keep small size changes within the same request and bitmap allocation.
    return ((width.coerceAtMost(1152) + 127) / 128) * 128
}

@Composable
private fun MediaProgress(playback: MediaPlayback) {
    val duration = playback.sample.duration
    val position = key(playback) {
        produceState(playback.positionAt(mediaClockMillis())) {
            while (playback.playing && value < duration) {
                delay(250)
                value = playback.positionAt(mediaClockMillis())
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val secondaryText = cycleUiColor(colors.onSurfaceVariant)
    val accent = cycleUiColor(colors.primary)
    Box(
        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))
            .background(secondaryText.copy(alpha = 0.25f))
            .drawBehind { drawRect(accent, size = Size(size.width * (position.value / duration).toFloat(), size.height)) }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo((position.value / duration).toFloat(), 0f..1f) },
    )
    MediaProgressLabels(duration, position, secondaryText)
}

@Composable
private fun MediaProgressLabels(duration: Double, position: State<Double>, color: Color) {
    val seconds by remember(position) { derivedStateOf { position.value.toLong() } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(mediaTime(seconds), style = MaterialTheme.typography.labelSmall, color = color)
        Text(remember(duration) { mediaTime(duration.toLong()) }, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

internal fun mediaClockMillis(): Long = System.nanoTime() / 1_000_000

@ConsistentCopyVisibility
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

private fun mediaTime(seconds: Long): String {
    val minutes = seconds / 60
    val tail = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$tail"
    else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail"
}

private fun decodeMediaArtwork(bytes: ByteArray, target: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = mediaArtworkSampleSize(bounds.outWidth, bounds.outHeight, target)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

internal fun mediaArtworkSampleSize(width: Int, height: Int, target: Int): Int {
    val shortEdge = minOf(width, height)
    val longEdge = maxOf(width, height)
    var sample = 1
    while (shortEdge / (sample * 2) >= target.coerceAtLeast(1) || longEdge / sample > 1536) sample *= 2
    return sample
}
