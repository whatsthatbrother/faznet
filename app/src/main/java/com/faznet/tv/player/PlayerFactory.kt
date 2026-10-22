package com.faznet.tv.player

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.faznet.tv.data.Channel
import com.faznet.tv.net.Http

@OptIn(UnstableApi::class)
object PlayerFactory {

    /**
     * TS parsing. By default video starts only on a real IDR key frame: starting on a non-IDR I-frame makes some
     * TV hardware decoders (Amlogic, Mi TV Stick) output nothing — sound plays, picture stays frozen.
     * Streams that never send IDR frames get the tolerant mode instead (decided per channel at run time,
     * see [looseKeyframes]).
     * FLAG_DETECT_ACCESS_UNITS is deliberately NOT used: on interlaced 1080i broadcast streams it splits
     * pictures wrongly, which corrupts frames and starves the video decoder.
     */
    private const val TS_FLAGS_STRICT = 0
    private const val TS_FLAGS_LOOSE = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES

    /** Channels (URLs) that showed no picture in strict mode this session: open them in tolerant mode. */
    val looseKeyframes = HashSet<String>()

    private fun extractors(flags: Int) = DefaultExtractorsFactory()
        .setTsExtractorFlags(flags)
        .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        .setConstantBitrateSeekingEnabled(true)

    fun create(context: Context, useFfmpeg: Boolean, tunneling: Boolean = false): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                when {
                    !useFfmpeg -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    // Tunneling needs the platform audio decoder: use FFmpeg only for what the TV can't decode (MP2, AC3…)
                    tunneling -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                }
            )
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(tunneling))
        }

        // Live IPTV: start quickly (1 s) and keep a healthy cushion.
        // Many IPTV TS streams are muxed with audio and video several seconds apart, so one track must be
        // buffered far ahead of the other. Time must win over the byte cap, otherwise loading stops with
        // "target buffer size reached with < 500 ms buffered" and playback stalls.
        // The buffer lives on the Java heap: on 1 GB sticks (Mi TV Stick) a big one causes GC pauses and
        // frozen video after some minutes, so it is sized from the device's memory.
        val low = isLowMemory(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (low) 10_000 else 15_000, if (low) 25_000 else 40_000, 1_000, 2_500)
            .setTargetBufferBytes(bufferBytes(context, low))
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    fun isLowMemory(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || info.totalMem in 1 until 1_700L * 1024 * 1024
    }

    private fun bufferBytes(context: Context, low: Boolean): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val heapMb = am.largeMemoryClass.coerceAtLeast(64)
        val mb = if (low) (heapMb / 4).coerceIn(16, 32) else (heapMb / 3).coerceIn(32, 64)
        return mb * 1024 * 1024
    }

    fun mediaSource(context: Context, ch: Channel, userAgent: String, type: StreamType): MediaSource {
        val flags = if (ch.url in looseKeyframes) TS_FLAGS_LOOSE else TS_FLAGS_STRICT
        val headers = HashMap<String, String>()
        ch.referrer?.let { headers["Referer"] = it }
        val http = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setTransferListener(Throughput)
        val dataSource = DefaultDataSource.Factory(context, http)
        val item = MediaItem.Builder()
            .setUri(ch.url)
            // Never speed up to chase the live edge: that eats the buffer and causes stalls on IPTV.
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.0f).build())

        return when (type) {
            StreamType.HLS -> HlsMediaSource.Factory(dataSource)
                .setExtractorFactory(DefaultHlsExtractorFactory(flags, true))
                .setAllowChunklessPreparation(true)
                .createMediaSource(item.setMimeType(MimeTypes.APPLICATION_M3U8).build())
            StreamType.DASH -> DashMediaSource.Factory(dataSource)
                .createMediaSource(item.setMimeType(MimeTypes.APPLICATION_MPD).build())
            StreamType.PROGRESSIVE -> ProgressiveMediaSource.Factory(dataSource, extractors(flags))
                .createMediaSource(item.build())
        }
    }
}
