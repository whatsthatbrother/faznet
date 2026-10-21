package com.faznet.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.Repository
import com.faznet.tv.player.PlayerFactory
import com.faznet.tv.player.StreamProbe
import com.faznet.tv.player.StreamType
import com.faznet.tv.player.Throughput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var repo: Repository
    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var tvTitle: TextView
    private lateinit var btnFav: ImageButton
    private lateinit var infoPanel: View
    private lateinit var infoNumber: TextView
    private lateinit var infoLogo: ImageView
    private lateinit var infoName: TextView
    private lateinit var infoGroup: TextView
    private lateinit var tvNumberEntry: TextView
    private lateinit var tvError: TextView
    private lateinit var tvStats: TextView
    private var stallCount = 0
    private var underruns = 0
    private var bufferingSince = 0L
    private var bufferingTotalMs = 0L
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var lastWarning = ""

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var forceHls = false
    private var retries = 0
    private var numberBuffer = ""
    private var flinged = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoPanel.visibility = View.GONE }
    private val commitNumber = Runnable { jumpToNumber() }
    private val retry = Runnable { startCurrent() }
    /** If a live stream stays stuck in buffering, re-open it at the live edge instead of freezing. */
    private val stallWatchdog = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            if (retries < MAX_RETRIES) {
                retries++
                tvError.text = getString(R.string.reconnecting)
                tvError.visibility = View.VISIBLE
                startCurrent()
            } else {
                tvError.text = getString(R.string.err_stream, "TIMEOUT")
                tvError.visibility = View.VISIBLE
            }
        }
    }
    private var playToken = 0
    private var probeJob: Job? = null

    private val aspectModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val aspectLabels = intArrayOf(R.string.aspect_fit, R.string.aspect_fill, R.string.aspect_zoom)

    private lateinit var gestures: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        repo = Repository.get(this)

        channels = ChannelStore.current
        if (channels.isEmpty()) { finish(); return }
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, channels.size - 1)

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        tvTitle = findViewById(R.id.tvTitle)
        btnFav = findViewById(R.id.btnFav)
        infoPanel = findViewById(R.id.infoPanel)
        infoNumber = findViewById(R.id.infoNumber)
        infoLogo = findViewById(R.id.infoLogo)
        infoName = findViewById(R.id.infoName)
        infoGroup = findViewById(R.id.infoGroup)
        tvNumberEntry = findViewById(R.id.tvNumberEntry)
        tvError = findViewById(R.id.tvError)
        tvStats = findViewById(R.id.tvStats)
        if (repo.showStats) startStats()

        playerView.controllerAutoShow = false
        playerView.controllerShowTimeoutMs = 4000
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setShowFastForwardButton(false)
        playerView.setShowRewindButton(false)
        playerView.setShowShuffleButton(false)
        playerView.setShowSubtitleButton(true)
        playerView.resizeMode = aspectModes[repo.aspectMode.coerceIn(0, aspectModes.size - 1)]
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = visibility
            if (visibility == View.VISIBLE) infoPanel.visibility = View.GONE
        })

        btnFav.setOnClickListener { toggleFavorite() }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleAspect() }

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                val min = 80 * resources.displayMetrics.density
                if (abs(dy) > abs(dx) && abs(dy) > min) {
                    zap(if (dy < 0) 1 else -1)
                    flinged = true
                    return true
                }
                return false
            }
        })

        hideSystemUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) return
        initPlayer()
        play(index)
        if (repo.showStats) startStats()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        probeJob?.cancel()
        player?.release()
        player = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun initPlayer() {
        val p = PlayerFactory.create(this, repo.useFfmpeg)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        tvError.visibility = View.GONE
                        retries = 0
                        handler.removeCallbacks(stallWatchdog)
                    }
                    Player.STATE_BUFFERING -> {
                        stallCount++
                        handler.removeCallbacks(stallWatchdog)
                        handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
                    }
                    Player.STATE_ENDED -> {
                        // A live stream should never "end": the server dropped us, reconnect.
                        handler.removeCallbacks(retry)
                        handler.postDelayed(retry, 1000)
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(t: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                underruns++
            }
            override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
            }
            override fun onAudioDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
            }
            override fun onLoadError(t: AnalyticsListener.EventTime, l: LoadEventInfo, m: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                lastWarning = "load: ${error.javaClass.simpleName} ${error.message ?: ""}".take(60)
            }
            override fun onAudioSinkError(t: AnalyticsListener.EventTime, audioSinkError: Exception) {
                lastWarning = "audio: ${audioSinkError.javaClass.simpleName}".take(60)
            }
            override fun onPlaybackStateChanged(t: AnalyticsListener.EventTime, state: Int) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (state == Player.STATE_BUFFERING) bufferingSince = now
                else if (bufferingSince != 0L) { bufferingTotalMs += now - bufferingSince; bufferingSince = 0L }
            }
        })
        if (com.faznet.tv.BuildConfig.DEBUG) p.addAnalyticsListener(EventLogger("FaznetPlayer"))
        p.playWhenReady = true
        playerView.player = p
        player = p
    }

    private fun play(i: Int) {
        index = i
        forceHls = false
        retries = 0
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallWatchdog)
        playerView.hideController()
        val ch = channels[index]
        ChannelStore.lastPlayedUrl = ch.url
        stallCount = 0
        underruns = 0
        bufferingTotalMs = 0
        bufferingSince = 0
        lastWarning = ""
        startCurrent()
        showInfo(ch)
        tvTitle.text = "${ch.number}. ${ch.name}"
        updateFavIcon()
        repo.markWatched(ChannelStore.playlistId, ch.url)
    }

    private fun startCurrent() {
        val p = player ?: return
        val ch = channels[index]
        val token = ++playToken
        tvError.visibility = View.GONE
        val ua = ch.userAgent ?: repo.userAgent
        probeJob?.cancel()
        p.stop()
        probeJob = lifecycleScope.launch {
            val type = if (forceHls) StreamType.HLS else StreamProbe.detect(ch.url, ua, ch.referrer)
            if (token != playToken || player !== p) return@launch
            p.setMediaSource(PlayerFactory.mediaSource(this@PlayerActivity, ch, ua, type))
            p.prepare()
            p.play()
        }
    }

    private fun handleError(error: PlaybackException) {
        val p = player ?: return
        handler.removeCallbacks(stallWatchdog)
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> if (!forceHls) {
                // Extension-less URLs are often HLS: retry as HLS once and remember it
                forceHls = true
                channels.getOrNull(index)?.let { StreamProbe.remember(it.url, StreamType.HLS) }
                startCurrent()
                return
            }
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
        }
        val transient = error.errorCode in 1000..2999 || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
        if (transient && retries < MAX_RETRIES) {
            retries++
            tvError.text = getString(R.string.reconnecting)
            tvError.visibility = View.VISIBLE
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, (1500L * retries).coerceAtMost(6000L))
            return
        }
        tvError.text = getString(R.string.err_stream, error.errorCodeName)
        tvError.visibility = View.VISIBLE
        // Keep the remote free for zapping: don't leave the controller up on a dead channel
        playerView.post { playerView.hideController() }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val v = p.videoFormat
                val a = p.audioFormat
                val counters = p.videoDecoderCounters
                val dropped = counters?.droppedBufferCount ?: 0
                tvStats.text = buildString {
                    append("Buffer  %.1f s\n".format(p.totalBufferedDuration / 1000.0))
                    append("Net     %.2f Mbps\n".format(Throughput.sampleMbps()))
                    if (v != null) append("Video   ${v.width}x${v.height} ${v.sampleMimeType?.substringAfter('/') ?: ""}\n")
                    if (a != null) append("Audio   ${a.sampleMimeType?.substringAfter('/') ?: ""} ${a.channelCount}ch ${a.sampleRate}Hz\n")
                    val audioTracks = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioTracks.isNotEmpty()) {
                        append("ATracks ")
                        append(audioTracks.joinToString(" ") { g ->
                            val f = g.getTrackFormat(0)
                            val mark = if (g.isSelected) "*" else ""
                            "$mark${f.sampleMimeType?.substringAfter('/') ?: "?"}${f.language?.let { "/$it" } ?: ""}${f.id?.let { "#$it" } ?: ""}"
                        })
                        append("\n")
                    }
                    append("Stalls  ${(stallCount - 1).coerceAtLeast(0)}   Drops $dropped   Underruns $underruns\n")
                    val buffering = bufferingTotalMs + if (bufferingSince != 0L) android.os.SystemClock.elapsedRealtime() - bufferingSince else 0L
                    append("Waiting %.1f s   Loading ${if (p.isLoading) "yes" else "no"}   ${stateName(p.playbackState)}\n".format(buffering / 1000.0))
                    append("Pos     %.1f s\n".format(p.currentPosition / 1000.0))
                    if (videoDecoder.isNotEmpty()) append("VDec    ${videoDecoder.take(34)}\n")
                    if (audioDecoder.isNotEmpty()) append("ADec    ${audioDecoder.take(34)}\n")
                    if (lastWarning.isNotEmpty()) append("Warn    $lastWarning")
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun stateName(s: Int) = when (s) {
        Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; else -> "ENDED"
    }

    private fun startStats() {
        tvStats.visibility = View.VISIBLE
        handler.removeCallbacks(statsTick)
        handler.post(statsTick)
    }

    private fun toggleStats() {
        repo.showStats = !repo.showStats
        if (repo.showStats) startStats() else {
            handler.removeCallbacks(statsTick)
            tvStats.visibility = View.GONE
        }
    }

    private fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val n = channels.size
        play(((index + delta) % n + n) % n)
    }

    private fun showInfo(ch: Channel) {
        infoNumber.text = ch.number.toString()
        infoName.text = ch.name
        infoGroup.text = ch.group
        loadLogo(infoLogo, ch.logo)
        if (!playerView.isControllerFullyVisible) infoPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    private fun toggleFavorite() {
        val ch = channels.getOrNull(index) ?: return
        val added = repo.toggleFavorite(ChannelStore.playlistId, ch.url)
        Toast.makeText(this, if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        updateFavIcon()
    }

    private fun updateFavIcon() {
        val ch = channels.getOrNull(index) ?: return
        val fav = repo.favorites(ChannelStore.playlistId).contains(ch.url)
        btnFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    private fun cycleAspect() {
        val next = (repo.aspectMode + 1) % aspectModes.size
        repo.aspectMode = next
        playerView.resizeMode = aspectModes[next]
        Toast.makeText(this, aspectLabels[next], Toast.LENGTH_SHORT).show()
    }

    // ---------- remote / keyboard ----------
    private fun appendDigit(d: Int) {
        if (numberBuffer.length >= 5) numberBuffer = ""
        numberBuffer += d
        tvNumberEntry.text = numberBuffer
        tvNumberEntry.visibility = View.VISIBLE
        handler.removeCallbacks(commitNumber)
        handler.postDelayed(commitNumber, 2000)
    }

    private fun jumpToNumber() {
        handler.removeCallbacks(commitNumber)
        val num = numberBuffer.toIntOrNull()
        numberBuffer = ""
        tvNumberEntry.visibility = View.GONE
        if (num == null) return
        var i = channels.indexOfFirst { it.number == num }
        if (i < 0) {
            i = ChannelStore.all.indexOfFirst { it.number == num }
            if (i >= 0) channels = ChannelStore.all
        }
        if (i >= 0) play(i)
        else Toast.makeText(this, getString(R.string.channel_not_found, num), Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        val controllerVisible = playerView.isControllerFullyVisible

        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) {
            if (first) appendDigit(digit)
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (first) zap(1); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (first) zap(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (!controllerVisible) {
                if (first) zap(1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!controllerVisible) {
                if (first) zap(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (numberBuffer.isNotEmpty()) {
                    if (first) jumpToNumber(); return true
                }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED -> {
                if (first) toggleStats(); return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                if (first) channels.getOrNull(index)?.let { showInfo(it) }; return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (numberBuffer.isNotEmpty()) {
                    if (first) {
                        numberBuffer = ""
                        tvNumberEntry.visibility = View.GONE
                        handler.removeCallbacks(commitNumber)
                    }
                    return true
                }
                if (controllerVisible) {
                    if (first) playerView.hideController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        flinged = false
        gestures.onTouchEvent(ev)
        if (flinged) {
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val STALL_TIMEOUT_MS = 8_000L
        private const val MAX_RETRIES = 5
    }
}
