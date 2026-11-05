package com.evofactory.homeboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class StreamDescriptionTest {
    private val session = "v=0\r\no=- 1 1 IN IP4 10.0.0.2\r\ns=Stream\r\nt=0 0\r\n"
    private val video = "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0IAH5WoFAFuQA==,aM48gA==\r\na=control:trackID=0\r\n"
    private val bareAudio = "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000/1\r\na=control:trackID=1\r\n"

    @Test
    fun tracksAndTheirFormats() {
        assertEquals(
            listOf(SdpTrack("video", "H264", described = true), SdpTrack("audio", "MPEG4-GENERIC", described = false)),
            sdpTracks(session + video + bareAudio),
        )
    }

    @Test
    fun h264WithoutParameterSets() {
        val noSprop = "m=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000\na=fmtp:96 packetization-mode=1\n"
        assertEquals(listOf(SdpTrack("video", "H264", described = false)), sdpTracks("v=0\n$noSprop"))
    }

    @Test
    fun h265NeedsAllThree() {
        val h265 = "m=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=fmtp:98 sprop-vps=QAE;sprop-sps=QgE\r\n"
        assertEquals(listOf(SdpTrack("video", "H265", described = false)), sdpTracks(session + h265))
    }

    @Test
    fun staticPayloadNeedsNothing() {
        assertEquals(listOf(SdpTrack("audio", "type 0", described = true)), sdpTracks(session + "m=audio 0 RTP/AVP 0\r\n"))
    }

    @Test
    fun audioTakenOut() {
        assertEquals(session + video, sdpWithoutAudio(session + bareAudio + video))
    }

    @Test
    fun filterRewritesTheDescriptionOnly() {
        val sdp = session + video + bareAudio
        val options = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: DESCRIBE, SETUP, PLAY\r\n\r\n"
        val describe = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp"
        val media = byteArrayOf('$'.code.toByte(), 0, 0, 2, 1, 2)
        val stream = RtspFilterStream(ByteArrayInputStream((options + describe).toByteArray() + media), ::sdpWithoutAudio)
        val trimmed = session + video
        assertEquals(
            options + "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${trimmed.length}\r\n\r\n$trimmed",
            String(stream.readNBytes(options.length + describe.length - bareAudio.length)),
        )
        assertEquals(media.toList(), stream.readAllBytes().toList())
    }

    // RTP header: version 2, padding bit as given, payload type 104 with the marker
    private fun rtp(payload: List<Int>, padding: Int = 0): List<Int> {
        val header = listOf(if (padding > 0) 0xA0 else 0x80, 0x80 or 104, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3)
        return header + payload + List(padding) { if (it == padding - 1) padding else 0 }
    }

    private fun interleaved(channel: Int, packet: List<Int>) = listOf('$'.code, channel, packet.size shr 8, packet.size and 0xff) + packet

    private fun bytes(values: List<Int>) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun rtpPaddingTakenOut() {
        val aac = listOf(0x00, 0x10, 0x00, 0x18, 0x01, 0x1A, 0x15)
        val answer = "RTSP/1.0 200 OK\r\nCSeq: 5\r\n\r\n".map { it.code }
        val stream = RtspFilterStream(ByteArrayInputStream(bytes(interleaved(2, rtp(aac, padding = 3)) + answer)), { it })
        val expected = interleaved(2, rtp(aac)) + answer
        assertEquals(expected.map { it.toByte() }, stream.readAllBytes().toList())
    }

    @Test
    fun unpaddedAndRtcpUnchanged() {
        val plain = interleaved(0, rtp(listOf(0x40, 0x01, 0x0C)))
        // RTCP sender report with a padding bit: RTCP keeps it
        val rtcp = interleaved(1, listOf(0xA0, 200, 0, 6) + List(20) { 0 } + listOf(0, 0, 0, 4))
        val stream = RtspFilterStream(ByteArrayInputStream(bytes(plain + rtcp)), { it })
        assertEquals((plain + rtcp).map { it.toByte() }, stream.readAllBytes().toList())
    }

    @Test
    fun paddingNeverEatsTheHeader() {
        val packet = rtp(listOf(1, 2)).toMutableList().apply { this[0] = 0xA0; this[lastIndex] = 40 }
        assertEquals(0, rtpPadding(bytes(packet), 0, packet.size))
    }
}
