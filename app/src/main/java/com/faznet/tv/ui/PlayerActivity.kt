package com.faznet.tv.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import com.faznet.tv.Perf
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelPages
import com.faznet.tv.data.ChannelRepository
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.ChannelMemory
import com.faznet.tv.data.ProblemLog
import com.faznet.tv.data.ChannelCheck
import com.faznet.tv.data.Epg
import com.faznet.tv.data.Repository
import com.faznet.tv.data.safeMessage
import com.faznet.tv.player.ExoWatch
import com.faznet.tv.player.PlayerFactory
import com.faznet.tv.player.ProgressWatch
import com.faznet.tv.player.StreamProbe
import com.faznet.tv.player.StreamType
import com.faznet.tv.player.Throughput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    @Inject lateinit var channelRepo: ChannelRepository

    /** A browsable group in the in-player channel list. */
    private class Group(val key: String?, val title: String, val pages: ChannelPages)

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
    /** The list Up/Down moves through. */
    private lateinit var zapList: ChannelPages
    private var current: Channel? = null
    /** Place of [current] in [zapList]. */
    private var index = 0
    private var forceHls = false
    private var retries = 0
    private var numberBuffer = ""

    private val exoWatch = ExoWatch(com.faznet.tv.BuildConfig.PLAYER_TRACE_WATCH)
    /** Written on the player's thread: the time of the last picture and the distance between the last two. */
    @Volatile private var lastFramePtsUs = -1L
    @Volatile private var frameIntervalUs = 0L
    private var recoveries = 0
    private var httpRetries = 0
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
        if (i >= 0) lifecycleScope.launch {
            val ch = zapList.get(i)
            if (pendingZap != i) return@launch
            pendingZap = -1
            ch?.let { play(it, i) }
        }
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
                    httpRetries = 0
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
            // Often while the channel is still behind the loading circle: it appears the moment it runs.
            handler.postDelayed(this, if (revealed) 500 else 100)
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
        if (!isTv()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(R.layout.activity_player)
        repo = Repository.get(this)

        val startChannel = ChannelStore.startChannel
        val startScope = ChannelStore.scope
        if (startChannel == null || startScope == null) { finish(); return }
        // The channel opens at once; the list it belongs to follows from the database (see loadLists).
        zapList = channelRepo.pagesOf(startScope)
        groups = listOf(Group(ChannelStore.groupKey, ChannelStore.groupTitle, zapList))
        current = startChannel
        index = 0

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

        listAdapter = OverlayChannelAdapter(lifecycleScope) { pos -> onListPick(pos) }
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
        findViewById<View>(R.id.btnPlaylistSwitch).setOnClickListener { hideOptions(); switchPlaylist() }

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
        loadLists()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Restart in the group being watched, at the channel being watched.
        val group = groups.getOrNull(playGroup) ?: return
        val ch = current ?: return
        ChannelStore.start(ChannelStore.playlistId, ch, group.pages.scope, group.key, group.title)
    }

    override fun onStart() {
        super.onStart()
        play(current ?: return, index)
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
        releasePlayer()
        PlayerFactory.freeBuffers()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizeListPanel()
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

    /** Becomes true when the list the channel was opened from is here: only then the next channel is known. */
    private var listReady = false
    private var listView: ChannelRepository.ListView? = null
    private val playlist by lazy { repo.playlists().firstOrNull { it.id == ChannelStore.playlistId } }

    /**
     * Reads the list the channel was opened from, then the other groups for browsing in the channel list. The channel
     * is already opening: on a TV stick this must not hold it up, whatever the size of the list.
     */
    private fun loadLists() {
        lifecycleScope.launch {
            val first = groups[0]
            Perf.measure("player.list") { current?.let { first.pages.indexOf(it) } }?.let { index = it }
            listReady = true
            val p = playlist ?: return@launch
            val view = channelRepo.listView(channelRepo.activeList(p.id) ?: return@launch)
            listView = view
            val all = ArrayList<Group>(view.groups.size + 3)
            all.add(Group(MainActivity.KEY_ALL, getString(R.string.group_all), channelRepo.pagesOf(view.all())))
            view.favorites(p).takeIf { it.size > 0 }?.let { all.add(Group(MainActivity.KEY_FAV, getString(R.string.group_favorites), channelRepo.pagesOf(it))) }
            view.recents(p).takeIf { it.size > 0 }?.let { all.add(Group(MainActivity.KEY_RECENT, getString(R.string.group_recent), channelRepo.pagesOf(it))) }
            for (g in view.openGroups) view.group(g.name)?.let { all.add(Group(g.name, g.name, channelRepo.pagesOf(it))) }
            // The first group stays what it is; one that is no group (search results) goes in front.
            val same = if (first.key == null) -1 else all.indexOfFirst { it.key == first.key }
            if (same >= 0) all[same] = first else all.add(0, first)
            val browsing = groups.getOrNull(listGroupIndex)
            groups = all
            playGroup = all.indexOf(first)
            listGroupIndex = all.indexOf(browsing).coerceAtLeast(0)
        }
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

    // ---- how long a channel took to appear: logged when it does, tools/zap-test.ps1 reads it ----
    private var openPressAt = 0L
    private var openAt = 0L
    private var openPreparedAt = 0L
    private var openReadyAt = 0L
    private var openFrameAt = 0L
    private var openCount = 0

    private fun logOpen() {
        val ch = current ?: return
        val now = Perf.now()
        val press = if (openPressAt != 0L) openPressAt else openAt
        fun after(t: Long) = if (t == 0L) -1 else t - openAt
        Perf.log(
            "open total=${now - press} wait=${openAt - press} prepare=${after(openPreparedAt)} ready=${after(openReadyAt)} " +
                "frame=${after(openFrameAt)} show=${now - openAt} reopens=${(openCount - 1).coerceAtLeast(0)} " +
                "forced=${now - hiddenSince > MAX_HIDDEN_MS} ch=${ch.number} ${ch.name}"
        )
        openPressAt = 0L
        openAt = 0L
    }

    private fun reveal() {
        if (revealed) return
        logOpen()
        revealed = true
        playerView.alpha = 1f
        player?.volume = 1f
        pbStart.visibility = View.GONE
        // Shown because it waited too long: an error on the screen has to stay readable.
        if (player?.playerError == null) tvError.visibility = View.GONE
    }

    private var trace: com.faznet.tv.player.PlayerTrace? = null

    private fun initPlayer() {
        softwareVideo = wantsSoftwareVideo(current)
        val p = PlayerFactory.create(this, repo.useFfmpeg, tunneling = false, softwareVideo = softwareVideo)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        if (openReadyAt == 0L) openReadyAt = Perf.now()
                        tvError.visibility = View.GONE
                        retries = 0
                        handler.removeCallbacks(stallWatchdog)
                        httpRetries = 0
                        exoWatch.onReady(SystemClock.elapsedRealtime())
                    }
                    Player.STATE_BUFFERING -> {
                        stallCount++
                        exoWatch.onNotReady()
                        handler.removeCallbacks(stallWatchdog)
                        handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
                    }
                    Player.STATE_ENDED -> {
                        // A live stream should never "end": the server dropped us, reconnect. With a growing pause
                        // and a limit: a channel that ends at once used to open a new connection every second
                        // forever, which is what trips the provider's connection limit (HTTP 403). After the
                        // limit hardWatch keeps re-opening it, 15 → 30 → 60 → 120 s apart.
                        handler.removeCallbacks(retry)
                        if (retries < MAX_RETRIES) {
                            retries++
                            handler.postDelayed(retry, (1000L * retries).coerceAtMost(5000L))
                        } else {
                            pbStart.visibility = View.GONE
                            tvError.text = getString(R.string.err_stream, "ENDED")
                            tvError.visibility = View.VISIBLE
                            current?.let { ProblemLog.add(this@PlayerActivity, it.name, "stream ended $MAX_RETRIES times") }
                        }
                    }
                    else -> exoWatch.onNotReady()
                }
            }

            override fun onRenderedFirstFrame() {
                if (openFrameAt == 0L) openFrameAt = Perf.now()
                exoWatch.onFirstFrame(SystemClock.elapsedRealtime())
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
                current?.let { ChannelMemory.rememberHeight(this@PlayerActivity, it.url, format.height) }
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
        if (com.faznet.tv.BuildConfig.PLAYER_TRACE) {
            trace?.stop()
            trace = com.faznet.tv.player.PlayerTrace(p, handler, current?.let { "${it.number} ${it.name}" } ?: "")
        }
        p.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (lastFramePtsUs >= 0) frameIntervalUs = presentationTimeUs - lastFramePtsUs
            lastFramePtsUs = presentationTimeUs
            if (com.faznet.tv.BuildConfig.PLAYER_TRACE) trace?.frame(presentationTimeUs)
        }
        // Live TV has no pause button, so nothing may pause it. Android sends "audio becoming noisy" whenever the
        // HDMI signal drops for a moment (TV switched input or off and on, CEC standby, a loose cable); ExoPlayer
        // then paused, and since the watchdogs only look at a player that is meant to play, the channel stayed
        // frozen until the user left it (Mi TV Stick, 31.10: HDMI gone for 0.6 s → paused for good).
        p.setHandleAudioBecomingNoisy(false)
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady || player !== p) return
                // Paused by the system anyway (audio focus, a remote, …): open the channel again at the live edge,
                // since the picture in the buffer is old by now.
                current?.let { ProblemLog.add(this@PlayerActivity, it.name, "paused by the system ($reason) → re-open") }
                handler.post { if (player === p) { freshPlayer(); startCurrent() } }
            }
        })
        p.playWhenReady = true
        playerView.player = p
        player = p
    }

    private fun play(ch: Channel, i: Int) {
        openAt = Perf.now()
        openCount = 0
        handler.removeCallbacks(zapCommit)
        pendingZap = -1
        index = i
        current = ch
        forceHls = false
        retries = 0
        httpRetries = 0
        recoveries = 0
        progressWatch.reset(SystemClock.elapsedRealtime())
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallWatchdog)
        ChannelStore.lastPlayed = ch
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
        playlist?.let { p -> lifecycleScope.launch { channelRepo.markWatched(p, ch) } }
    }

    /** Resolve the servers of the previous/next channels in advance, so zapping to them starts sooner. */
    private fun warmNeighbours() {
        val list = zapList
        val n = list.size
        if (n < 2) return
        val at = index
        lifecycleScope.launch {
            val hosts = listOfNotNull(list.get((at + 1) % n), list.get((at - 1 + n) % n))
                .mapNotNull { runCatching { java.net.URI(it.url).host }.getOrNull() }.distinct()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                hosts.forEach { h -> runCatching { java.net.InetAddress.getAllByName(h) } }
            }
        }
    }

    private fun releasePlayer() {
        probeJob?.cancel()
        player?.let {
            playerView.player = null
            it.release()
        }
        player = null
    }

    private fun freshPlayer() {
        releasePlayer()
        videoDecoder = ""
        audioDecoder = ""
        initPlayer()
    }

    private fun startCurrent() {
        refused = false
        // A re-open long after the channel appeared is timed by itself.
        if (openAt == 0L) openAt = Perf.now()
        openCount++
        openPreparedAt = 0L
        openReadyAt = 0L
        openFrameAt = 0L
        val p = player ?: return
        val ch = current ?: return
        val token = ++playToken
        tvError.visibility = View.GONE
        exoWatch.onOpen()
        lastFramePtsUs = -1L
        frameIntervalUs = 0L
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
            openPreparedAt = Perf.now()
            p.prepare()
            p.play()
        }
    }

    private val progressWatch = ProgressWatch()
    /** The server said no, for good as far as this opening goes: nothing re-opens it until the viewer does something. */
    private var refused = false

    /** @return true if it re-opened the channel. */
    private fun hardWatch(pos: Long, frames: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        // Retries after a refusal are under way: starting over here would only make more connections.
        val waited = progressWatch.check(now, pos, frames, revealed, pendingZap >= 0 || httpRetries > 0) ?: return false
        val ch = current ?: return false
        ProblemLog.add(this, ch.name, "no progress for ${waited / 1000} s → re-open")
        lastWarning = "watchdog re-open"
        retries = 0
        recoveries = 0
        httpRetries = 0
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallWatchdog)
        showReconnecting()
        freshPlayer()
        startCurrent()
        return true
    }

    private fun checkFrames() {
        if (refused) return
        val p = player ?: return
        if (p.playWhenReady) {
            val frames = p.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount.toLong() } ?: 0L
            if (hardWatch(p.currentPosition, frames)) return
        }
        // Never keep a channel hidden for long: if it can't settle, show it anyway.
        val now = SystemClock.elapsedRealtime()
        if (!revealed && now - hiddenSince > MAX_HIDDEN_MS) reveal()
        if (p.playbackState != Player.STATE_READY || !p.playWhenReady ||
            p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || !exoWatch.isReady
        ) return
        val counters = p.videoDecoderCounters?.also { it.ensureUpdated() }
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val activity = if (counters == null) 0L else counters.queuedInputBufferCount.toLong() + rendered +
            counters.skippedOutputBufferCount + counters.droppedBufferCount + counters.droppedInputBufferCount
        val result = exoWatch.check(
            ExoWatch.Sample(
                now, p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO), rendered, activity, p.currentPosition,
                lastFramePtsUs, frameIntervalUs,
            )
        )
        if (result.healthy) recoveries = 0
        if (!revealed && result.canShow) reveal()
        val problem = result.problem ?: return
        val reason = problem.reason
        // The numbers say what really happened; they reach the developer with the problem log.
        val detail = "wedged=${problem.wedged} in=${counters?.queuedInputBufferCount} out=$rendered behind=${problem.behindMs} buf=${p.totalBufferedDuration}"
        Perf.log("freeze $reason $detail ch=${current?.number}")
        // Enough quick fixes on this channel: from here the last-resort watch opens it again, less often.
        if (recoveries >= MAX_RECOVERIES) return
        // No picture at all in strict key-frame mode: this stream probably never sends IDR frames.
        if (problem.noPicture) current?.let { PlayerFactory.looseKeyframes.add(it.url) }
        recoveries++
        totalRecoveries++
        lastWarning = "auto-fix: $reason"
        current?.let { ch ->
            // A freeze right at the start says nothing about the channel, so don't switch decoders for it.
            if (!problem.atStart) ChannelMemory.addFix(this, ch.url)
            p.videoFormat?.let { ChannelMemory.rememberHeight(this, ch.url, it.height) }
            ProblemLog.add(this, ch.name, reason + (if (softwareVideo) " (software)" else "") + " [$detail]")
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
                current?.let { StreamProbe.remember(it.url, StreamType.HLS) }
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
            // Two retries (2 s, then 4 s): the provider often still counts the previous channel's connection.
            if (httpRetries < 2) {
                httpRetries++
                showReconnecting()
                handler.removeCallbacks(retry)
                handler.postDelayed(retry, 2000L * httpRetries)
            } else {
                // Say so and leave it: every further try is one more connection on an account that has a limit.
                refused = true
                reveal()
                tvError.text = getString(R.string.err_refused, http)
                current?.let { ProblemLog.add(this, it.name, "refused by server (HTTP $http)") }
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
        current?.let { ProblemLog.add(this, it.name, "error ${error.errorCodeName}") }
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
                    val keyMode = if (current?.url?.let { it in PlayerFactory.looseKeyframes } == true) "loose" else "IDR"
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
        val n = zapList.size
        if (n == 0 || !listReady) return
        openPressAt = Perf.now()
        val from = if (pendingZap >= 0) pendingZap else index
        val at = ((from + delta) % n + n) % n
        pendingZap = at
        zapList.peek(at)?.let(::showZapTarget) ?: lifecycleScope.launch {
            val ch = zapList.get(at)
            if (ch != null && pendingZap == at) showZapTarget(ch)
        }
        handler.removeCallbacks(stallWatchdog)
        handler.removeCallbacks(retry)
        probeJob?.cancel()
        player?.stop()
        tvError.visibility = View.GONE
        handler.removeCallbacks(zapCommit)
        val now = Perf.now()
        handler.postDelayed(zapCommit, if (now - lastZapPressAt > ZAP_IDLE_MS) ZAP_FIRST_DELAY_MS else ZAP_DELAY_MS)
        lastZapPressAt = now
    }

    private fun showZapTarget(ch: Channel) {
        showInfo(ch)
        tvTitle.text = "${ch.number}. ${ch.name}"
    }

    private var lastZapPressAt = 0L

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
        val ch = current ?: return
        val p = playlist ?: return
        lifecycleScope.launch {
            val added = channelRepo.toggleFavorite(p, ch)
            toast(getString(if (added) R.string.fav_added else R.string.fav_removed))
            updateFavIcon()
        }
    }

    private fun updateFavIcon() {
        val ch = current ?: return
        val p = playlist ?: return
        lifecycleScope.launch {
            val fav = channelRepo.isFavorite(p, ch)
            btnFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
        }
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

    /** Another playlist straight from the player: the main screen loads it and opens its last channel. */
    private fun switchPlaylist() {
        val lists = repo.playlists()
        if (lists.size < 2) { toast(getString(R.string.playlists_only_one)); return }
        val current = lists.indexOfFirst { it.id == ChannelStore.playlistId }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playlists_switch)
            .setSingleChoiceItems(lists.map { it.name }.toTypedArray(), current) { d, which ->
                d.dismiss()
                if (which != current) {
                    repo.activeId = lists[which].id
                    ChannelStore.autoPlayNext = true
                    finish()
                }
            }
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
        listPanel.visibility = View.VISIBLE
        fillList()
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
        fillList()
        bumpList()
    }

    private fun fillList() {
        val g = groups[listGroupIndex]
        val ch = current
        listGroup.text = g.title
        listCount.text = ""
        listAdapter.submit(g.pages, ch?.url)
        lifecycleScope.launch {
            val pos = ch?.let { g.pages.indexOf(it) } ?: 0
            val focused = g.pages.get(pos)
            if (groups.getOrNull(listGroupIndex) !== g || listPanel.visibility != View.VISIBLE) return@launch
            listCount.text = getString(R.string.list_count, pos + 1, g.pages.size)
            focusListRow(pos)
            showListEpg(focused)
        }
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
        val ch = g.pages.peek(pos) ?: return
        if (listGroupIndex == playGroup && current?.url == ch.url) {
            hideChannelList()
            return
        }
        playGroup = listGroupIndex
        zapList = g.pages
        hideChannelList()
        play(ch, pos)
    }

    private fun onListFocusMoved() {
        val focused = listChannels.focusedChild ?: return
        val pos = listChannels.getChildAdapterPosition(focused)
        if (pos >= 0) {
            listCount.text = getString(R.string.list_count, pos + 1, listAdapter.itemCount)
            showListEpg(groups.getOrNull(listGroupIndex)?.pages?.peek(pos))
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
        lifecycleScope.launch {
            val view = listView
            val ch = view?.let { v -> channelRepo.channelByNumber(v.listId, num, v.groups.associate { it.id to it.name }) }
            if (ch == null) {
                toast(getString(R.string.channel_not_found, num))
                return@launch
            }
            val here = zapList.indexOf(ch)
            if (here != null) {
                play(ch, here)
                return@launch
            }
            // Not in this group: channel numbers are those of the whole list.
            val allIdx = groups.indexOfFirst { it.key == MainActivity.KEY_ALL }
            val whole = groups.getOrNull(allIdx)
            val at = whole?.let { it.pages.indexOf(ch) }
            if (whole != null && at != null) {
                playGroup = allIdx
                zapList = whole.pages
                play(ch, at)
            } else toast(getString(R.string.channel_not_found, num))
        }
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
                if (first) current?.let { showInfo(it) }; return true
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
        private const val STALL_TIMEOUT_MS = 8_000L
        private const val MAX_RETRIES = 5
        private const val MAX_HIDDEN_MS = 9_000L
        private const val MAX_RECOVERIES = 4
        private const val INFO_MS = 4_000L
        private const val OPTIONS_MS = 8_000L
        private const val LIST_MS = 12_000L
        // A single press opens the channel soon; while presses keep coming only the one the user stops on opens.
        private const val ZAP_FIRST_DELAY_MS = 200L
        private const val ZAP_DELAY_MS = 350L
        private const val ZAP_IDLE_MS = 1_000L
    }
}

/** Compact channel rows for the list over the picture; the channels come a page at a time as the list reaches them. */
internal class OverlayChannelAdapter(
    private val scope: CoroutineScope,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<OverlayChannelAdapter.VH>() {

    private var pages: ChannelPages? = null
    private var playingUrl: String? = null
    private val reading = HashSet<Int>()

    fun submit(list: ChannelPages, playing: String?) {
        pages = list
        playingUrl = playing
        reading.clear()
        notifyDataSetChanged()
    }

    fun setPlaying(url: String) {
        playingUrl = url
        notifyDataSetChanged()
    }

    private fun read(list: ChannelPages, position: Int) {
        val page = position / ChannelPages.PAGE
        if (!reading.add(page)) return
        scope.launch {
            list.get(position)
            if (pages !== list) return@launch
            reading.remove(page)
            notifyItemRangeChanged(page * ChannelPages.PAGE, ChannelPages.PAGE)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.ovNumber)
        val logo: ImageView = v.findViewById(R.id.ovLogo)
        val name: TextView = v.findViewById(R.id.ovName)
        val playing: ImageView = v.findViewById(R.id.ovPlaying)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_overlay_channel, parent, false))

    override fun getItemCount() = pages?.size ?: 0

    override fun onBindViewHolder(h: VH, position: Int) {
        val list = pages ?: return
        val ch = list.peek(position)
        if (ch == null) {
            read(list, position)
            h.number.text = (position + 1).toString()
            h.name.text = ""
            h.logo.setImageDrawable(null)
            h.playing.visibility = View.GONE
            h.itemView.isSelected = false
            h.itemView.setOnClickListener(null)
            return
        }
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
