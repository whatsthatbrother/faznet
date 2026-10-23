package com.faznet.tv.player

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Second playback engine based on libVLC. VLC is much more tolerant of badly started or damaged IPTV
 * streams than ExoPlayer, at the cost of a bigger app. One instance per channel, like the ExoPlayer path.
 */
class VlcEngine(context: Context, private val layout: VLCVideoLayout, private val listener: Listener) {

    interface Listener {
        fun onVlcPlaying()
        fun onVlcVideo()
        fun onVlcError()
        fun onVlcEnded()
    }

    private val lib = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--network-caching=1500",
            "--live-caching=1500",
            "--http-reconnect",
            "--no-stats",
            "--no-sub-autodetect-file",
        )
    )

    private val mp = MediaPlayer(lib).apply {
        attachViews(layout, null, true, false)
        setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> listener.onVlcPlaying()
                MediaPlayer.Event.Vout -> if (e.voutCount > 0) listener.onVlcVideo()
                MediaPlayer.Event.EncounteredError -> listener.onVlcError()
                MediaPlayer.Event.EndReached -> listener.onVlcEnded()
                else -> Unit
            }
        }
    }

    private var released = false

    fun play(url: String, userAgent: String, referrer: String?) {
        if (released) return
        val media = Media(lib, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":http-user-agent=$userAgent")
        referrer?.let { media.addOption(":http-referrer=$it") }
        media.addOption(":network-caching=1500")
        mp.media = media
        media.release()
        mp.play()
    }

    fun stop() {
        if (!released) mp.stop()
    }

    /** 0..100 */
    var volume: Int
        get() = if (released) 0 else mp.volume
        set(v) { if (!released) mp.volume = v }

    /** Same order as the app's picture sizes: Fit, Stretch, Zoom. */
    fun setScale(mode: Int) {
        if (released) return
        mp.videoScale = when (mode) {
            1 -> MediaPlayer.ScaleType.SURFACE_FILL
            2 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
    }

    val isPlaying: Boolean get() = !released && mp.isPlaying

    fun videoSize(): String? = if (released) null else mp.currentVideoTrack?.let { "${it.width}x${it.height} ${it.codec ?: ""}" }

    fun audioTracks(): List<Pair<Int, String>> =
        if (released) emptyList() else mp.audioTracks?.filter { it.id >= 0 }?.map { it.id to it.name } ?: emptyList()

    var audioTrack: Int
        get() = if (released) -1 else mp.audioTrack
        set(v) { if (!released) mp.audioTrack = v }

    fun subtitleTracks(): List<Pair<Int, String>> =
        if (released) emptyList() else mp.spuTracks?.filter { it.id >= 0 }?.map { it.id to it.name } ?: emptyList()

    var subtitleTrack: Int
        get() = if (released) -1 else mp.spuTrack
        set(v) { if (!released) mp.spuTrack = v }

    fun release() {
        if (released) return
        released = true
        runCatching { mp.setEventListener(null) }
        runCatching { mp.stop() }
        runCatching { mp.detachViews() }
        runCatching { mp.release() }
        runCatching { lib.release() }
    }
}
