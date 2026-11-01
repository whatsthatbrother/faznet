package com.faznet.tv.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.faznet.tv.BuildConfig
import com.faznet.tv.Perf
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelCheck
import com.faznet.tv.data.ChannelRepository
import com.faznet.tv.data.ChannelScope
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.Parental
import com.faznet.tv.data.Playlist
import com.faznet.tv.data.RemoteSync
import com.faznet.tv.data.Repository
import com.faznet.tv.data.XtreamAccount
import com.faznet.tv.data.XtreamApi
import com.faznet.tv.data.safeMessage
import com.faznet.tv.ui.compose.EmptyInfo
import com.faznet.tv.ui.compose.HomeState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the home screen shows and does. It keeps no channel list: the grid pages through a [ChannelScope], so a playlist
 * of any size costs the screen only what is on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: Repository,
    private val channels: ChannelRepository,
) : ViewModel() {

    /** Things only the screen around this can do. */
    sealed interface Event {
        class Toast(val text: String, val long: Boolean = false) : Event
        class AskPin(val group: String) : Event
        class Play(val channel: Channel) : Event
    }

    val ui = HomeState()

    private val events_ = EventChannel<Event>(EventChannel.BUFFERED)
    val events: Flow<Event> = events_.receiveAsFlow()

    private val scope = MutableStateFlow<ChannelScope?>(null)

    /** The channels of the chosen group. New pages come from the database as the grid gets near them. */
    val pages: Flow<PagingData<Channel>> = scope.filterNotNull().flatMapLatest { channels.pages(it) }.cachedIn(viewModelScope)

    private val tv = context.isTv()
    private var playlist: Playlist? = null
    private var loadedPlaylistId: String? = null
    private var view: ChannelRepository.ListView? = null
    private var selectedGroup = KEY_ALL
    private var autoPlayPending = false
    private var hintShown = false
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var observeJob: Job? = null
    private var filterJob: Job? = null
    private var groupJob: Job? = null

    init {
        viewModelScope.launch { channels.importProgress.collect { ui.importing = it } }
        viewModelScope.launch {
            // Not while the first screen is being drawn.
            delay(CLEAN_UP_DELAY_MS)
            runCatching { channels.cleanUp() }
        }
    }

    /** Coming from a cold start: "open the last channel" applies. */
    fun autoPlayOnce() {
        autoPlayPending = repo.autoPlayLast
    }

    // ---------- coming to the screen ----------

    fun onResume() {
        refreshState()
        syncFromWeb(force = false)
        startWelcomePolling()
    }

    fun onPause() {
        pollJob?.cancel()
    }

    /** Pull playlists uploaded on the website (MAC + PIN). Quiet: errors only matter on the device screen. */
    private fun syncFromWeb(force: Boolean, then: (() -> Unit)? = null) {
        if (repo.serverUrl.isEmpty()) { then?.invoke(); return }
        if (!force && System.currentTimeMillis() - repo.lastSync < 30_000) { then?.invoke(); return }
        viewModelScope.launch {
            val r = RemoteSync.sync(repo)
            if (r is RemoteSync.Result.Ok && r.changed) {
                loadedPlaylistId = null
                refreshState()
            }
            then?.invoke()
        }
    }

    /** While there is nothing to watch, keep checking the server so an upload from the web appears by itself. */
    private fun startWelcomePolling() {
        pollJob?.cancel()
        if (repo.serverUrl.isEmpty()) return
        pollJob = viewModelScope.launch {
            while (repo.playlists().isEmpty()) {
                delay(15_000)
                val r = RemoteSync.sync(repo)
                if (r is RemoteSync.Result.Ok && r.changed) refreshState()
            }
        }
    }

    fun refreshAll() {
        syncFromWeb(force = true) {
            repo.activePlaylist()?.let { p ->
                playlist = p
                ui.playlistName = p.name
                load(p, true)
            }
        }
    }

    /** The list needs to be read again, for instance after the debug playlist was added. */
    fun reload() {
        loadedPlaylistId = null
        refreshState()
    }

    /** A parental lock may have changed: what is hidden has to be worked out again. */
    fun locksChanged() {
        val p = playlist ?: return
        val listId = view?.listId ?: return
        viewModelScope.launch { show(p, listId, keepSelection = true) }
    }

    private fun refreshState() {
        val p = repo.activePlaylist()
        playlist = p
        if (p == null) {
            loadedPlaylistId = null
            view = null
            observeJob?.cancel()
            ui.groups = emptyList()
            scope.value = ChannelScope.Fixed(emptyList())
            ui.xtream = false
            ui.playlistName = ""
            val msg = if (repo.serverUrl.isEmpty()) context.getString(R.string.welcome_msg)
            else context.getString(R.string.welcome_msg) + "\n\n" + context.getString(R.string.welcome_mac, repo.deviceMac)
            ui.empty = EmptyInfo(
                context.getString(R.string.welcome_title), msg, context.getString(R.string.add_playlist), { ui.addPlaylist?.invoke() },
                showDevice = true, showDemo = BuildConfig.DEBUG,
            )
            return
        }
        ui.playlistName = p.name
        // Movies, series and the TV guide come from the Xtream account behind the playlist.
        ui.xtream = XtreamAccount.from(p) != null
        if (p.id != loadedPlaylistId) {
            load(p, false)
        } else {
            if (lockSignature() != shownLocks) locksChanged()
            restoreFocusToLastPlayed()
        }
    }

    /** Changes when a parental lock was set in Settings or opened with the PIN. */
    private fun lockSignature() = Parental.unlocked to if (Parental.enabled(context)) Parental.lockedGroups(context) else emptySet()

    private var shownLocks: Pair<Boolean, Set<String>>? = null

    /** Coming back from the player on TV: put the D-pad focus on the channel that was playing. */
    private fun restoreFocusToLastPlayed() {
        if (!tv) return
        val ch = ChannelStore.lastPlayed ?: return
        val current = scope.value ?: return
        viewModelScope.launch { channels.indexIn(current, ch)?.let { ui.focusIndex = it } }
    }

    private fun load(p: Playlist, force: Boolean) {
        loadJob?.cancel()
        ui.empty = null
        ui.loading = true
        loadJob = viewModelScope.launch {
            try {
                val result = Perf.measure("home.load") { channels.ensureList(p, force) }
                Perf.log("home.channels ${result.channels}")
                show(p, result.listId, keepSelection = false)
                ui.loading = false
                if (tv) focusOnLastChannel(p)
                if (!hintShown && result.channels > 0) {
                    hintShown = true
                    events_.trySend(Event.Toast(context.getString(R.string.hint_long_press), long = true))
                }
                maybeAutoPlay(p)
                // Opened from the database at once; ask the server for a newer list in the background.
                if (!force && channels.ageMs(p) > BACKGROUND_REFRESH_MS) refreshInBackground(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.loading = false
                // Refresh failed (no internet, server down): keep showing the list on the device and say so.
                val existing = if (force) channels.activeList(p.id) else null
                if (existing != null) {
                    events_.trySend(Event.Toast(context.getString(R.string.err_load, e.safeMessage()), long = true))
                    if (loadedPlaylistId != p.id) show(p, existing, keepSelection = false)
                    return@launch
                }
                loadedPlaylistId = null
                view = null
                observeJob?.cancel()
                ui.groups = emptyList()
                scope.value = ChannelScope.Fixed(emptyList())
                ui.empty = EmptyInfo(p.name, context.getString(R.string.err_load, e.safeMessage()), context.getString(R.string.retry), { load(p, true) })
            }
        }
    }

    private fun refreshInBackground(p: Playlist) {
        viewModelScope.launch {
            val result = try { channels.ensureList(p, forceRefresh(p)) } catch (e: CancellationException) { throw e } catch (e: Exception) { return@launch }
            if (playlist?.id != p.id || loadedPlaylistId != p.id || !result.changed) return@launch
            show(p, result.listId, keepSelection = true)
        }
    }

    private fun forceRefresh(p: Playlist) = p.m3uUrl() != null

    private suspend fun focusOnLastChannel(p: Playlist) {
        val v = view ?: return
        ui.focusIndex = channels.lastChannel(p, v)?.let { last -> scope.value?.let { channels.indexIn(it, last) } } ?: 0
    }

    private suspend fun maybeAutoPlay(p: Playlist) {
        if (!autoPlayPending && !ChannelStore.autoPlayNext) return
        autoPlayPending = false
        ChannelStore.autoPlayNext = false
        val v = view ?: return
        val last = channels.lastChannel(p, v) ?: return
        selectedGroup = KEY_ALL
        ui.selectedGroup = KEY_ALL
        open(p, last, v.all(), KEY_ALL, context.getString(R.string.group_all))
    }

    /** Puts a finished list on the screen and follows what changes in it. */
    private suspend fun show(p: Playlist, listId: Long, keepSelection: Boolean) {
        shownLocks = lockSignature()
        val v = channels.listView(listId)
        view = v
        loadedPlaylistId = p.id
        if (!keepSelection) selectedGroup = KEY_ALL
        applySelection(keepScroll = keepSelection)
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            var first = true
            combine(channels.favoriteIds(p.id), channels.recentIds(p.id)) { f, r -> f to r }.collect { (favorites, recents) ->
                ui.favIds = favorites.toHashSet()
                publishGroups(v, favorites, recents)
                // The list on screen is one of these two: show the change.
                if (!first && (selectedGroup == KEY_FAV || selectedGroup == KEY_RECENT)) applySelection(keepScroll = true)
                first = false
            }
        }
        // Filled in as soon as the account answers: without movies or series their buttons stay away.
        ui.hasMovies = false
        ui.hasSeries = false
        XtreamAccount.from(p)?.let { account ->
            viewModelScope.launch { ui.hasMovies = XtreamApi.vodCategories(account).isNotEmpty() }
            viewModelScope.launch { ui.hasSeries = XtreamApi.seriesCategories(account).isNotEmpty() }
        }
    }

    private fun publishGroups(v: ChannelRepository.ListView, favorites: List<Long>, recents: List<Long>) {
        val items = ArrayList<GroupItem>(v.groups.size + 3)
        items.add(GroupItem(KEY_ALL, context.getString(R.string.group_all), v.channelCount))
        items.add(GroupItem(KEY_FAV, context.getString(R.string.group_favorites), favorites.size))
        items.add(GroupItem(KEY_RECENT, context.getString(R.string.group_recent), recents.size))
        for (g in v.groups) items.add(GroupItem(g.name, if (v.isLocked(g.name)) "🔒 ${g.name}" else g.name, g.channelCount))
        ui.groups = items
        ui.selectedGroup = selectedGroup
    }

    // ---------- choosing what the grid shows ----------

    fun selectGroup(key: String, fromClick: Boolean) {
        val v = view ?: return
        if (v.isLocked(key)) {
            // Locked group: only a real click asks for the PIN (not the D-pad passing over it).
            if (fromClick) events_.trySend(Event.AskPin(key))
            return
        }
        if (key == selectedGroup && ui.selectedGroup == key) return
        selectedGroup = key
        ui.selectedGroup = key
        groupJob?.cancel()
        groupJob = viewModelScope.launch {
            // Remote moving over the groups: show the channels of the group it stops on, not of every group it passes.
            if (!fromClick) delay(GROUP_DELAY_MS)
            applySelection(keepScroll = false)
        }
    }

    fun setQuery(text: String) {
        ui.query = text
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            // Typing: for the last letter only.
            if (text.isNotBlank()) delay(SEARCH_DELAY_MS)
            applySelection(keepScroll = false)
        }
    }

    private suspend fun applySelection(keepScroll: Boolean) {
        val p = playlist ?: return
        val v = view ?: return
        if (v.isLocked(selectedGroup)) selectedGroup = KEY_ALL
        ui.selectedGroup = selectedGroup
        val typed = ui.query.trim()
        val next = when {
            typed.isNotEmpty() -> channels.search(v, typed)
            selectedGroup == KEY_ALL -> v.all()
            selectedGroup == KEY_FAV -> v.favorites(p)
            selectedGroup == KEY_RECENT -> v.recents(p)
            else -> v.group(selectedGroup) ?: v.all()
        }
        scope.value = next
        if (!keepScroll) ui.scrollTop++
        ui.empty = if (next.size == 0) EmptyInfo("", context.getString(R.string.no_channels)) else null
    }

    // ---------- channels ----------

    fun openChannel(ch: Channel) {
        val p = playlist ?: return
        val current = scope.value ?: return
        val typed = ui.query.isNotBlank()
        val key = if (typed) null else selectedGroup
        val title = when {
            typed -> context.getString(R.string.group_search)
            selectedGroup == KEY_ALL -> context.getString(R.string.group_all)
            selectedGroup == KEY_FAV -> context.getString(R.string.group_favorites)
            selectedGroup == KEY_RECENT -> context.getString(R.string.group_recent)
            else -> selectedGroup
        }
        open(p, ch, current, key, title)
    }

    private fun open(p: Playlist, ch: Channel, list: ChannelScope, key: String?, title: String) {
        ChannelStore.start(p.id, ch, list, key, title)
        events_.trySend(Event.Play(ch))
    }

    fun toggleFavorite(ch: Channel) {
        val p = playlist ?: return
        viewModelScope.launch {
            val added = channels.toggleFavorite(p, ch)
            events_.trySend(Event.Toast(context.getString(if (added) R.string.fav_added else R.string.fav_removed)))
        }
    }

    /** The title of a group and its channels, for testing them one by one. */
    suspend fun channelsForCheck(key: String): Pair<String, List<Channel>>? {
        val p = playlist ?: return null
        val v = view ?: return null
        val list = when (key) {
            KEY_ALL -> v.all()
            KEY_FAV -> v.favorites(p)
            KEY_RECENT -> v.recents(p)
            else -> v.group(key) ?: return null
        }
        val title = ui.groups.firstOrNull { it.key == key }?.title ?: key
        return title to channels.channelsOf(list)
    }

    /** A check finished: "not working" labels change. */
    fun checkProgress() {
        ui.checkVersion++
    }

    suspend fun checkChannel(ch: Channel): Boolean {
        val ok = ChannelCheck.check(ch, repo.userAgent)
        if (!ok) ui.checkVersion++
        return ok
    }

    /** After the PIN was right. */
    fun unlockedOpen(group: String) {
        val p = playlist ?: return
        val listId = view?.listId ?: return
        viewModelScope.launch {
            show(p, listId, keepSelection = true)
            selectGroup(group, true)
        }
    }

    fun isLockedGroup(key: String) = Parental.isLocked(context, key)

    companion object {
        const val KEY_ALL = "\u0000all"
        const val KEY_FAV = "\u0000fav"
        const val KEY_RECENT = "\u0000recent"
        private const val SEARCH_DELAY_MS = 120L
        private const val BACKGROUND_REFRESH_MS = 3 * 60 * 60 * 1000L
        private const val GROUP_DELAY_MS = 220L
        private const val CLEAN_UP_DELAY_MS = 8_000L
    }
}
