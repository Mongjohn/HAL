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
        val stream = SdpFilterStream(ByteArrayInputStream((options + describe).toByteArray() + media), ::sdpWithoutAudio)
        val trimmed = session + video
        assertEquals(
            options + "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${trimmed.length}\r\n\r\n$trimmed",
            String(stream.readNBytes(options.length + describe.length - bareAudio.length)),
        )
        assertEquals(media.toList(), stream.readAllBytes().toList())
    }
}
