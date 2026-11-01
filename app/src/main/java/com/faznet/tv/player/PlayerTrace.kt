package com.faznet.tv.player

import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.faznet.tv.Perf

/**
 * Only in a build made with -PplayerTrace: what the first seconds of a channel look like, for hunting freezes.
 * One line every 200 ms with the clock, the frames and the buffer, plus the first video frames with their times.
 */
@OptIn(UnstableApi::class)
class PlayerTrace(private val p: ExoPlayer, private val handler: Handler, private val label: String) :
    AnalyticsListener {

    private val t0 = SystemClock.elapsedRealtime()
    @Volatile private var framesSeen = 0
    @Volatile private var lastPtsUs = -1L
    private var stopped = false

    private fun t() = SystemClock.elapsedRealtime() - t0
    private fun log(s: String) = Perf.log("trace t=${t()} $s")

    private val sampler = object : Runnable {
        override fun run() {
            if (stopped) return
            val v = p.videoDecoderCounters
            v?.ensureUpdated()
            val a = p.audioDecoderCounters
            a?.ensureUpdated()
            log(
                "state=${p.playbackState} pos=${p.currentPosition} buf=${p.totalBufferedDuration} shownPts=${lastPtsUs / 1000} " +
                    "vIn=${v?.queuedInputBufferCount} vOut=${v?.renderedOutputBufferCount} skip=${v?.skippedOutputBufferCount} " +
                    "drop=${v?.droppedBufferCount} inDrop=${v?.droppedInputBufferCount} toKey=${v?.droppedToKeyframeCount} aIn=${a?.queuedInputBufferCount}"
            )
            if (t() < 9_000) handler.postDelayed(this, 200) else stopped = true
        }
    }

    init {
        log("begin $label")
        p.addAnalyticsListener(this)
        handler.postDelayed(sampler, 200)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(sampler)
    }

    /** Called by the player screen, which watches the frame times itself. */
    fun frame(presentationTimeUs: Long) {
        lastPtsUs = presentationTimeUs
        if (framesSeen++ < 4) log("frame#$framesSeen pts=${presentationTimeUs / 1000}")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        log("playbackState=$state pos=${eventTime.eventPlaybackPositionMs}")
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        log("firstFrameRendered pos=${eventTime.eventPlaybackPositionMs}")
    }

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        log("audioAdvancing pos=${eventTime.eventPlaybackPositionMs}")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        log("videoFormat ${format.width}x${format.height} ${format.sampleMimeType} fps=${format.frameRate}")
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        log("audioFormat ${format.sampleMimeType} ${format.sampleRate}")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        log("droppedFrames $droppedFrames in $elapsedMs ms")
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        log("isPlaying=$isPlaying pos=${eventTime.eventPlaybackPositionMs}")
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
        log("error ${error.errorCodeName}")
    }
}
