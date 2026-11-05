package com.evofactory.homeboard

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A track as the camera describes it in its stream description (SDP): [media] is "video" or "audio",
 * [encoding] e.g. "H264" or "MPEG4-GENERIC". [described]: the camera gave the format details the
 * player needs before it starts. Without them, Media3 refuses the whole stream ("missing attribute
 * fmtp", "missing sprop parameter").
 */
internal data class SdpTrack(val media: String, val encoding: String, val described: Boolean) {
    override fun toString() = "$media $encoding" + if (described) "" else " without its format"
}

/** The tracks in a stream description, in order */
internal fun sdpTracks(sdp: String): List<SdpTrack> = sdpSections(sdp).drop(1).mapNotNull { lines ->
    val media = lines.first().removePrefix("m=").substringBefore(' ').trim()
    val payload = lines.first().split(' ').getOrNull(3)?.trim() ?: return@mapNotNull null
    val encoding = lines.firstNotNullOfOrNull { attributeValue(it, "rtpmap", payload) }
        ?.substringBefore('/')?.trim()?.uppercase()
        // Static payload types have no rtpmap
        ?: return@mapNotNull SdpTrack(media, "type $payload", described = true)
    val format = lines.firstNotNullOfOrNull { attributeValue(it, "fmtp", payload) }.orEmpty()
    val described = when (encoding) {
        "H264" -> "sprop-parameter-sets" in format
        "H265" -> listOf("sprop-vps", "sprop-sps", "sprop-pps").all { it in format }
        "MPEG4-GENERIC", "MP4A-LATM", "MP4V-ES" -> format.isNotBlank()
        else -> true
    }
    SdpTrack(media, encoding, described)
}

/** The description without its audio tracks, which the player then never sees */
internal fun sdpWithoutAudio(sdp: String): String =
    sdpSections(sdp).filterNot { it.first().startsWith("m=audio") }.joinToString("") { it.joinToString("") }

// The session lines, then one list per track, from its m= line. Each line keeps its line ending.
internal fun sdpSections(sdp: String): List<List<String>> {
    val sections = mutableListOf(mutableListOf<String>())
    Regex("""[^\n]*\n|[^\n]+$""").findAll(sdp).forEach { match ->
        if (match.value.startsWith("m=")) sections += mutableListOf<String>()
        sections.last() += match.value
    }
    return sections
}

// e.g. "H264/90000" from "a=rtpmap:96 H264/90000"
internal fun attributeValue(line: String, name: String, payload: String): String? {
    val prefix = "a=$name:$payload"
    if (!line.startsWith(prefix)) return null
    val rest = line.substring(prefix.length)
    return if (rest.isEmpty() || rest[0].isWhitespace()) rest.trim() else null
}

private const val MAX_HEADERS_BYTES = 64 * 1024

/**
 * Hands the camera's RTSP connection to the player with two fixes. [rewrite] sees, and may change,
 * each stream description (SDP). RTP packets interleaved on the connection (RTP over TCP) lose their
 * padding: Media3 1.11 keeps it as payload, and its AAC reader takes a frame as the last AU-size bytes
 * of the packet, so every padded frame reached the decoder shifted, which rejected it as silence
 * (a camera that pads its audio to 4 bytes, 2026-11-05). Over UDP the player reads the packets
 * itself, so their padding stays.
 */
internal class RtspFilterStream(raw: InputStream, private val rewrite: (String) -> String) : InputStream() {
    private val input = BufferedInputStream(raw)
    private var pending = ByteArray(0)
    private var next = 0
    private var end = 0

    // Lost track of where messages start: everything passes as it comes
    private var passThrough = false

    override fun read(): Int {
        if (next < end) return pending[next++].toInt() and 0xff
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (next == end) {
            if (passThrough) return input.read(b, off, len)
            pending = nextMessage() ?: return -1
            next = 0
            end = pending.size
        }
        val count = minOf(len, end - next)
        System.arraycopy(pending, next, b, off, count)
        next += count
        return count
    }

    override fun available() = end - next + if (passThrough) input.available() else 0

    override fun close() = input.close()

    /** One interleaved packet or one answer, null at the end of the stream */
    private fun nextMessage(): ByteArray? {
        val first = input.read()
        if (first == -1) return null
        return if (first == '$'.code) interleaved() else answer(first)
    }

    /** '$', the channel, the packet's 16-bit length, then the packet, without its RTP padding */
    private fun interleaved(): ByteArray {
        val head = byteArrayOf('$'.code.toByte(), 0, 0, 0)
        val got = readInto(head, 1, 3)
        if (got < 3) return head.copyOf(1 + got)
        val length = ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
        val frame = head.copyOf(4 + length)
        val read = readInto(frame, 4, length)
        if (read < length) return frame.copyOf(4 + read)
        val padding = rtpPadding(frame, 4, length)
        if (padding == 0) return frame
        val kept = length - padding
        frame[2] = (kept shr 8).toByte()
        frame[3] = kept.toByte()
        frame[4] = (frame[4].toInt() and 0xDF).toByte()
        return frame.copyOf(4 + kept)
    }

    /** One answer: its headers, then a body as long as their Content-Length */
    private fun answer(first: Int): ByteArray {
        val head = ByteArrayOutputStream()
        head.write(first)
        var last4 = first
        while (last4 != 0x0D0A0D0A && last4 and 0xFFFF != 0x0A0A) {
            val c = input.read()
            if (c == -1) return head.toByteArray()
            head.write(c)
            // Not an answer after all
            if (head.size() > MAX_HEADERS_BYTES) {
                passThrough = true
                return head.toByteArray()
            }
            last4 = (last4 shl 8) or c
        }
        val headers = head.toString(Charsets.ISO_8859_1.name())
        val length = Regex("""(?im)^content-length:\s*(\d+)""").find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        val read = readInto(body, 0, length)
        if (read < length || !Regex("""(?im)^content-type:\s*application/sdp""").containsMatchIn(headers)) {
            return head.toByteArray() + body.copyOf(read)
        }
        val sdp = rewrite(String(body, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val newHeaders = headers.replace(Regex("""(?im)^(content-length:\s*)\d+"""), "$1${sdp.size}")
        return newHeaders.toByteArray(Charsets.ISO_8859_1) + sdp
    }

    // How many bytes it read, fewer only if the stream ended first
    private fun readInto(buffer: ByteArray, offset: Int, length: Int): Int {
        var read = 0
        while (read < length) {
            val count = input.read(buffer, offset + read, length - read)
            if (count == -1) break
            read += count
        }
        return read
    }
}

/**
 * The padding at the end of the RTP packet at [start], 0 if it has none or isn't RTP. RTCP keeps its
 * own: its packet types (192 to 223) read as RTP payload types 64 to 95 with the marker, which RTP
 * leaves unused for that reason.
 */
internal fun rtpPadding(packet: ByteArray, start: Int, length: Int): Int {
    if (length < 12) return 0
    val first = packet[start].toInt() and 0xff
    if (first and 0xC0 != 0x80 || first and 0x20 == 0) return 0
    if ((packet[start + 1].toInt() and 0xff) in 192..223) return 0
    var header = 12 + (first and 0x0F) * 4
    if (first and 0x10 != 0) {
        if (length < header + 4) return 0
        header += 4 + (((packet[start + header + 2].toInt() and 0xff) shl 8) or (packet[start + header + 3].toInt() and 0xff)) * 4
    }
    val padding = packet[start + length - 1].toInt() and 0xff
    return if (padding in 1..length - header) padding else 0
}
