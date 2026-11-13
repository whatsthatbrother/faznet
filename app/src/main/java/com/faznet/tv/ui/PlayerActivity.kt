package com.faznet.tv.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.sqlite.SQLiteException
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
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.faznet.tv.Perf
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelPages
import com.faznet.tv.data.ChannelRepository
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.ProblemLog
import com.faznet.tv.data.DbErrors
import com.faznet.tv.data.Epg
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.player.PlayerFactory
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.PagedChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
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
    /** What Compose draws over the picture. */
    private val ui = PlayerOverlayState()
    private val messages = PlayerMessages()
    /** False when onCreate finished early (no channel to play after the process was restarted). */
    private var created = false
    private var infoEpgJob: Job? = null
    private var listEpgJob: Job? = null
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())


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
    private var numberBuffer = ""

    private var okLong = false
    private lateinit var playback: LivePlayback

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { ui.infoShown = false }
    private val hideTopBar = Runnable { hideOptions() }
    private val hideList = Runnable { hideChannelList() }
    private val hideToast = Runnable { ui.toast = null }
    private val commitNumber = Runnable { jumpToNumber() }
    private var pendingZap = -1
    private val zapCommit = Runnable {
        val i = pendingZap
        if (i >= 0) lifecycleScope.launch(DbErrors) {
            val ch = zapList.get(i)
            if (pendingZap != i) return@launch
            pendingZap = -1
            if (ch != null) play(ch, i) else listGone()
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
            handler.post { playback.networkBack() }
        }
    }



    private val aspectModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val aspectLabels = intArrayOf(R.string.aspect_fit, R.string.aspect_fill, R.string.aspect_zoom)

    private lateinit var gestures: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones: upright, whichever way the phone is held; sideways only with the full screen button.
        if (!isTv()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        repo = Repository.get(this)

        val startChannel = ChannelStore.startChannel
        val startScope = ChannelStore.scope
        if (startChannel == null || startScope == null) { finish(); return }
        // The channel opens at once; the list it belongs to follows from the database (see loadLists).
        zapList = channelRepo.pagesOf(startScope)
        groups = listOf(Group(ChannelStore.groupKey, ChannelStore.groupTitle, zapList))
        current = startChannel
        index = 0

        playerView = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            isFocusable = false
            keepScreenOn = true
        }
        playback = LivePlayback(
            this, repo, handler, playerView, messages,
            zapping = { pendingZap >= 0 }, onBadPeriod = ::suggestRestart,
        )

        playerView.useController = false
        playerView.resizeMode = aspectModes[repo.aspectMode.coerceIn(0, aspectModes.size - 1)]
        ui.fullscreenButton = !isTv()
        sizeListPanel()

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    ui.listShown -> hideChannelList()
                    ui.optionsShown -> hideOptions()
                    else -> showOptions()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || ui.listShown) return false
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

        val tv = isTv()
        window.setBackgroundDrawable(null)
        setContent {
            FaznetTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView({ playerView }, Modifier.fillMaxSize())
                    PlayerOverlay(ui, messages, overlayActions, tv, wide = resources.configuration.screenWidthDp >= 900)
                }
            }
        }
        created = true
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
        playback.watch()
        // onStop clears the handler: a sleep timer set before goes on with the time it has left.
        if (sleepAt != 0L) {
            val left = sleepAt - SystemClock.elapsedRealtime()
            if (left > 0) handler.postDelayed(sleepRunnable, left) else sleepAt = 0L
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback) }
        pendingZap = -1
        handler.removeCallbacksAndMessages(null)
        // Their timers to hide them are gone with the line above: left as they are, the list, the options, a toast or a
        // half-typed channel number stayed on the screen for good after coming back (Home, the TV's standby).
        // Not when onCreate finished early (no channel to play after the process was restarted): no views then.
        if (created) {
            hideChannelList()
            hideOptions()
            ui.toast = null
            numberBuffer = ""
            ui.numberEntry = ""
        }
        if (::playback.isInitialized) playback.close()
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
        lifecycleScope.launch(DbErrors) {
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

    /**
     * The next channel is not there: the list this screen holds has gone, and a newer one took its place. Zapping has
     * stopped the player by then, so the channel opens again, and Up/Down go on in the whole of the new list.
     */
    private fun listGone() {
        val ch = current ?: return
        play(ch, index)
        lifecycleScope.launch(DbErrors) {
            val view = channelRepo.listView(channelRepo.activeList(ChannelStore.playlistId) ?: return@launch)
            val pages = channelRepo.pagesOf(view.all())
            if (pages.size == 0) return@launch
            zapList = pages
            groups = listOf(Group(MainActivity.KEY_ALL, getString(R.string.group_all), pages))
            playGroup = 0
            listGroupIndex = 0
            index = (ch.number - 1).coerceIn(0, pages.size - 1)
            loadLists()
        }
    }

    // ---------- player ----------


    private fun play(ch: Channel, i: Int) {
        handler.removeCallbacks(zapCommit)
        pendingZap = -1
        index = i
        current = ch
        ChannelStore.lastPlayed = ch
        playback.open(ch)
        warmNeighbours()
        showInfo(ch)
        ui.title = "${ch.number}. ${ch.name}"
        updateFavIcon()
        ui.listPlaying = ch.url
        playlist?.let { p -> lifecycleScope.launch(DbErrors) { channelRepo.markWatched(p, ch) } }
    }

    /** Resolve the servers of the previous/next channels in advance, so zapping to them starts sooner. */
    private fun warmNeighbours() {
        val list = zapList
        val n = list.size
        if (n < 2) return
        val at = index
        lifecycleScope.launch(DbErrors) {
            val hosts = listOfNotNull(list.get((at + 1) % n), list.get((at - 1 + n) % n))
                .mapNotNull { runCatching { java.net.URI(it.url).host }.getOrNull() }.distinct()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                hosts.forEach { h -> runCatching { java.net.InetAddress.getAllByName(h) } }
            }
        }
    }



    /**
     * Several channels froze at their start the same way: the device's decoder is in a bad period, which a restart
     * clears (see BadDecoderPeriod). Said at most once a day, and never again if the viewer asks so.
     */
    private fun suggestRestart() {
        PlayerFactory.badPeriod.reset()
        if (isFinishing) return
        val sp = getSharedPreferences("hints", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (sp.getBoolean("restart_never", false) || now - sp.getLong("restart_at", 0L) < RESTART_HINT_EVERY_MS) return
        sp.edit().putLong("restart_at", now).apply()
        current?.let { ProblemLog.add(this, it.name, "restart suggested: 3 channels froze at their start within 10 min") }
        // Red, and in English and Albanian together whatever the app's language: it has to be noticed and understood.
        val en = inLanguage("en")
        val sq = inLanguage("sq")
        fun both(id: Int, sep: String) = en.getString(id) + sep + sq.getString(id)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Faznet_Dialog_Alert)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(both(R.string.restart_title, "\n"))
            .setMessage(both(R.string.restart_message, "\n\n"))
            .setPositiveButton(both(R.string.restart_ok, " · "), null)
            .setNegativeButton(both(R.string.restart_never, " · ")) { _, _ -> sp.edit().putBoolean("restart_never", true).apply() }
            .show()
    }

    private fun inLanguage(tag: String) = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
    ).resources

    // ---------- phones: upright, full screen by the button ----------

    private var fullscreen = false

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ui.fullscreen = on
    }


    // ---------- stats ----------

    private val statsTick = object : Runnable {
        override fun run() {
            playback.statsText()?.let { ui.stats = it }
            handler.postDelayed(this, 1000)
        }
    }

    private fun startStats() {
        if (ui.stats == null) ui.stats = ""
        handler.removeCallbacks(statsTick)
        handler.post(statsTick)
    }

    private fun toggleStats() {
        repo.showStats = !repo.showStats
        if (repo.showStats) startStats() else {
            handler.removeCallbacks(statsTick)
            ui.stats = null
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
        playback.pressed()
        val from = if (pendingZap >= 0) pendingZap else index
        val at = ((from + delta) % n + n) % n
        pendingZap = at
        zapList.peek(at)?.let(::showZapTarget) ?: lifecycleScope.launch(DbErrors) {
            val ch = zapList.get(at)
            if (ch != null && pendingZap == at) showZapTarget(ch)
        }
        playback.stopForZap()
        messages.errorShown = false
        handler.removeCallbacks(zapCommit)
        val now = Perf.now()
        handler.postDelayed(zapCommit, if (now - lastZapPressAt > ZAP_IDLE_MS) ZAP_FIRST_DELAY_MS else ZAP_DELAY_MS)
        lastZapPressAt = now
    }

    private fun showZapTarget(ch: Channel) {
        showInfo(ch)
        ui.title = "${ch.number}. ${ch.name}"
    }

    private var lastZapPressAt = 0L

    private fun epgText(nn: Epg.NowNext): String = buildString {
        nn.now?.let { append(getString(R.string.epg_now, it.title, "${timeFmt.format(it.start)}–${timeFmt.format(it.end)}")) }
        nn.next?.let {
            if (isNotEmpty()) append("\n")
            append(getString(R.string.epg_next, it.title, timeFmt.format(it.start)))
        }
    }

    /** The Xtream account of the playlist being watched (for now/next of channels whose address does not tell it). */
    private fun xtream(): XtreamAccount? {
        val id = ChannelStore.playlistId
        xtreamFor?.let { if (it.first == id) return it.second }
        return XtreamAccount.from(repo.playlists().firstOrNull { it.id == id }).also { xtreamFor = id to it }
    }
    private var xtreamFor: Pair<String, XtreamAccount?>? = null

    private fun showInfoEpg(ch: Channel) {
        infoEpgJob?.cancel()
        ui.infoEpg = null
        ui.infoProgress = null
        if (!Epg.supports(ch, xtream())) return
        infoEpgJob = lifecycleScope.launch {
            // While zapping quickly, only ask for the channel the user stops on.
            if (Epg.cached(ch) == null) kotlinx.coroutines.delay(500)
            val nn = Epg.nowNext(ch, xtream(), ch.userAgent ?: repo.userAgent) ?: return@launch
            ui.infoEpg = epgText(nn)
            nn.now?.let { pr ->
                val span = (pr.end - pr.start).coerceAtLeast(1)
                ui.infoProgress = ((System.currentTimeMillis() - pr.start).toFloat() / span).coerceIn(0f, 1f)
            }
        }
    }

    private fun showListEpg(ch: Channel?) {
        listEpgJob?.cancel()
        if (ch == null || !Epg.supports(ch, xtream())) { ui.listEpg = null; return }
        ui.listEpg = Epg.cached(ch)?.let { epgText(it) }
        // Small delay: scrolling quickly through the list must not fire a request per row.
        listEpgJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(600)
            val nn = Epg.nowNext(ch, xtream(), ch.userAgent ?: repo.userAgent)
            ui.listEpg = nn?.let { epgText(it) }
        }
    }

    private fun showInfo(ch: Channel) {
        showInfoEpg(ch)
        ui.infoNumber = ch.number.toString()
        ui.infoName = ch.name
        ui.infoGroup = groups.getOrNull(playGroup)?.title ?: ch.group.orEmpty()
        ui.infoLogo = ch.logo
        if (!ui.listShown && !ui.optionsShown) ui.infoShown = true
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, INFO_MS)
    }

    private fun showOptions() {
        hideChannelList()
        ui.infoShown = false
        ui.optionsShown = true
        bumpOptions()
    }

    private fun bumpOptions() {
        handler.removeCallbacks(hideTopBar)
        handler.postDelayed(hideTopBar, OPTIONS_MS)
    }

    private fun hideOptions() {
        handler.removeCallbacks(hideTopBar)
        ui.optionsShown = false
    }

    private fun toast(text: String) {
        ui.toast = text
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 2000)
    }

    private fun toggleFavorite() {
        val ch = current ?: return
        val p = playlist ?: return
        lifecycleScope.launch(DbErrors) {
            val added = try {
                channelRepo.toggleFavorite(p, ch)
            } catch (e: SQLiteException) {
                toast(getString(R.string.err_storage))
                return@launch
            }
            toast(getString(if (added) R.string.fav_added else R.string.fav_removed))
            updateFavIcon()
        }
    }

    private fun updateFavIcon() {
        val ch = current ?: return
        val p = playlist ?: return
        lifecycleScope.launch(DbErrors) {
            val fav = channelRepo.isFavorite(p, ch)
            ui.favorite = fav
        }
    }

    /** Sound language and subtitles of the current channel. */
    private fun showTracks() {
        com.faznet.tv.player.TrackDialog.show(this, playback.player ?: return)
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
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val target = when {
            widthDp < 600 -> widthDp * 0.82f
            else -> (widthDp * 0.40f).coerceIn(340f, 520f) + if (isTv()) 24f else 0f
        }
        ui.listWidth = target.dp
    }

    private fun showChannelList() {
        hideOptions()
        ui.infoShown = false
        listGroupIndex = playGroup
        ui.listShown = true
        fillList()
        bumpList()
    }

    private fun hideChannelList() {
        handler.removeCallbacks(hideList)
        if (!ui.listShown) return
        ui.listShown = false
        ui.listEpg = null
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

    /** The paged reader of each group's list, kept while the screen is open. */
    private val pagedLists = HashMap<ChannelPages, PagedChannels>()

    private fun fillList() {
        val g = groups[listGroupIndex]
        val ch = current
        ui.listTitle = g.title
        ui.listCount = ""
        ui.listPages = pagedLists.getOrPut(g.pages) { PagedChannels(g.pages) }
        ui.listPlaying = ch?.url
        lifecycleScope.launch(DbErrors) {
            val pos = ch?.let { g.pages.indexOf(it) } ?: 0
            val focused = g.pages.get(pos)
            if (groups.getOrNull(listGroupIndex) !== g || !ui.listShown) return@launch
            ui.listCount = getString(R.string.list_count, pos + 1, g.pages.size)
            ui.focusListRow(pos)
            showListEpg(focused)
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

    private fun onListFocusMoved(pos: Int) {
        ui.listFocused = pos
        val pages = groups.getOrNull(listGroupIndex)?.pages ?: return
        ui.listCount = getString(R.string.list_count, pos + 1, pages.size)
        showListEpg(pages.peek(pos))
    }

    private val overlayActions = object : PlayerOverlayActions {
        override fun listPick(pos: Int) = onListPick(pos)
        override fun listFocusMoved(pos: Int) = onListFocusMoved(pos)
        override fun prevGroup() = switchListGroup(-1)
        override fun nextGroup() = switchListGroup(1)
        override fun openList() { hideOptions(); showChannelList() }
        override fun favorite() { toggleFavorite(); bumpOptions() }
        override fun aspect() { cycleAspect(); bumpOptions() }
        override fun tracks() { hideOptions(); showTracks() }
        override fun sleep() { hideOptions(); showSleep() }
        override fun playlists() { hideOptions(); switchPlaylist() }
        override fun stats() { toggleStats(); bumpOptions() }
        override fun fullscreen() { setFullscreen(!fullscreen); bumpOptions() }
    }

    // ---------- remote / keyboard ----------

    private fun appendDigit(d: Int) {
        if (numberBuffer.length >= 5) numberBuffer = ""
        numberBuffer += d
        ui.numberEntry = numberBuffer
        handler.removeCallbacks(commitNumber)
        handler.postDelayed(commitNumber, 2000)
    }

    private fun jumpToNumber() {
        handler.removeCallbacks(commitNumber)
        val num = numberBuffer.toIntOrNull()
        numberBuffer = ""
        ui.numberEntry = ""
        if (num == null) return
        lifecycleScope.launch(DbErrors) {
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

    /** The screen took a Back press (closed the list, the options, a number...): its release must not close the player. */
    private var backTaken = false
    private var passedOn = false

    private fun passOn(event: KeyEvent): Boolean {
        passedOn = true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A Back press used here (closing the list, the options, a number): its release is kept here too, so nothing
        // else on the screen takes it for a Back of its own.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && backTaken) {
            backTaken = false
            return true
        }
        passedOn = false
        val handled = handleKey(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) backTaken = handled && !passedOn
        return handled
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        val listOpen = ui.listShown
        val optionsOpen = ui.optionsShown

        if (listOpen) {
            bumpList()
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) switchListGroup(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) switchListGroup(1); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { if (first) hideChannelList(); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) pageList(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) pageList(1); return true }
            }
            return passOn(event)
        }

        if (optionsOpen) {
            bumpOptions()
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { if (first) hideOptions(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (first) hideOptions(); return true }
            }
            return passOn(event)
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
                // Phone in full screen: Back makes it upright again first.
                if (fullscreen) {
                    if (first) setFullscreen(false)
                    return true
                }
                if (numberBuffer.isNotEmpty()) {
                    if (first) {
                        numberBuffer = ""
                        ui.numberEntry = ""
                        handler.removeCallbacks(commitNumber)
                    }
                    return true
                }
                if (ui.infoShown) {
                    if (first) { handler.removeCallbacks(hideInfo); ui.infoShown = false }
                    return true
                }
            }
        }
        return passOn(event)
    }

    private fun pageList(dir: Int) {
        val count = groups.getOrNull(listGroupIndex)?.pages?.size ?: return
        val info = ui.listState.layoutInfo
        val focused = ui.listFocused.takeIf { it >= 0 } ?: ui.listState.firstVisibleItemIndex
        val visible = (info.visibleItemsInfo.size - 1).coerceAtLeast(1)
        val target = (focused + dir * visible).coerceIn(0, (count - 1).coerceAtLeast(0))
        ui.focusListRow(target)
        ui.listCount = getString(R.string.list_count, target + 1, count)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ui.listShown) bumpList()
        if (ui.optionsShown) bumpOptions()
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        private const val RESTART_HINT_EVERY_MS = 24 * 60 * 60 * 1000L
        private const val INFO_MS = 4_000L
        private const val OPTIONS_MS = 8_000L
        private const val LIST_MS = 12_000L
        // A single press opens the channel soon; while presses keep coming only the one the user stops on opens.
        private const val ZAP_FIRST_DELAY_MS = 200L
        private const val ZAP_DELAY_MS = 350L
        private const val ZAP_IDLE_MS = 1_000L
    }
}
