package com.evofactory.hal

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Prefer the user's configured weather, then a deterministic available HA weather source. */
internal fun screensaverWeather(preferred: List<String>, entities: Map<String, HaEntity>): HaEntity? {
    val ordered = (preferred.filter(::isHaWeather) + entities.keys.filter(::isHaWeather).sorted()).distinct()
        .mapNotNull(entities::get)
    return ordered.firstOrNull { it.state !in setOf("unknown", "unavailable", "") } ?: ordered.firstOrNull()
}

/** Screensaver content is hosted directly by Android's DreamService window. */
@Composable
internal fun ArtworkScreensaverContent(
    frame: ArtworkFrame?, weather: HaEntity?, players: List<ScreensaverMediaPlayer>,
    connection: HaConnection, video: BackgroundVideoController, dimPercent: Int, caption: ArtworkLabel? = null,
) {
    val motion = remember { ScreensaverBubbleMotion() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArtworkBackground(frame, dimPercent, screensaver = true, draw = { !video.hasFrame })
        VideoBackground(video, dimPercent, screensaver = true)
        if (!video.hasFrame && caption != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ArtworkCaption(caption, Modifier.align(Alignment.TopEnd).padding(EdgePadding).width(300.dp).cycleUiColors())
            }
        }
        DriftingClock(weather, players, connection, motion)
    }
}

@Composable
private fun DriftingClock(weather: HaEntity?, players: List<ScreensaverMediaPlayer>, connection: HaConnection,
    motion: ScreensaverBubbleMotion) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val timeFormat = DateFormat.getTimeFormat(context)
    val dateFormat = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMMEEEEd"), locale) }
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface.copy(alpha = 0.78f)
    val secondary = colors.onSurfaceVariant.copy(alpha = 0.82f)
    @Composable fun ClockWeather() {
        Column(Modifier.fillMaxWidth().cycleUiColors(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(dateFormat.format(now), color = secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(timeFormat.format(now), color = ink, fontSize = 68.sp, lineHeight = 74.sp,
                fontWeight = FontWeight.Light, letterSpacing = (-2).sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.onSurface.copy(alpha = 0.08f)))
            Spacer(Modifier.height(6.dp))
            val available = weather?.takeIf { it.state !in setOf("unknown", "unavailable", "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MdiIcon(weatherIcon(available?.state), size = 42.dp, color = secondary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (available != null) {
                        val temperature = available.temperature?.let { "${it.roundToInt()}${available.temperatureUnit ?: "°"}" }
                        if (temperature != null) Text(temperature, color = ink, fontSize = 28.sp, fontWeight = FontWeight.Light)
                        Text(weatherLabel(available.state) ?: available.reading, color = secondary, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(available.name, color = secondary.copy(alpha = 0.65f), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else Text("Weather unavailable", color = secondary, fontSize = 14.sp)
                }
            }
            if (available != null && connection.status == HaStatus.Connected) {
                ExtendedWeatherForecast(connection.hourly[available.id], connection.daily[available.id],
                    LocalScreensaverForecast.current, compact = true,
                    precipitationUnit = available.attributes.optString("precipitation_unit", "mm"))
            }
        }
    }
    MovingScreensaverBubbles(players, connection, motion) {
        ScreensaverBubble(Modifier.width(IntrinsicSize.Max)) {
            Box(Modifier.padding(horizontal = 26.dp, vertical = 22.dp)) { ClockWeather() }
        }
    }
}

@Composable
private fun ScreensaverBubble(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val colors = MaterialTheme.colorScheme
    Box(modifier.background(cycleUiColor(Color(0xFF080D10).copy(alpha = 0.52f)), shape)
        .border(1.dp, cycleUiColor(colors.onSurface.copy(alpha = 0.07f)), shape)) { content() }
}

/** Fit the entire reading uniformly inside a wide bubble, including tall/long metadata. */
@Composable
private fun ScreensaverPlayerBubble(player: ScreensaverMediaPlayer, connection: HaConnection) {
    ScreensaverBubble(Modifier.fillMaxSize()) {
        androidx.compose.ui.layout.Layout(content = {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp).graphicsLayer { alpha = 0.82f }) {
                MediaPlayerReading(player.id, connection.entities[player.id], player.name, connection.session,
                    compact = false, modifier = Modifier.fillMaxWidth(), showPrimaryActionIcon = false, showProgress = true)
            }
        }) { children, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val nominal = minOf(1f, height.toFloat() / 108.dp.toPx()).coerceAtLeast(0.001f)
            val content = children.single().measure(Constraints.fixedWidth((width / nominal).roundToInt().coerceAtLeast(1)))
            val scale = minOf(1f, width.toFloat() / content.width.coerceAtLeast(1), height.toFloat() / content.height.coerceAtLeast(1))
            layout(width, height) {
                content.placeWithLayer(((width - content.width * scale) / 2).roundToInt(),
                    ((height - content.height * scale) / 2).roundToInt()) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale; scaleY = scale
                }
            }
        }
    }
}

/** One frame clock drives only layer translations; layout/metadata do not run at frame cadence. */
@Composable
private fun MovingScreensaverBubbles(
    players: List<ScreensaverMediaPlayer>, connection: HaConnection, motion: ScreensaverBubbleMotion, clock: @Composable () -> Unit,
) {
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(motion) {
        var previous = 0L
        while (true) withFrameNanos { now ->
            if (previous != 0L) motion.step((now - previous) / 1_000_000_000.0)
            previous = now
            frame.longValue = now
        }
    }
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val clockWidth = minOf(350.dp.roundToPx(), constraints.maxWidth).coerceAtLeast(1)
        val clockBubble = subcompose("clock", clock).single()
            .measure(Constraints(maxWidth = clockWidth, maxHeight = Constraints.Infinity))
        val fit = screensaverBubbleFit(constraints.maxWidth, constraints.maxHeight,
            BubbleSize(clockBubble.width.toFloat(), clockBubble.height.toFloat()), players.size, density)
        val media = players.map { player ->
            subcompose(player.id) { ScreensaverPlayerBubble(player, connection) }.single()
                .measure(Constraints.fixed(fit.mediaWidth, fit.mediaHeight))
        }
        val sizes = listOf(BubbleSize(clockBubble.width * fit.clockScale, clockBubble.height * fit.clockScale)) +
            media.map { BubbleSize(it.width.toFloat(), it.height.toFloat()) }
        motion.configure(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), sizes, density,
            listOf("clock") + players.map { it.id })
        layout(constraints.maxWidth, constraints.maxHeight) {
            (listOf(clockBubble) + media).forEachIndexed { index, bubble ->
                bubble.placeWithLayer(0, 0) {
                    frame.longValue // Subscribe this drawing layer, not composition or measurement.
                    val body = motion.bodies[index]
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = if (index == 0) fit.clockScale else 1f
                    scaleY = scaleX
                    translationX = body.x; translationY = body.y
                }
            }
        }
    }
}
