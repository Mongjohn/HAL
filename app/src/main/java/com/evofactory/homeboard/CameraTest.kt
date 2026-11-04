package com.evofactory.homeboard

import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.OptIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

private const val TAG = "CameraTest"
private const val CONNECT_TIMEOUT_MS = 3_000
private const val ANSWER_TIMEOUT_MS = 5_000
private const val SAMPLE_MS = 4_000L
private const val SAMPLE_READ_TIMEOUT_MS = 500

private val GoodColor = Color(0xFF81C995)
private val WarnColor = Color(0xFFFDD663)

internal enum class Mark { Info, Good, Warn, Bad }

internal class ReportLine(val label: String, val value: String, val mark: Mark, val indent: Boolean)

/** What one stream's test found so far, line by line */
internal class StreamTestReport(val title: String) {
    val lines = mutableStateListOf<ReportLine>()
    var running by mutableStateOf(false)
}

/**
 * Tests the streams as entered, saved or not: talks RTSP to each camera itself, without a player,
 * so it never takes one of the TV's decoders. Up and down scroll the report; "Test again" reruns it.
 */
@Composable
fun CameraTestDialog(name: String, url: String, substreamUrl: String, useAudio: Boolean, useSubstreamAudio: Boolean, onDismiss: () -> Unit) {
    val reports = remember {
        listOfNotNull(StreamTestReport("Main Stream"), StreamTestReport("Sub Stream").takeIf { substreamUrl.isNotBlank() })
    }
    var run by remember { mutableIntStateOf(0) }
    val running = reports.any { it.running }
    LaunchedEffect(run) {
        reports.forEach { it.lines.clear() }
        reports.forEachIndexed { index, report ->
            report.running = true
            try {
                if (index == 0) testStream(url, isMain = true, useAudio, report) else testStream(substreamUrl, isMain = false, useSubstreamAudio, report)
            } finally {
                report.running = false
            }
            // Without the camera's address, which the log never has
            AppLog.i(
                TAG,
                "${report.title}:\n" + report.lines.joinToString("\n") {
                    "  ${if (it.indent) "  " else ""}${it.label}: ${it.value.replace(Regex("""rtsps?://\S+"""), "(address)")}"
                },
            )
        }
    }
    val reportFocus = remember { FocusRequester() }
    TopDialog(
        title = "Test ${name.ifBlank { "camera" }}",
        onDismiss = onDismiss,
        fullWidth = true,
        atTop = true,
        buttons = listOf(DialogButton("Test again", enabled = !running) { run++ }),
        contentFocus = { reportFocus },
        hint = "Up and down scroll the report",
    ) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val height = LocalConfiguration.current.screenHeightDp.dp - 150.dp
        Box(
            modifier = Modifier
                .heightIn(max = height)
                .focusRequester(reportFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (event.key) {
                        Key.DirectionDown -> if (scroll.value < scroll.maxValue) 240f else return@onPreviewKeyEvent false
                        Key.DirectionUp -> if (scroll.value > 0) -240f else return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                    scope.launch { scroll.animateScrollBy(step) }
                    true
                }
                .ringToTop()
                .focusable(),
        ) {
            Column(Modifier.verticalScroll(scroll).padding(horizontal = 12.dp)) {
                reports.forEach { report ->
                    Text(
                        report.title + if (report.running) " · testing…" else "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    if (report.lines.isEmpty() && !report.running) Text("Waiting…", style = MaterialTheme.typography.bodySmall)
                    report.lines.forEach { ReportRow(it) }
                }
            }
        }
        LaunchedEffect(Unit) { reportFocus.requestFocus() }
    }
}

@Composable
private fun ReportRow(line: ReportLine) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = if (line.indent) 24.dp else 0.dp, top = 2.dp, bottom = 2.dp)) {
        Text(
            line.label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(0.28f),
        )
        Text(
            line.value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = when (line.mark) {
                Mark.Info -> colors.onSurface
                Mark.Good -> GoodColor
                Mark.Warn -> WarnColor
                Mark.Bad -> ErrorColor
            },
            modifier = Modifier.weight(0.72f),
        )
    }
}

// One track as the camera describes it
private class TestTrack(val number: Int, val media: String, val payload: String, val encoding: String, val clockRate: Int, val channels: Int, val format: String, val control: String?) {
    val label get() = "Track $number"
    val parameters: Map<String, String> = format.split(';').mapNotNull { part ->
        part.trim().takeIf { it.isNotEmpty() }?.let { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
    }.toMap()
}

private fun parseTracks(sdp: String): List<TestTrack> = sdpSections(sdp).drop(1).mapIndexed { index, lines ->
    val words = lines.first().trim().split(' ')
    val media = words.first().removePrefix("m=")
    val payload = words.getOrNull(3).orEmpty()
    val rtpmap = lines.firstNotNullOfOrNull { attributeValue(it, "rtpmap", payload) }
    val format = lines.firstNotNullOfOrNull { attributeValue(it, "fmtp", payload) }.orEmpty()
    val control = lines.firstOrNull { it.startsWith("a=control:") }?.substringAfter("a=control:")?.trim()
    val (encoding, clock, channels) = rtpmap?.split('/')?.let {
        Triple(it[0].trim().uppercase(), it.getOrNull(1)?.trim()?.toIntOrNull() ?: 0, it.getOrNull(2)?.trim()?.toIntOrNull() ?: 1)
    } ?: when (payload) {
        // Static payload types, which need no rtpmap
        "0" -> Triple("PCMU", 8000, 1)
        "8" -> Triple("PCMA", 8000, 1)
        "26" -> Triple("JPEG", 90000, 1)
        else -> Triple("type $payload", 0, 1)
    }
    TestTrack(index + 1, media, payload, encoding, clock, channels, format, control)
}

// The encodings Media3's RTSP player takes
private val Media3Encodings = setOf(
    "H264", "H265", "MP4V-ES", "H263-1998", "H263-2000", "VP8", "VP9",
    "MPEG4-GENERIC", "MP4A-LATM", "PCMU", "PCMA", "L8", "L16", "OPUS", "AC3", "AMR", "AMR-WB",
)

private fun mimeOf(encoding: String) = when (encoding) {
    "H264" -> "video/avc"
    "H265" -> "video/hevc"
    "MP4V-ES" -> "video/mp4v-es"
    "VP8" -> "video/x-vnd.on2.vp8"
    "VP9" -> "video/x-vnd.on2.vp9"
    "MPEG4-GENERIC", "MP4A-LATM" -> "audio/mp4a-latm"
    "PCMU" -> "audio/g711-mlaw"
    "PCMA" -> "audio/g711-alaw"
    "OPUS" -> "audio/opus"
    "AC3" -> "audio/ac3"
    else -> null
}

// Reports through the main thread, which owns the report's state; masks any login a value carries
private class Reporter(private val report: StreamTestReport) {
    suspend fun line(label: String, value: String, mark: Mark = Mark.Info, indent: Boolean = false) = withContext(Dispatchers.Main) {
        report.lines += ReportLine(label, maskSecrets(value), mark, indent)
    }
}

private fun maskSecrets(text: String) = text
    .replace(Regex("""(rtsps?://)[^/@\s]*@"""), "$1***@")
    .replace(Regex("""(?i)\b(user(name)?|pass(word)?|pwd|token|auth|key)=[^&\s;]*"""), "$1=***")

@OptIn(UnstableApi::class)
internal suspend fun testStream(url: String, isMain: Boolean, useAudio: Boolean, report: StreamTestReport) = withContext(Dispatchers.IO) {
    val r = Reporter(report)
    val uri = Uri.parse(url.trim())
    val host = uri.host.orEmpty()
    val port = if (uri.port > 0) uri.port else 554
    val bareUrl = uri.buildUpon().encodedAuthority(if (uri.port > 0) "$host:$port" else host).build().toString()
    r.line("Address", bareUrl)
    val userInfo = uri.userInfo
    val user = userInfo?.substringBefore(':')
    val password = userInfo?.substringAfter(':', "")
    r.line(
        "Login",
        when {
            user == null -> "none in the address"
            password.isNullOrEmpty() -> "user name in the address, no password"
            else -> "user name and password in the address"
        },
    )
    if (uri.scheme.equals("rtsps", ignoreCase = true)) {
        r.line("TLS", "rtsps:// isn't tested here", Mark.Warn)
        return@withContext
    }

    // Name lookup
    var started = SystemClock.elapsedRealtime()
    val addresses = try {
        InetAddress.getAllByName(host)
    } catch (e: UnknownHostException) {
        r.line("Name lookup", "$host: no such name on this network", Mark.Bad)
        return@withContext
    }
    val literal = host.all { it.isDigit() || it == '.' } || host.contains(':')
    r.line(
        "Name lookup",
        if (literal) "not needed, an IP address" else addresses.joinToString { it.hostAddress.orEmpty() } + " · ${SystemClock.elapsedRealtime() - started} ms",
    )
    val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()

    // Connection
    val socket = Socket()
    coroutineContext[Job]?.invokeOnCompletion { runCatching { socket.close() } }
    started = SystemClock.elapsedRealtime()
    try {
        socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
    } catch (e: Exception) {
        r.line(
            "Connect",
            when (e) {
                is SocketTimeoutException -> "no answer on TCP port $port within ${CONNECT_TIMEOUT_MS / 1000} s"
                is ConnectException -> "TCP port $port refused: nothing listens there"
                is NoRouteToHostException -> "no route to ${address.hostAddress}"
                else -> e.toString()
            },
            Mark.Bad,
        )
        return@withContext
    }
    r.line("Connect", "TCP port $port in ${SystemClock.elapsedRealtime() - started} ms, from ${socket.localAddress.hostAddress}", Mark.Good)
    socket.soTimeout = ANSWER_TIMEOUT_MS
    val rtsp = RtspTalk(socket, user?.let(Uri::decode), password?.let(Uri::decode))

    try {
        // What it offers
        val options = rtsp.request("OPTIONS", bareUrl)
        r.line("OPTIONS", options.summary, if (options.code == 200) Mark.Good else Mark.Warn)
        (options.header("server") ?: options.header("user-agent"))?.let { r.line("Server", it) }
        options.header("public")?.let { r.line("Requests", it.replace(Regex("""\s*,\s*"""), " ")) }

        // Its description of the stream
        val describe = rtsp.request("DESCRIBE", bareUrl, mapOf("Accept" to "application/sdp"))
        rtsp.challenge?.let { r.line("Login asked", it) }
        if (describe.code != 200) {
            r.line("DESCRIBE", describe.summary + explainCode(describe.code, user != null), Mark.Bad)
            return@withContext
        }
        r.line("DESCRIBE", describe.summary + (rtsp.challenge?.let { ", logged in" } ?: ""), Mark.Good)
        val sdp = String(describe.body, Charsets.UTF_8)
        val base = describe.header("content-base") ?: describe.header("content-location") ?: bareUrl
        r.line("Description", "${describe.body.size} bytes · ${describe.header("content-type") ?: "no type"}")
        describe.header("content-base")?.let { r.line("Content-Base", it) }
        sdp.lineSequence().firstOrNull { it.startsWith("s=") }?.substring(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { r.line("Session name", it) }
        sdp.lineSequence().firstOrNull { it.startsWith("a=tool:") }?.substringAfter(':')?.trim()?.let { r.line("Made by", it) }
        val tracks = parseTracks(sdp)
        val described = sdpTracks(sdp)
        r.line("Tracks", if (tracks.isEmpty()) "none" else tracks.joinToString { "${it.media} ${it.encoding}" }, if (tracks.none { it.media == "video" }) Mark.Bad else Mark.Info)

        tracks.forEachIndexed { index, track -> reportTrack(r, track, described.getOrNull(index)?.described ?: true, isMain, useAudio) }

        // A few seconds of the stream itself
        sample(r, rtsp, tracks, base)
    } catch (e: SocketTimeoutException) {
        r.line("Stopped", "the camera stopped answering for ${ANSWER_TIMEOUT_MS / 1000} s", Mark.Bad)
    } catch (e: EOFException) {
        r.line("Stopped", "the camera closed the connection", Mark.Bad)
    } catch (e: Exception) {
        if (coroutineContext[Job]?.isActive == false) throw e
        r.line("Stopped", e.toString(), Mark.Bad)
    } finally {
        runCatching { socket.close() }
    }
}

private fun explainCode(code: Int, hasLogin: Boolean) = when (code) {
    401 -> if (hasLogin) ": the camera refused this login" else ": the camera wants a login, put it in the address"
    403 -> ": access to this stream is refused"
    404 -> ": no stream at this path"
    else -> ""
}

@OptIn(UnstableApi::class)
private suspend fun reportTrack(r: Reporter, track: TestTrack, described: Boolean, isMain: Boolean, useAudio: Boolean) {
    r.line(track.label, "${track.media} ${track.encoding}" + (if (track.clockRate > 0) "/${track.clockRate}" else "") + (if (track.channels > 1) "/${track.channels}" else ""))
    r.line("Payload", "type ${track.payload}" + (track.control?.let { " · control $it" } ?: ""), indent = true)
    if (track.format.isNotBlank()) r.line("Format", track.parameters.entries.joinToString(" ") { (k, v) -> if (v.length > 24) "$k=(${v.length} chars)" else "$k=$v" }, indent = true)
    var width = 0
    var height = 0
    when (track.encoding) {
        "H264" -> {
            track.parameters["profile-level-id"]?.let { r.line("Profile id", "${it.uppercase()}: ${h264ProfileLevel(it)}", indent = true) }
            track.parameters["packetization-mode"]?.let { r.line("Packetization", "mode $it" + if (it == "0") " (one NAL unit per packet)" else " (fragmented and aggregated NAL units)", indent = true) }
            val sets = track.parameters["sprop-parameter-sets"]?.split(',')?.mapNotNull { decodeBase64(it) }.orEmpty()
            sets.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1F == 7 }?.let { sps ->
                runCatching { NalUnitUtil.parseSpsNalUnit(sps, 0, sps.size) }.getOrNull()?.let {
                    width = it.width
                    height = it.height
                    r.line("SPS", h264SpsSummary(it, sps.size), indent = true)
                }
            }
            sets.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1F == 8 }?.let { r.line("PPS", "${it.size} bytes", indent = true) }
        }
        "H265" -> {
            listOf("sprop-vps", "sprop-pps").forEach { key ->
                track.parameters[key]?.let { decodeBase64(it) }?.let { r.line(key.removePrefix("sprop-").uppercase(), "${it.size} bytes", indent = true) }
            }
            track.parameters["sprop-sps"]?.let { decodeBase64(it) }?.let { sps ->
                runCatching { NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null) }.getOrNull()?.let {
                    width = it.width
                    height = it.height
                    r.line("SPS", h265SpsSummary(it, sps.size), indent = true)
                }
            }
        }
        "MPEG4-GENERIC", "MP4A-LATM" -> track.parameters["config"]?.let { r.line("AAC config", "${it.uppercase()}: ${aacConfig(it, track.encoding == "MP4A-LATM")}", indent = true) }
    }
    when {
        track.encoding !in Media3Encodings -> r.line("Media3", "can't play ${track.encoding}" + if (track.media == "video") ": no picture from this stream" else "", if (track.media == "video") Mark.Bad else Mark.Warn, indent = true)
        !described -> r.line(
            "Media3",
            "format details missing" + when (track.encoding) {
                "H264" -> " (sprop-parameter-sets)"
                "H265" -> " (sprop-vps/sps/pps)"
                else -> " (a=fmtp)"
            } + ": Media3 refuses the whole stream" + when {
                track.media != "audio" -> ""
                useAudio -> ". Turn off \"Use audio track\""
                else -> ", but the app takes this track out"
            },
            if (track.media == "audio" && !useAudio) Mark.Warn else Mark.Bad,
            indent = true,
        )
        else -> r.line("Media3", "format described, playable", Mark.Good, indent = true)
    }
    mimeOf(track.encoding)?.let { mime -> decoderLine(mime, width, height) }?.let { (text, mark) -> r.line("Decoder here", text, mark, indent = true) }
    when (track.media) {
        "video" -> r.line("In the app", if (isMain) "Home tab and full screen" else "Cameras tab tiles", indent = true)
        "audio" -> r.line(
            "In the app",
            when {
                !useAudio -> "taken out: \"Use audio track\" is off"
                isMain -> "plays muted on the Home tab, heard in full screen"
                else -> "not played: substreams have no sound"
            },
            indent = true,
        )
        else -> r.line("In the app", "not played", indent = true)
    }
}

private fun decodeBase64(text: String) = runCatching { Base64.decode(text.trim(), Base64.DEFAULT) }.getOrNull()

// constraint_set1 makes Baseline "Constrained", as most cameras' substreams are
private fun h264ProfileName(idc: Int, constraints: Int = 0) = when (idc) {
    66 -> if (constraints and 0x40 != 0) "Constrained Baseline" else "Baseline"
    77 -> "Main"
    88 -> "Extended"
    100 -> "High"
    110 -> "High 10"
    122 -> "High 4:2:2"
    244 -> "High 4:4:4"
    else -> "profile $idc"
}

private fun h264ProfileLevel(id: String): String {
    val value = id.toIntOrNull(16) ?: return "unreadable"
    val level = value and 0xFF
    return "${h264ProfileName(value shr 16, (value shr 8) and 0xFF)}, level ${level / 10}.${level % 10}"
}

@OptIn(UnstableApi::class)
private fun h264SpsSummary(sps: NalUnitUtil.SpsData, size: Int) =
    "${sps.width}×${sps.height} · ${h264ProfileName(sps.profileIdc, sps.constraintsFlagsAndReservedZero2Bits)} ${sps.levelIdc / 10}.${sps.levelIdc % 10} · " +
        "${8 + sps.bitDepthLumaMinus8}-bit · ${if (sps.frameMbsOnlyFlag) "progressive" else "interlaced"} · " +
        "${sps.maxNumRefFrames} ref frames · $size bytes"

@OptIn(UnstableApi::class)
private fun h265SpsSummary(sps: NalUnitUtil.H265SpsData, size: Int): String {
    val ptl = sps.profileTierLevel ?: return "${sps.width}×${sps.height} · ${8 + sps.bitDepthLumaMinus8}-bit · $size bytes"
    val profile = when (ptl.generalProfileIdc) {
        1 -> "Main"
        2 -> "Main 10"
        3 -> "Main Still Picture"
        4 -> "Range extensions"
        else -> "profile ${ptl.generalProfileIdc}"
    }
    val chroma = when (sps.chromaFormatIdc) {
        0 -> "4:0:0"
        1 -> "4:2:0"
        2 -> "4:2:2"
        else -> "4:4:4"
    }
    return "${sps.width}×${sps.height} · $profile, level ${"%.1f".format(ptl.generalLevelIdc / 30.0)} " +
        "${if (ptl.generalTierFlag) "High" else "Main"} tier · ${8 + sps.bitDepthLumaMinus8}-bit $chroma · $size bytes"
}

private val AacRates = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

// AudioSpecificConfig; LATM's StreamMuxConfig carries it 15 bits in
private fun aacConfig(hex: String, latm: Boolean): String {
    val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
    val bits = bytes.joinToString("") { it.toString(2).padStart(8, '0') }
    val start = if (latm) 15 else 0
    if (bits.length < start + 13) return "unreadable"
    val type = bits.substring(start, start + 5).toInt(2)
    val rate = bits.substring(start + 5, start + 9).toInt(2)
    val channels = bits.substring(start + 9, start + 13).toInt(2)
    val name = when (type) {
        1 -> "AAC Main"
        2 -> "AAC-LC"
        4 -> "AAC LTP"
        5 -> "HE-AAC (SBR)"
        29 -> "HE-AAC v2 (PS)"
        39 -> "AAC-ELD"
        else -> "object type $type"
    }
    val hz = AacRates.getOrNull(rate)?.let { "${it / 1000.0} kHz".replace(".0 ", " ") } ?: "rate index $rate"
    val layout = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        else -> "$channels channels"
    }
    return "$name · $hz · $layout"
}

// The decoder players pick first for this type, and whether it takes this size
private fun decoderLine(mime: String, width: Int, height: Int): Pair<String, Mark> {
    val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { !it.isEncoder && mime in it.supportedTypes }
        ?: return "none for $mime on this TV" to Mark.Bad
    val caps = info.getCapabilitiesForType(mime)
    val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
    val size = caps.videoCapabilities?.takeIf { width > 0 && height > 0 }?.let { video ->
        if (video.isSizeSupported(width, height)) " · ${width}×$height supported" else " · ${width}×$height NOT supported"
    }.orEmpty()
    return "${info.name} · ${if (hardware) "hardware" else "software"} · up to ${caps.maxSupportedInstances} at once$size" to
        if (size.contains("NOT")) Mark.Bad else Mark.Info
}

// What a few seconds of one track brought
private class TrackSample(val track: TestTrack) {
    var packets = 0
    var bytes = 0L
    var lost = 0
    private var lastSeq = -1
    private var lastRawTs = 0L
    private var ts = 0L
    var firstTs = -1L
    var lastTs = -1L
    var frames = 0
    var firstAt = 0L
    var lastAt = 0L
    val nalTypes = mutableSetOf<Int>()
    val keyframes = mutableListOf<Long>()
    var inBandSps: ByteArray? = null

    fun add(packet: ByteArray, now: Long) {
        if (packet.size < 12 || packet[0].toInt() and 0xC0 != 0x80) return
        val first = packets == 0
        packets += 1
        if (first) firstAt = now
        lastAt = now
        val seq = u16(packet, 2)
        if (lastSeq >= 0) {
            val gap = (seq - lastSeq - 1) and 0xFFFF
            if (gap in 1..1000) lost += gap
        }
        lastSeq = seq
        val raw = u32(packet, 4)
        ts = if (first) 0 else ts + (raw - lastRawTs).toInt()
        lastRawTs = raw
        if (first) firstTs = ts
        if (ts != lastTs) frames += 1
        lastTs = ts
        var offset = 12 + (packet[0].toInt() and 0x0F) * 4
        if (packet[0].toInt() and 0x10 != 0 && packet.size >= offset + 4) offset += 4 + u16(packet, offset + 2) * 4
        val end = packet.size - if (packet[0].toInt() and 0x20 != 0) packet.last().toInt() and 0xFF else 0
        if (offset >= end) return
        bytes += end - offset
        val payload = packet.copyOfRange(offset, end)
        when (track.encoding) {
            "H264" -> h264Units(payload)
            "H265" -> h265Units(payload)
            else -> emptyList()
        }.forEach { (type, unit) ->
            nalTypes += type
            val key = if (track.encoding == "H264") type == 5 else type in 16..21
            if (key && keyframes.lastOrNull() != ts) keyframes += ts
            val sps = if (track.encoding == "H264") type == 7 else type == 33
            if (sps && unit != null && inBandSps == null) inBandSps = unit
        }
    }

    // (type, the whole unit when it's all in this packet)
    private fun h264Units(p: ByteArray): List<Pair<Int, ByteArray?>> = when (val type = p[0].toInt() and 0x1F) {
        24 -> aggregated(p, 1).map { (it[0].toInt() and 0x1F) to it }
        28 -> if (p.size > 1 && p[1].toInt() and 0x80 != 0) listOf((p[1].toInt() and 0x1F) to null) else emptyList()
        else -> listOf(type to p)
    }

    private fun h265Units(p: ByteArray): List<Pair<Int, ByteArray?>> {
        if (p.size < 2) return emptyList()
        return when (val type = (p[0].toInt() shr 1) and 0x3F) {
            48 -> aggregated(p, 2).map { ((it[0].toInt() shr 1) and 0x3F) to it }
            49 -> if (p.size > 2 && p[2].toInt() and 0x80 != 0) listOf((p[2].toInt() and 0x3F) to null) else emptyList()
            else -> listOf(type to p)
        }
    }

    // Units packed one after another, each after its 16-bit size
    private fun aggregated(p: ByteArray, start: Int): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var i = start
        while (i + 2 < p.size) {
            val size = u16(p, i)
            if (size == 0 || i + 2 + size > p.size) break
            units += p.copyOfRange(i + 2, i + 2 + size)
            i += 2 + size
        }
        return units
    }
}

private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

@OptIn(UnstableApi::class)
private suspend fun sample(r: Reporter, rtsp: RtspTalk, tracks: List<TestTrack>, base: String) {
    val samples = mutableMapOf<Int, TrackSample>()
    for (track in tracks.filter { it.media == "video" || it.media == "audio" }) {
        val control = track.control
        val trackUrl = when {
            control == null || control == "*" -> base
            control.startsWith("rtsp://", ignoreCase = true) || control.startsWith("rtsps://", ignoreCase = true) -> control
            else -> base.trimEnd('/') + "/" + control
        }
        val channel = samples.size * 2
        val setup = rtsp.request("SETUP", trackUrl, mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"))
        if (setup.code != 200) {
            r.line("SETUP ${track.label}", setup.summary + if (setup.code == 461) ": refuses RTP over TCP, the sample needs it" else "", Mark.Warn)
            continue
        }
        setup.header("session")?.let { rtsp.session = it.substringBefore(';').trim() }
        val transport = setup.header("transport").orEmpty()
        val actual = Regex("""interleaved=(\d+)""").find(transport)?.groupValues?.get(1)?.toIntOrNull() ?: channel
        val timeout = setup.header("session")?.let { Regex("""timeout=(\d+)""").find(it)?.groupValues?.get(1) }
        r.line("SETUP ${track.label}", setup.summary + " · channels $actual-${actual + 1}" + (timeout?.let { " · session timeout $it s" } ?: ""), Mark.Good)
        samples[actual] = TrackSample(track)
    }
    if (samples.isEmpty()) return
    val play = rtsp.request("PLAY", base, mapOf("Range" to "npt=0.000-"))
    if (play.code != 200) {
        r.line("PLAY", play.summary, Mark.Bad)
        return
    }
    r.line("PLAY", play.summary + (play.header("range")?.let { " · $it" } ?: ""), Mark.Good)
    val playedAt = SystemClock.elapsedRealtime()
    rtsp.sampling = true
    while (SystemClock.elapsedRealtime() - playedAt < SAMPLE_MS) {
        val frame = try {
            rtsp.readInterleaved()
        } catch (e: SocketTimeoutException) {
            null
        } ?: continue
        samples[frame.first]?.add(frame.second, SystemClock.elapsedRealtime())
    }
    rtsp.sampling = false
    runCatching { rtsp.request("TEARDOWN", base) }

    r.line("Sample", "${SAMPLE_MS / 1000} s over RTSP's TCP connection")
    samples.values.forEach { s ->
        val track = s.track
        val label = "${track.label} ${track.media}"
        if (s.packets == 0) {
            r.line(
                label,
                "no packets in ${SAMPLE_MS / 1000} s" + if (track.media == "audio") ": the camera describes audio but sends none" else "",
                Mark.Bad,
            )
            return@forEach
        }
        val seconds = (s.lastAt - s.firstAt).coerceAtLeast(1) / 1000.0
        val rate = s.bytes * 8 / seconds
        val bitrate = if (rate >= 1_000_000) "%.2f Mbit/s".format(rate / 1_000_000) else "%.0f kbit/s".format(rate / 1000)
        r.line(label, "${s.packets} packets · $bitrate · first after ${s.firstAt - playedAt} ms", Mark.Good)
        if (s.lost > 0) r.line("Lost", "${s.lost} packets (sequence gaps)", Mark.Warn, indent = true)
        if (track.media != "video" || track.clockRate <= 0) return@forEach
        val span = (s.lastTs - s.firstTs).toDouble() / track.clockRate
        if (s.frames > 1 && span > 0) r.line("Frame rate", "%.1f fps (%d frames in %.2f s of stream time)".format((s.frames - 1) / span, s.frames, span), indent = true)
        r.line(
            "Keyframes",
            when (s.keyframes.size) {
                0 -> "none in ${SAMPLE_MS / 1000} s: a picture can take that long to start"
                1 -> "1 in ${SAMPLE_MS / 1000} s: further apart than the sample"
                else -> "every %.2f s".format((s.keyframes.last() - s.keyframes.first()).toDouble() / track.clockRate / (s.keyframes.size - 1))
            },
            if (s.keyframes.isEmpty()) Mark.Warn else Mark.Info,
            indent = true,
        )
        val sets = if (track.encoding == "H264") listOf(7 to "SPS", 8 to "PPS") else listOf(32 to "VPS", 33 to "SPS", 34 to "PPS")
        val inStream = sets.filter { it.first in s.nalTypes }.map { it.second }
        r.line("In the stream", if (inStream.isEmpty()) "no parameter sets seen" else inStream.joinToString(" ") + " seen", indent = true)
        if (!track.format.contains("sprop") && inStream.isNotEmpty()) {
            r.line("Note", "the camera sends its parameter sets in the stream only: Media3 needs them in the description", Mark.Warn, indent = true)
        }
        s.inBandSps?.let { sps ->
            val summary = runCatching {
                if (track.encoding == "H264") h264SpsSummary(NalUnitUtil.parseSpsNalUnit(sps, 0, sps.size), sps.size)
                else h265SpsSummary(NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null), sps.size)
            }.getOrNull()
            summary?.let { r.line("SPS in stream", it, indent = true) }
        }
        val names = s.nalTypes.sorted().joinToString(" ")
        r.line("NAL types", names, indent = true)
    }
}

private class RtspAnswer(val code: Int, val status: String, private val headers: Map<String, String>, val body: ByteArray, val ms: Long) {
    fun header(name: String) = headers[name.lowercase()]

    val summary get() = "$status · $ms ms"
}

/** A bare RTSP conversation: requests one at a time, with Basic or Digest login when asked */
private class RtspTalk(socket: Socket, private val user: String?, private val password: String?) {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()
    private val socket = socket
    private var cseq = 0
    private var scheme: String? = null
    private var params: Map<String, String> = emptyMap()
    private var nonceCount = 0
    var session: String? = null

    /** The login the camera asked for, e.g. Digest, realm "IP Camera" */
    var challenge: String? = null

    /** Reading media: short timeouts, so the sample ends on time */
    var sampling = false
        set(value) {
            field = value
            socket.soTimeout = if (value) SAMPLE_READ_TIMEOUT_MS else ANSWER_TIMEOUT_MS
        }

    fun request(method: String, url: String, headers: Map<String, String> = emptyMap()): RtspAnswer {
        val answer = send(method, url, headers)
        if (answer.code != 401 || user == null || scheme != null) return answer
        val offers = answer.header("www-authenticate").orEmpty().split('\n')
        val offer = offers.firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) }
            ?: offers.firstOrNull { it.trim().startsWith("Basic", ignoreCase = true) }
            ?: return answer
        scheme = offer.trim().substringBefore(' ').lowercase()
        params = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]*))""").findAll(offer).associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        challenge = "${scheme!!.replaceFirstChar { it.uppercase() }}" + (params["realm"]?.let { ", realm \"$it\"" } ?: "") +
            (params["qop"]?.let { ", qop $it" } ?: "") + (params["algorithm"]?.let { ", $it" } ?: "")
        return send(method, url, headers)
    }

    private fun send(method: String, url: String, headers: Map<String, String>): RtspAnswer {
        val started = SystemClock.elapsedRealtime()
        cseq += 1
        val text = buildString {
            append("$method $url RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: HomeBoard camera test\r\n")
            authorization(method, url)?.let { append("Authorization: $it\r\n") }
            session?.let { append("Session: $it\r\n") }
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        while (true) {
            // Media still arriving, e.g. before TEARDOWN's answer
            if (peek() == '$'.code) {
                skipInterleaved()
                continue
            }
            val status = readLine()
            if (status.isEmpty()) continue
            val answerHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = readLine()
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                answerHeaders[name] = answerHeaders[name]?.let { "$it\n$value" } ?: value
            }
            val body = readBytes(answerHeaders["content-length"]?.toIntOrNull() ?: 0)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return RtspAnswer(code, status.substringAfter(' ').trim(), answerHeaders, body, SystemClock.elapsedRealtime() - started)
        }
    }

    // RFC 2617, as cameras use it for RTSP
    private fun authorization(method: String, url: String): String? = when (scheme) {
        "basic" -> "Basic " + Base64.encodeToString("$user:${password.orEmpty()}".toByteArray(), Base64.NO_WRAP)
        "digest" -> {
            val realm = params["realm"].orEmpty()
            val nonce = params["nonce"].orEmpty()
            val ha1 = md5("$user:$realm:${password.orEmpty()}")
            val ha2 = md5("$method:$url")
            val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            buildString {
                append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$url\"")
                if (qop != null) {
                    nonceCount += 1
                    val nc = "%08x".format(nonceCount)
                    val cnonce = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
                    append(", response=\"${md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
                } else {
                    append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
                }
                params["opaque"]?.let { append(", opaque=\"$it\"") }
                params["algorithm"]?.let { append(", algorithm=$it") }
            }
        }
        else -> null
    }

    /** One media packet: its channel and bytes; null for anything else, e.g. an RTSP message from the camera */
    fun readInterleaved(): Pair<Int, ByteArray>? {
        if (peek() != '$'.code) {
            // e.g. the camera's own requests or keep-alive answers
            while (readLine().isNotEmpty()) Unit
            return null
        }
        // The rest of the packet is on its way: a short timeout would cut it and lose track of the stream
        socket.soTimeout = ANSWER_TIMEOUT_MS
        try {
            input.read()
            val channel = input.read()
            val length = (input.read() shl 8) or input.read()
            return channel to readBytes(length)
        } finally {
            if (sampling) socket.soTimeout = SAMPLE_READ_TIMEOUT_MS
        }
    }

    private fun skipInterleaved() {
        input.read()
        input.read()
        val length = (input.read() shl 8) or input.read()
        readBytes(length)
    }

    private fun peek(): Int {
        input.mark(1)
        val c = input.read()
        input.reset()
        if (c == -1) throw EOFException()
        return c
    }

    private fun readLine(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) throw EOFException()
            if (c == '\n'.code) break
            if (c != '\r'.code) line.write(c)
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    private fun readBytes(length: Int): ByteArray = ByteArray(length).also { readFully(input, it) }
}

private fun readFully(input: InputStream, buffer: ByteArray) {
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count == -1) throw EOFException()
        read += count
    }
}

private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
