package com.faznet.tv.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.ChannelMemory
import com.faznet.tv.data.Parental
import com.faznet.tv.data.ProblemLog
import com.faznet.tv.data.ChannelCheck
import com.faznet.tv.data.Epg
import com.faznet.tv.data.Repository
import com.faznet.tv.data.safeMessage
import com.faznet.tv.player.PlayerFactory
import com.faznet.tv.player.StreamProbe
import com.faznet.tv.player.StreamType
import com.faznet.tv.player.Throughput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    /** A browsable group in the in-player channel list. */
    private class Group(val key: String?, val title: String, val channels: List<Channel>)

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
    private lateinit var tvToast: TextView
    private lateinit var listPanel: View
    private lateinit var listGroup: TextView
    private lateinit var listCount: TextView
    private lateinit var listChannels: RecyclerView
    private lateinit var listAdapter: OverlayChannelAdapter
    private lateinit var infoEpg: TextView
    private lateinit var infoEpgProgress: ProgressBar
    private lateinit var listEpg: TextView
    private var infoEpgJob: Job? = null
    private var listEpgJob: Job? = null
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    private var stallCount = 0
    private var underruns = 0
    private var bufferingSince = 0L
    private var bufferingTotalMs = 0L
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var lastWarning = ""

    private var player: ExoPlayer? = null
    private var groups: List<Group> = emptyList()
    /** Group the player zaps through (Up/Down, CH+/-). */
    private var playGroup = 0
    /** Group shown in the channel list (may differ while browsing with Left/Right). */
    private var listGroupIndex = 0
    private var channels: List<Channel> = emptyList()
    private var index = 0
    private var forceHls = false
    private var retries = 0
    private var numberBuffer = ""

    // ---- frozen-picture watchdog state ----
    private var readySince = 0L
    private var firstFrameSeen = false
    private var lastRendered = -1
    private var lastRenderedAt = 0L
    private var lastPos = -1L
    private var lastPosAt = 0L
    private var recoveries = 0
    private var httpRetried = false
    private var okLong = false
    private var totalRecoveries = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoPanel.visibility = View.GONE }
    private val hideTopBar = Runnable { hideOptions() }
    private val hideList = Runnable { hideChannelList() }
    private val hideToast = Runnable { tvToast.visibility = View.GONE }
    private val commitNumber = Runnable { jumpToNumber() }
    private val retry = Runnable { startCurrent() }
    private var pendingZap = -1
    private val zapCommit = Runnable {
        val i = pendingZap
        pendingZap = -1
        if (i >= 0) play(i)
    }

    /** Wi-Fi/network came back: re-open a channel that was stuck or failed instead of waiting for the watchdog. */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // onAvailable also fires right after registering: only react to a network that comes back after a loss.
        @Volatile private var lost = false

        override fun onLost(network: Network) { lost = true }

        override fun onAvailable(network: Network) {
            if (!lost) return
            lost = false
            handler.post {
                val p = player ?: return@post
                if (pendingZap < 0 && (p.playerError != null || p.playbackState == Player.STATE_BUFFERING ||
                        p.playbackState == Player.STATE_IDLE || tvError.visibility == View.VISIBLE)
                ) {
                    retries = 0
                    httpRetried = false
                    freshPlayer()
                    startCurrent()
                }
            }
        }
    }

    /** If a live stream stays stuck in buffering, re-open it at the live edge instead of freezing. */
    private val stallWatchdog = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            if (retries < MAX_RETRIES) {
                retries++
                showReconnecting()
                startCurrent()
            } else {
                tvError.text = getString(R.string.err_stream, "TIMEOUT")
                pbStart.visibility = View.GONE
                tvError.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Some TV decoders (Amlogic on the Mi TV Stick) start without a clean key frame, or wedge after a while:
     * the sound keeps playing but the picture never appears or stops. Re-opening the channel fixes it, so do
     * that automatically: no first frame shortly after READY, or no new frame while the clock still moves,
     * or nothing moving at all while READY.
     */
    private val frameWatch = object : Runnable {
        override fun run() {
            checkFrames()
            handler.postDelayed(this, 500)
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

        val start = ChannelStore.current
        if (start.isEmpty()) { finish(); return }
        val startIndex = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, start.size - 1)
        buildGroups(start)
        channels = groups[playGroup].channels
        // The start list is one of the groups (or the search result), so the index is valid in it.
        index = channels.indexOfFirst { it.url == start[startIndex].url }.coerceAtLeast(0)

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
        tvToast = findViewById(R.id.tvToast)
        listPanel = findViewById(R.id.listPanel)
        listGroup = findViewById(R.id.listGroup)
        listCount = findViewById(R.id.listCount)
        listChannels = findViewById(R.id.listChannels)
        infoEpg = findViewById(R.id.infoEpg)
        infoEpgProgress = findViewById(R.id.infoEpgProgress)
        listEpg = findViewById(R.id.listEpg)
        pbStart = findViewById(R.id.pbStart)

        playerView.useController = false
        playerView.resizeMode = aspectModes[repo.aspectMode.coerceIn(0, aspectModes.size - 1)]

        listAdapter = OverlayChannelAdapter { pos -> onListPick(pos) }
        listChannels.layoutManager = LinearLayoutManager(this)
        listChannels.adapter = listAdapter
        listChannels.itemAnimator = null
        listChannels.setHasFixedSize(true)
        listPanel.isClickable = true  // taps on the panel must not reach the picture behind it
        sizeListPanel()

        findViewById<View>(R.id.listPrev).setOnClickListener { switchListGroup(-1) }
        findViewById<View>(R.id.listNext).setOnClickListener { switchListGroup(1) }
        findViewById<View>(R.id.btnList).setOnClickListener { hideOptions(); showChannelList() }
        btnFav.setOnClickListener { toggleFavorite(); bumpOptions() }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleAspect(); bumpOptions() }
        findViewById<View>(R.id.btnStats).setOnClickListener { toggleStats(); bumpOptions() }
        findViewById<View>(R.id.btnTracks).setOnClickListener { hideOptions(); showTracks() }
        findViewById<View>(R.id.btnSleep).setOnClickListener { hideOptions(); showSleep() }

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    listPanel.visibility == View.VISIBLE -> hideChannelList()
                    topBar.visibility == View.VISIBLE -> hideOptions()
                    else -> showOptions()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || listPanel.visibility == View.VISIBLE) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                val min = 80 * resources.displayMetrics.density
                if (abs(dy) > abs(dx) && abs(dy) > min) {
                    zap(if (dy < 0) 1 else -1)
                    return true
                }
                if (abs(dx) > abs(dy) && abs(dx) > min && dx > 0) {
                    showChannelList()
                    return true
                }
                return false
            }
        })
        playerView.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev); true }

        hideSystemUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Restart in the group being watched, at the channel being watched.
        ChannelStore.current = channels
        ChannelStore.groupKey = groups.getOrNull(playGroup)?.key
        outState.putInt(EXTRA_INDEX, index)
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) return
        play(index)
        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback
            )
        }
        if (repo.showStats) startStats()
        handler.removeCallbacks(frameWatch)
        handler.postDelayed(frameWatch, 1000)
    }

    override fun onStop() {
        super.onStop()
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback) }
        pendingZap = -1
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

    // ---------- groups ----------

    private fun buildGroups(start: List<Channel>) {
        val all = Parental.visible(this, ChannelStore.all.ifEmpty { start })
        val favs = repo.favorites(ChannelStore.playlistId)
        val byUrl = all.associateBy { it.url }
        val list = ArrayList<Group>()
        list.add(Group(MainActivity.KEY_ALL, getString(R.string.group_all), all))
        all.filter { favs.contains(it.url) }.takeIf { it.isNotEmpty() }
            ?.let { list.add(Group(MainActivity.KEY_FAV, getString(R.string.group_favorites), it)) }
        repo.recents(ChannelStore.playlistId).mapNotNull { byUrl[it] }.takeIf { it.isNotEmpty() }
            ?.let { list.add(Group(MainActivity.KEY_RECENT, getString(R.string.group_recent), it)) }
        val byGroup = LinkedHashMap<String, ArrayList<Channel>>()
        for (c in all) byGroup.getOrPut(c.group) { ArrayList() }.add(c)
        byGroup.forEach { (g, chs) -> list.add(Group(g, g, chs)) }

        val key = ChannelStore.groupKey
        var at = if (key != null) list.indexOfFirst { it.key == key } else -1
        if (at >= 0 && list[at].channels.size != start.size) at = -1
        if (at < 0) {
            // Search results (or a list we can't match): keep it as its own group in front.
            list.add(0, Group(null, getString(R.string.group_search), start))
            at = 0
        }
        groups = list
        playGroup = at
        listGroupIndex = at
    }

    // ---------- player ----------

    /** Software video decoder for SD channels that froze before (or always for SD, if chosen in Settings). */
    private fun wantsSoftwareVideo(ch: Channel?): Boolean {
        ch ?: return false
        val info = ChannelMemory.get(this, ch.url) ?: return false
        val sd = info.height in 1..576
        return sd && (repo.softwareSd || info.fixes > 0)
    }

    private var softwareVideo = false

    // ---- clean start: picture and sound stay hidden until the picture runs smoothly ----
    private lateinit var pbStart: View
    private var revealed = true
    private var hiddenSince = 0L

    private fun hideUntilStable() {
        if (revealed) hiddenSince = SystemClock.elapsedRealtime()
        revealed = false
        playerView.alpha = 0f
        player?.volume = 0f
        pbStart.visibility = View.VISIBLE
    }

    /** Channels known to freeze on their first opening are re-opened at once, before any frozen picture. */
    private var preReopened = false

    private fun maybePreReopen() {
        val ch = channels.getOrNull(index) ?: return
        if (preReopened || recoveries > 0 || pendingZap >= 0) return
        if (ChannelMemory.startFreezes(this, ch.url) < 2) return
        preReopened = true
        lastWarning = "quick re-open (known slow start)"
        handler.post {
            freshPlayer()
            startCurrent()
        }
    }

    private fun reveal() {
        if (revealed) return
        // Opened cleanly on the first try: this channel needs the quick re-open less and less.
        if (!preReopened && recoveries == 0) channels.getOrNull(index)?.let { ChannelMemory.cleanStart(this, it.url) }
        revealed = true
        playerView.alpha = 1f
        player?.volume = 1f
        pbStart.visibility = View.GONE
        tvError.visibility = View.GONE
    }

    private fun initPlayer() {
        softwareVideo = wantsSoftwareVideo(channels.getOrNull(index))
        val p = PlayerFactory.create(this, repo.useFfmpeg, tunneling = false, softwareVideo = softwareVideo)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        tvError.visibility = View.GONE
                        retries = 0
                        handler.removeCallbacks(stallWatchdog)
                        val now = SystemClock.elapsedRealtime()
                        readySince = now
                        lastPosAt = now
                        lastRenderedAt = now
                        maybePreReopen()
                    }
                    Player.STATE_BUFFERING -> {
                        stallCount++
                        readySince = 0L
                        handler.removeCallbacks(stallWatchdog)
                        handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
                    }
                    Player.STATE_ENDED -> {
                        // A live stream should never "end": the server dropped us, reconnect.
                        handler.removeCallbacks(retry)
                        handler.postDelayed(retry, 1000)
                    }
                    else -> readySince = 0L
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameSeen = true
                lastRenderedAt = SystemClock.elapsedRealtime()
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
            override fun onVideoInputFormatChanged(t: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                channels.getOrNull(index)?.let { ChannelMemory.rememberHeight(this@PlayerActivity, it.url, format.height) }
            }
            override fun onAudioDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
            }
            override fun onLoadError(t: AnalyticsListener.EventTime, l: LoadEventInfo, m: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                lastWarning = "load: ${error.javaClass.simpleName} ${error.safeMessage()}".take(60)
            }
            override fun onAudioSinkError(t: AnalyticsListener.EventTime, audioSinkError: Exception) {
                lastWarning = "audio: ${audioSinkError.javaClass.simpleName}".take(60)
            }
            override fun onPlaybackStateChanged(t: AnalyticsListener.EventTime, state: Int) {
                val now = SystemClock.elapsedRealtime()
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
        handler.removeCallbacks(zapCommit)
        pendingZap = -1
        index = i
        forceHls = false
        retries = 0
        httpRetried = false
        recoveries = 0
        preReopened = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallWatchdog)
        val ch = channels[index]
        ChannelStore.lastPlayedUrl = ch.url
        stallCount = 0
        underruns = 0
        bufferingTotalMs = 0
        bufferingSince = 0
        lastWarning = ""
        // A brand-new player (and video decoder) for every channel: re-using the decoder across channels is what
        // left the Mi TV Stick with sound but a frozen picture; re-opening always fixed it, so always do that.
        freshPlayer()
        startCurrent()
        warmNeighbours()
        showInfo(ch)
        tvTitle.text = "${ch.number}. ${ch.name}"
        updateFavIcon()
        if (listPanel.visibility == View.VISIBLE) listAdapter.setPlaying(ch.url)
        repo.markWatched(ChannelStore.playlistId, ch.url)
    }

    /** Resolve the servers of the previous/next channels in advance, so zapping to them starts sooner. */
    private fun warmNeighbours() {
        val n = channels.size
        if (n < 2) return
        val hosts = listOf(channels[(index + 1) % n], channels[(index - 1 + n) % n])
            .mapNotNull { runCatching { java.net.URI(it.url).host }.getOrNull() }.distinct()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            hosts.forEach { h -> runCatching { java.net.InetAddress.getAllByName(h) } }
        }
    }

    private fun freshPlayer() {
        probeJob?.cancel()
        player?.let {
            playerView.player = null
            it.release()
        }
        player = null
        videoDecoder = ""
        audioDecoder = ""
        initPlayer()
    }

    private fun startCurrent() {
        val p = player ?: return
        val ch = channels[index]
        val token = ++playToken
        tvError.visibility = View.GONE
        firstFrameSeen = false
        readySince = 0L
        lastRendered = -1
        lastPos = -1L
        hideUntilStable()
        val ua = ch.userAgent ?: repo.userAgent
        probeJob?.cancel()
        // stop() disables the renderers, so the video decoder is released and re-created for the new stream:
        // a decoder that wedged on the previous channel cannot carry over.
        p.stop()
        p.clearMediaItems()
        probeJob = lifecycleScope.launch {
            val type = if (forceHls) StreamType.HLS else StreamProbe.detect(ch.url, ua, ch.referrer)
            if (token != playToken || player !== p) return@launch
            p.setMediaSource(PlayerFactory.mediaSource(this@PlayerActivity, ch, ua, type))
            p.prepare()
            p.play()
        }
    }

    private fun checkFrames() {
        val p = player ?: return
        // Never keep a channel hidden for long: if it can't settle, show it anyway.
        if (!revealed && SystemClock.elapsedRealtime() - hiddenSince > MAX_HIDDEN_MS) reveal()
        if (p.playbackState != Player.STATE_READY || !p.playWhenReady ||
            p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || readySince == 0L
        ) return
        val now = SystemClock.elapsedRealtime()
        val hasVideo = p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        val tunneled = false
        val rendered = p.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount } ?: 0
        if (rendered != lastRendered) {
            lastRendered = rendered
            lastRenderedAt = now
            // Healthy for a while: forgive earlier recoveries on this channel.
            if (firstFrameSeen && now - readySince > 30_000) recoveries = 0
        }
        val pos = p.currentPosition
        if (pos != lastPos) { lastPos = pos; lastPosAt = now }
        if (!revealed) {
            val running = rendered >= STABLE_FRAMES && now - lastRenderedAt < 400
            if (running || (!hasVideo && now - readySince > 500)) reveal()
        }

        // Right after a channel opens is when the picture freezes on the Mi TV Stick (MP2 sound via FFmpeg runs
        // ahead of the picture); re-opening always fixes it, so react within about a second there.
        val early = now - readySince < EARLY_WINDOW_MS
        val frozenLimit = if (early) EARLY_FROZEN_MS else FROZEN_TIMEOUT_MS
        val reason = when {
            hasVideo && !firstFrameSeen && now - readySince > FIRST_FRAME_TIMEOUT_MS -> "no picture"
            // Tunneled playback does not report decoded frames, so only the other checks apply there.
            !tunneled && hasVideo && firstFrameSeen && now - lastRenderedAt > frozenLimit && now - lastPosAt < 1_000 ->
                if (early) "picture frozen at start" else "picture frozen"
            now - lastPosAt > STUCK_TIMEOUT_MS -> "playback stuck"
            else -> return
        }
        if (recoveries >= MAX_RECOVERIES) return
        // No picture at all in strict key-frame mode: this stream probably never sends IDR frames.
        if (reason == "no picture") channels.getOrNull(index)?.let { PlayerFactory.looseKeyframes.add(it.url) }
        recoveries++
        totalRecoveries++
        lastWarning = "auto-fix: $reason"
        channels.getOrNull(index)?.let { ch ->
            // A freeze right at the start comes from the provider (the first seconds of a channel it just
            // started serving); it has nothing to do with the decoder, so don't switch decoders for it.
            if (!reason.endsWith("at start")) ChannelMemory.addFix(this, ch.url)
            else ChannelMemory.addStartFreeze(this, ch.url)
            p.videoFormat?.let { ChannelMemory.rememberHeight(this, ch.url, it.height) }
            ProblemLog.add(this, ch.name, reason + if (softwareVideo) " (software)" else "")
        }
        showReconnecting()
        freshPlayer()
        startCurrent()
    }

    private fun showReconnecting() {
        // While a channel is still opening behind the loading circle, a quiet retry needs no message.
        if (!revealed) return
        tvError.text = getString(R.string.reconnecting)
        tvError.visibility = View.VISIBLE
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
        // The server refused the channel (not in the package, offline, or the account's connection limit while
        // zapping fast): one retry after a pause for the old connection to close, then say so plainly.
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (http != null && http in 400..499) {
            if (!httpRetried) {
                httpRetried = true
                showReconnecting()
                handler.removeCallbacks(retry)
                handler.postDelayed(retry, 2000)
            } else {
                tvError.text = getString(R.string.err_refused, http)
                pbStart.visibility = View.GONE
                channels.getOrNull(index)?.let { ProblemLog.add(this, it.name, "refused by server (HTTP $http)") }
                tvError.visibility = View.VISIBLE
            }
            return
        }
        val transient = error.errorCode in 1000..2999 || error.errorCode in 4000..4999
        if (transient && retries < MAX_RETRIES) {
            retries++
            showReconnecting()
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, (1500L * retries).coerceAtMost(6000L))
            return
        }
        tvError.text = getString(R.string.err_stream, error.errorCodeName)
        pbStart.visibility = View.GONE
        channels.getOrNull(index)?.let { ProblemLog.add(this, it.name, "error ${error.errorCodeName}") }
        tvError.visibility = View.VISIBLE
    }

    // ---------- stats ----------

    private val statsTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val v = p.videoFormat
                val a = p.audioFormat
                val counters = p.videoDecoderCounters?.also { it.ensureUpdated() }
                val dropped = counters?.droppedBufferCount ?: 0
                val frames = counters?.renderedOutputBufferCount ?: 0
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
                            "$mark${f.sampleMimeType?.substringAfter('/') ?: "?"}${f.language?.let { "/$it" } ?: ""}"
                        })
                        append("\n")
                    }
                    val keyMode = if (channels.getOrNull(index)?.url?.let { it in PlayerFactory.looseKeyframes } == true) "loose" else "IDR"
                    append("Frames  $frames   Drops $dropped   Fixes $totalRecoveries   Start $keyMode\n")
                    if (counters != null) append("Late    skip ${counters.skippedOutputBufferCount}  toKey ${counters.droppedToKeyframeCount}  inDrop ${counters.droppedInputBufferCount}  maxRun ${counters.maxConsecutiveDroppedBufferCount}\n")
                    append("Stalls  ${(stallCount - 1).coerceAtLeast(0)}   Underruns $underruns\n")
                    val buffering = bufferingTotalMs + if (bufferingSince != 0L) SystemClock.elapsedRealtime() - bufferingSince else 0L
                    append("Waiting %.1f s   ${stateName(p.playbackState)}\n".format(buffering / 1000.0))
                    if (videoDecoder.isNotEmpty()) append("VDec    ${videoDecoder.take(34)}${if (softwareVideo) " (SW)" else ""}\n")
                    if (audioDecoder.isNotEmpty()) append("ADec    ${audioDecoder.take(34)}\n")
                    val rt = Runtime.getRuntime()
                    append("Memory  ${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} MB")
                    if (PlayerFactory.isLowMemory(this@PlayerActivity)) append(" (low)")
                    if (lastWarning.isNotEmpty()) append("\nWarn    $lastWarning")
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

    // ---------- infobar / options / toast ----------

    /**
     * Fast zapping: Up/Down only moves the selection and shows the infobar; the channel is opened when the
     * presses stop. The old stream is stopped at once so the server sees a single connection.
     */
    private fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val n = channels.size
        val from = if (pendingZap >= 0) pendingZap else index
        pendingZap = ((from + delta) % n + n) % n
        val ch = channels[pendingZap]
        showInfo(ch)
        tvTitle.text = "${ch.number}. ${ch.name}"
        handler.removeCallbacks(stallWatchdog)
        handler.removeCallbacks(retry)
        probeJob?.cancel()
        player?.stop()
        tvError.visibility = View.GONE
        handler.removeCallbacks(zapCommit)
        handler.postDelayed(zapCommit, ZAP_DELAY_MS)
    }

    private fun epgText(nn: Epg.NowNext): String = buildString {
        nn.now?.let { append(getString(R.string.epg_now, it.title, "${timeFmt.format(it.start)}–${timeFmt.format(it.end)}")) }
        nn.next?.let {
            if (isNotEmpty()) append("\n")
            append(getString(R.string.epg_next, it.title, timeFmt.format(it.start)))
        }
    }

    private fun showInfoEpg(ch: Channel) {
        infoEpgJob?.cancel()
        infoEpg.visibility = View.GONE
        infoEpgProgress.visibility = View.GONE
        if (!Epg.supports(ch)) return
        infoEpgJob = lifecycleScope.launch {
            // While zapping quickly, only ask for the channel the user stops on.
            if (Epg.cached(ch) == null) kotlinx.coroutines.delay(500)
            val nn = Epg.nowNext(ch, ch.userAgent ?: repo.userAgent) ?: return@launch
            infoEpg.text = epgText(nn)
            infoEpg.visibility = View.VISIBLE
            nn.now?.let { pr ->
                val span = (pr.end - pr.start).coerceAtLeast(1)
                infoEpgProgress.progress = (((System.currentTimeMillis() - pr.start) * 1000) / span).toInt().coerceIn(0, 1000)
                infoEpgProgress.visibility = View.VISIBLE
            }
        }
    }

    private fun showListEpg(ch: Channel?) {
        listEpgJob?.cancel()
        if (ch == null || !Epg.supports(ch)) { listEpg.visibility = View.GONE; return }
        Epg.cached(ch)?.let { listEpg.text = epgText(it); listEpg.visibility = View.VISIBLE } ?: run { listEpg.visibility = View.GONE }
        // Small delay: scrolling quickly through the list must not fire a request per row.
        listEpgJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(600)
            val nn = Epg.nowNext(ch, ch.userAgent ?: repo.userAgent)
            if (nn == null) { listEpg.visibility = View.GONE; return@launch }
            listEpg.text = epgText(nn)
            listEpg.visibility = View.VISIBLE
        }
    }

    private fun showInfo(ch: Channel) {
        showInfoEpg(ch)
        infoNumber.text = ch.number.toString()
        infoName.text = ch.name
        infoGroup.text = groups.getOrNull(playGroup)?.title ?: ch.group
        loadLogo(infoLogo, ch.logo)
        if (listPanel.visibility != View.VISIBLE && topBar.visibility != View.VISIBLE) infoPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, INFO_MS)
    }

    private fun showOptions() {
        hideChannelList()
        infoPanel.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        findViewById<View>(R.id.btnList).requestFocus()
        bumpOptions()
    }

    private fun bumpOptions() {
        handler.removeCallbacks(hideTopBar)
        handler.postDelayed(hideTopBar, OPTIONS_MS)
    }

    private fun hideOptions() {
        handler.removeCallbacks(hideTopBar)
        topBar.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun toast(text: String) {
        tvToast.text = text
        tvToast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 2000)
    }

    private fun toggleFavorite() {
        val ch = channels.getOrNull(index) ?: return
        val added = repo.toggleFavorite(ChannelStore.playlistId, ch.url)
        toast(getString(if (added) R.string.fav_added else R.string.fav_removed))
        updateFavIcon()
    }

    private fun updateFavIcon() {
        val ch = channels.getOrNull(index) ?: return
        val fav = repo.favorites(ChannelStore.playlistId).contains(ch.url)
        btnFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    /** Sound language and subtitles of the current channel. */
    private fun showTracks() {
        val p = player ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        var checked = -1
        fun name(f: androidx.media3.common.Format, n: Int): String {
            val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale(it).displayLanguage.ifEmpty { it } }
            val codec = f.sampleMimeType?.substringAfter('/') ?: ""
            return listOfNotNull(f.label, lang).firstOrNull()?.let { "$it ($codec)" } ?: "#$n ($codec)"
        }
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            n++
            labels.add(getString(R.string.track_audio, name(g.getTrackFormat(0), n)))
            if (g.isSelected) checked = labels.size - 1
            val group = g.mediaTrackGroup
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group, 0)).build()
            }
        }
        labels.add(getString(R.string.track_subs_off))
        val offIndex = labels.size - 1
        actions.add {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        var anyText = false
        n = 0
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            n++
            anyText = true
            labels.add(getString(R.string.track_subs, name(g.getTrackFormat(0), n)))
            if (g.isSelected) checked = labels.size - 1
            val group = g.mediaTrackGroup
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group, 0)).build()
            }
        }
        if (anyText && checked < 0) checked = offIndex
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tracks)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> actions[which](); d.dismiss() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var sleepAt = 0L
    private val sleepRunnable = Runnable { finishAffinity() }

    private fun showSleep() {
        val minutes = intArrayOf(0, 30, 60, 90, 120)
        val labels = minutes.map { if (it == 0) getString(R.string.sleep_off) else getString(R.string.sleep_minutes, it) }.toTypedArray()
        val current = if (sleepAt == 0L) 0 else -1
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer)
            .setSingleChoiceItems(labels, current) { d, which ->
                handler.removeCallbacks(sleepRunnable)
                val m = minutes[which]
                sleepAt = if (m == 0) 0L else SystemClock.elapsedRealtime() + m * 60_000L
                if (m > 0) handler.postDelayed(sleepRunnable, m * 60_000L)
                toast(labels[which])
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun cycleAspect() {
        val next = (repo.aspectMode + 1) % aspectModes.size
        repo.aspectMode = next
        playerView.resizeMode = aspectModes[next]
        toast(getString(R.string.picture, getString(aspectLabels[next])))
    }

    // ---------- channel list over the picture ----------

    private fun sizeListPanel() {
        if (isTv()) {
            // Overscan: keep the list and the bars inside the visible area of the TV.
            val d = resources.displayMetrics.density
            listPanel.setPadding((36 * d).toInt(), (28 * d).toInt(), listPanel.paddingRight, (24 * d).toInt())
        }
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val target = when {
            widthDp < 600 -> widthDp * 0.82f
            else -> (widthDp * 0.40f).coerceIn(340f, 520f) + if (isTv()) 24f else 0f
        }
        listPanel.layoutParams = listPanel.layoutParams.apply { width = (target * dm.density).toInt() }
    }

    private fun showChannelList() {
        hideOptions()
        infoPanel.visibility = View.GONE
        listGroupIndex = playGroup
        fillList(focusUrl = channels.getOrNull(index)?.url)
        listPanel.visibility = View.VISIBLE
        bumpList()
    }

    private fun hideChannelList() {
        handler.removeCallbacks(hideList)
        if (listPanel.visibility != View.VISIBLE) return
        listPanel.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun bumpList() {
        handler.removeCallbacks(hideList)
        handler.postDelayed(hideList, LIST_MS)
    }

    private fun switchListGroup(delta: Int) {
        if (groups.isEmpty()) return
        val n = groups.size
        listGroupIndex = ((listGroupIndex + delta) % n + n) % n
        val playingUrl = channels.getOrNull(index)?.url
        val g = groups[listGroupIndex]
        fillList(focusUrl = if (g.channels.any { it.url == playingUrl }) playingUrl else null)
        bumpList()
    }

    private fun fillList(focusUrl: String?) {
        val g = groups[listGroupIndex]
        listGroup.text = g.title
        listAdapter.submit(g.channels, channels.getOrNull(index)?.url)
        val pos = focusUrl?.let { u -> g.channels.indexOfFirst { it.url == u } }?.takeIf { it >= 0 } ?: 0
        listCount.text = getString(R.string.list_count, pos + 1, g.channels.size)
        focusListRow(pos)
        showListEpg(g.channels.getOrNull(pos))
    }

    private fun focusListRow(pos: Int) {
        val lm = listChannels.layoutManager as LinearLayoutManager
        listChannels.post {
            lm.scrollToPositionWithOffset(pos, listChannels.height / 3)
            listChannels.post {
                val vh = listChannels.findViewHolderForAdapterPosition(pos)
                if (vh != null) vh.itemView.requestFocus() else listChannels.requestFocus()
            }
        }
    }

    private fun onListPick(pos: Int) {
        val g = groups[listGroupIndex]
        val ch = g.channels.getOrNull(pos) ?: return
        if (listGroupIndex == playGroup && channels.getOrNull(index)?.url == ch.url) {
            hideChannelList()
            return
        }
        playGroup = listGroupIndex
        channels = g.channels
        hideChannelList()
        play(pos)
    }

    private fun onListFocusMoved() {
        val focused = listChannels.focusedChild ?: return
        val pos = listChannels.getChildAdapterPosition(focused)
        if (pos >= 0) {
            listCount.text = getString(R.string.list_count, pos + 1, listAdapter.itemCount)
            showListEpg(groups.getOrNull(listGroupIndex)?.channels?.getOrNull(pos))
        }
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
            val allIdx = groups.indexOfFirst { it.key == MainActivity.KEY_ALL }
            val all = groups.getOrNull(allIdx)?.channels ?: emptyList()
            i = all.indexOfFirst { it.number == num }
            if (i >= 0) { playGroup = allIdx; channels = all }
        }
        if (i >= 0) play(i)
        else toast(getString(R.string.channel_not_found, num))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        val listOpen = listPanel.visibility == View.VISIBLE
        val optionsOpen = topBar.visibility == View.VISIBLE

        if (listOpen) {
            bumpList()
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) switchListGroup(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) switchListGroup(1); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { if (first) hideChannelList(); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) pageList(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) pageList(1); return true }
            }
            val handled = super.dispatchKeyEvent(event)
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) listChannels.post { onListFocusMoved() }
            return handled
        }

        if (optionsOpen) {
            bumpOptions()
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { if (first) hideOptions(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (first) hideOptions(); return true }
            }
            return super.dispatchKeyEvent(event)
        }

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
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                if (first) zap(1); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (first) zap(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Short OK = channel list, long OK = options (the Mi TV Stick remote has no Menu key).
                if (down && event.repeatCount == 0) okLong = false
                if (down && event.repeatCount > 0 && !okLong && numberBuffer.isEmpty()) {
                    okLong = true
                    showOptions()
                }
                if (event.action == KeyEvent.ACTION_UP && !okLong) {
                    if (numberBuffer.isNotEmpty()) jumpToNumber() else showChannelList()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                if (first) channels.getOrNull(index)?.let { showInfo(it) }; return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (first) showOptions(); return true
            }
            KeyEvent.KEYCODE_PROG_RED -> {
                if (first) toggleStats(); return true
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                if (first) cycleAspect(); return true
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                if (first) toggleFavorite(); return true
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
                if (infoPanel.visibility == View.VISIBLE) {
                    if (first) { handler.removeCallbacks(hideInfo); infoPanel.visibility = View.GONE }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun pageList(dir: Int) {
        val lm = listChannels.layoutManager as LinearLayoutManager
        val focused = listChannels.focusedChild?.let { listChannels.getChildAdapterPosition(it) } ?: lm.findFirstVisibleItemPosition()
        val visible = (lm.findLastVisibleItemPosition() - lm.findFirstVisibleItemPosition()).coerceAtLeast(1)
        val target = (focused + dir * visible).coerceIn(0, (listAdapter.itemCount - 1).coerceAtLeast(0))
        focusListRow(target)
        listCount.text = getString(R.string.list_count, target + 1, listAdapter.itemCount)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (listPanel.visibility == View.VISIBLE) bumpList()
        if (topBar.visibility == View.VISIBLE) bumpOptions()
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val STALL_TIMEOUT_MS = 8_000L
        private const val MAX_RETRIES = 5
        private const val FIRST_FRAME_TIMEOUT_MS = 2_500L
        private const val FROZEN_TIMEOUT_MS = 2_500L
        private const val EARLY_WINDOW_MS = 6_000L
        // The picture is hidden during the start, so reacting quickly costs nothing visible.
        private const val EARLY_FROZEN_MS = 1_000L
        private const val STABLE_FRAMES = 12
        private const val MAX_HIDDEN_MS = 9_000L
        private const val STUCK_TIMEOUT_MS = 8_000L
        private const val MAX_RECOVERIES = 4
        private const val INFO_MS = 4_000L
        private const val OPTIONS_MS = 8_000L
        private const val LIST_MS = 12_000L
        private const val ZAP_DELAY_MS = 450L
    }
}

/** Compact channel rows for the list over the picture. */
internal class OverlayChannelAdapter(private val onPick: (Int) -> Unit) : RecyclerView.Adapter<OverlayChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    private var playingUrl: String? = null

    fun submit(list: List<Channel>, playing: String?) {
        items = list
        playingUrl = playing
        notifyDataSetChanged()
    }

    fun setPlaying(url: String) {
        val old = items.indexOfFirst { it.url == playingUrl }
        playingUrl = url
        val now = items.indexOfFirst { it.url == url }
        if (old >= 0) notifyItemChanged(old)
        if (now >= 0) notifyItemChanged(now)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.ovNumber)
        val logo: ImageView = v.findViewById(R.id.ovLogo)
        val name: TextView = v.findViewById(R.id.ovName)
        val playing: ImageView = v.findViewById(R.id.ovPlaying)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_overlay_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        val isPlaying = ch.url == playingUrl
        h.number.text = ch.number.toString()
        h.name.text = ch.name
        h.itemView.isSelected = isPlaying
        h.itemView.alpha = if (ChannelCheck.isDead(ch.url)) 0.45f else 1f
        h.playing.visibility = if (isPlaying) View.VISIBLE else View.GONE
        loadLogo(h.logo, ch.logo)
        h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onPick) }
    }
}
