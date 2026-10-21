package com.faznet.tv.player

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
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.faznet.tv.data.Channel
import com.faznet.tv.net.Http

@OptIn(UnstableApi::class)
object PlayerFactory {

    /**
     * IPTV-friendly TS parsing: tolerate streams that start without an IDR frame (common when joining a live
     * channel). FLAG_DETECT_ACCESS_UNITS is deliberately NOT used: on interlaced 1080i broadcast streams it splits
     * pictures wrongly, which corrupts frames and starves the video decoder.
     */
    private const val TS_FLAGS = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES

    private val extractors by lazy {
        DefaultExtractorsFactory()
            .setTsExtractorFlags(TS_FLAGS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
            .setConstantBitrateSeekingEnabled(true)
    }

    fun create(context: Context, useFfmpeg: Boolean): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (useFfmpeg) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
            .setEnableDecoderFallback(true)

        // Live IPTV: start quickly (1.5 s) and keep a healthy cushion (15–40 s).
        // Many IPTV TS streams are muxed with audio and video several seconds apart, so one track must be
        // buffered far ahead of the other. Time must win over the byte cap, otherwise loading stops with
        // "target buffer size reached with < 500 ms buffered" and playback stalls. 64 MB is still safe on 1 GB sticks.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    fun mediaSource(context: Context, ch: Channel, userAgent: String, type: StreamType): MediaSource {
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
                .setExtractorFactory(DefaultHlsExtractorFactory(TS_FLAGS, true))
                .setAllowChunklessPreparation(true)
                .createMediaSource(item.setMimeType(MimeTypes.APPLICATION_M3U8).build())
            StreamType.DASH -> DashMediaSource.Factory(dataSource)
                .createMediaSource(item.setMimeType(MimeTypes.APPLICATION_MPD).build())
            StreamType.PROGRESSIVE -> ProgressiveMediaSource.Factory(dataSource, extractors)
                .createMediaSource(item.build())
        }
    }
}
