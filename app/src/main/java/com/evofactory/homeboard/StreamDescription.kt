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
 * Hands the camera's RTSP answers to the player, after [rewrite] has seen, and may change, the first
 * stream description (SDP) among them. From then on, everything, video included, passes as it comes.
 */
internal class SdpFilterStream(raw: InputStream, private val rewrite: (String) -> String) : InputStream() {
    private val input = BufferedInputStream(raw)
    private var pending = ByteArray(0)
    private var next = 0
    private var passThrough = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (next == pending.size) {
            if (passThrough) return input.read(b, off, len)
            pending = nextAnswer() ?: return -1
            next = 0
        }
        val count = minOf(len, pending.size - next)
        System.arraycopy(pending, next, b, off, count)
        next += count
        return count
    }

    override fun available() = pending.size - next + if (passThrough) input.available() else 0

    override fun close() = input.close()

    /** One answer: its headers, then a body as long as their Content-Length. Null at the end of the stream. */
    private fun nextAnswer(): ByteArray? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val c = input.read()
            if (c == -1) return head.toByteArray().takeIf { it.isNotEmpty() }
            head.write(c)
            // Interleaved media before any description, or something else: nothing to change
            if ((head.size() == 1 && c == '$'.code) || head.size() > MAX_HEADERS_BYTES) {
                passThrough = true
                return head.toByteArray()
            }
            last4 = (last4 shl 8) or c
            if (last4 == 0x0D0A0D0A || last4 and 0xFFFF == 0x0A0A) break
        }
        val headers = head.toString(Charsets.ISO_8859_1.name())
        val length = Regex("""(?im)^content-length:\s*(\d+)""").find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = readBody(length)
        if (!Regex("""(?im)^content-type:\s*application/sdp""").containsMatchIn(headers) || body.size < length) {
            return head.toByteArray() + body
        }
        passThrough = true
        val sdp = rewrite(String(body, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val newHeaders = headers.replace(Regex("""(?im)^(content-length:\s*)\d+"""), "$1${sdp.size}")
        return newHeaders.toByteArray(Charsets.ISO_8859_1) + sdp
    }

    // Shorter only if the stream ended first
    private fun readBody(length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count == -1) return body.copyOf(read)
            read += count
        }
        return body
    }
}
