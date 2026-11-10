package com.faznet.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamProbeGuessTest {

    @Test
    fun rtspHasItsOwnSourceAndRtmpUdpArePlainStreams() {
        assertEquals(StreamType.RTSP, StreamProbe.guess("rtsp://cam.example/live"))
        assertEquals(StreamType.PROGRESSIVE, StreamProbe.guess("rtmp://h.example/live/ch1"))
        assertEquals(StreamType.PROGRESSIVE, StreamProbe.guess("udp://@239.0.0.1:1234"))
    }

    @Test
    fun theUsualOnesStayAsTheyWere() {
        assertEquals(StreamType.HLS, StreamProbe.guess("http://h/live/u/p/1.m3u8"))
        assertEquals(StreamType.PROGRESSIVE, StreamProbe.guess("http://h/live/u/p/1.ts"))
        assertEquals(StreamType.PROGRESSIVE, StreamProbe.guess("http://h/u/p/1"))
    }
}
