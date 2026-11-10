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
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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

    /**
     * Every channel gets a new player but they share one allocator: the next channel re-uses the buffer memory of the
     * last one, instead of the garbage collector freeing tens of MB and the player allocating them again on every zap.
     */
    private val allocator = DefaultAllocator(/* trimOnReset = */ false, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    /** Leaving the player: give the buffer memory back. */
    fun freeBuffers() = allocator.setTargetBufferSize(0)

    /** Channels (URLs) that showed no picture in strict mode this session: open them in tolerant mode. */
    val looseKeyframes = HashSet<String>()

    /** Channels (URLs) the software video decoder could not play this session: the hardware one for them, always. */
    val softwareFailed = HashSet<String>()

    /** Across player screens: a bad period shows over several channels, opened from the list one after another. */
    val badPeriod = BadDecoderPeriod()

    private fun extractors(flags: Int) = DefaultExtractorsFactory()
        .setTsExtractorFlags(flags)
        .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        .setConstantBitrateSeekingEnabled(true)

    /**
     * Video decoders: only the software ones when [softwareVideo] is set and the device has one for the format. Putting
     * them first is not enough: ExoPlayer orders the list again by what each decoder says it supports, and on the Mi TV
     * Stick the hardware decoder came out first for 1080 H.264 again (KLAN PLUS FHD, 06.11). A software decoder that
     * cannot start is a decoder error, which sends the channel back to the hardware one (PlayerActivity.handleError).
     */
    private fun codecSelector(softwareVideo: Boolean) = MediaCodecSelector { mime, secure, tunneling ->
        val list = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        if (softwareVideo && mime.startsWith("video/")) list.filter { it.softwareOnly || isSoftwareDecoder(it.name) }.ifEmpty { list } else list
    }

    /**
     * A software decoder, by its name. The system's own flag is not enough: the Mi TV Stick's firmware lists
     * OMX.google.h264.decoder as not software-only, so asking for software decoders found none, and the old "software
     * decoder for SD" setting never really took effect there.
     */
    fun isSoftwareDecoder(name: String) =
        name.startsWith("OMX.google.", ignoreCase = true) || name.startsWith("c2.android.", ignoreCase = true)

    fun create(context: Context, useFfmpeg: Boolean, softwareVideo: Boolean = false): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (useFfmpeg) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector(softwareVideo))

        val trackSelector = DefaultTrackSelector(context)

        // Live IPTV: start quickly (1 s) and keep a healthy cushion.
        // Many IPTV TS streams are muxed with audio and video several seconds apart, so one track must be
        // buffered far ahead of the other. Time must win over the byte cap, otherwise loading stops with
        // "target buffer size reached with < 500 ms buffered" and playback stalls.
        // The buffer lives on the Java heap, so on TV sticks a big one means GC pauses: it is sized from the device's
        // memory. Providers send 25-30 s at once when a channel opens, so 20 s still fills within the first second.
        val low = isLowMemory(context)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(if (low) 10_000 else 15_000, if (low) 20_000 else 40_000, 1_000, 2_500)
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
        // The Mi TV Stick 4K has 2 GB, and its 32-bit Java heap is what the buffer has to live with.
        return am.isLowRamDevice || info.totalMem in 1 until 2_600L * 1024 * 1024
    }

    private fun bufferBytes(context: Context, low: Boolean): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val heapMb = am.largeMemoryClass.coerceAtLeast(64)
        val mb = if (low) (heapMb / 4).coerceIn(16, 32) else (heapMb / 3).coerceIn(32, 64)
        return mb * 1024 * 1024
    }

    fun mediaSource(
        context: Context,
        ch: Channel,
        userAgent: String,
        type: StreamType,
        errors: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
    ): MediaSource {
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
                .setLoadErrorHandlingPolicy(errors)
                .createMediaSource(item.setMimeType(MimeTypes.APPLICATION_M3U8).build())
            StreamType.DASH -> DashMediaSource.Factory(dataSource)
                .setLoadErrorHandlingPolicy(errors)
                .createMediaSource(item.setMimeType(MimeTypes.APPLICATION_MPD).build())
            StreamType.PROGRESSIVE -> ProgressiveMediaSource.Factory(dataSource, extractors(flags))
                .setLoadErrorHandlingPolicy(errors)
                .createMediaSource(item.build())
            StreamType.RTSP -> androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                .setUserAgent(userAgent)
                .setLoadErrorHandlingPolicy(errors)
                .createMediaSource(item.build())
        }
    }
}
