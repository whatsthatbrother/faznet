package com.faznet.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faznet.tv.BuildConfig
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.Playlist
import com.faznet.tv.data.RemoteSync
import com.faznet.tv.data.Repository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repo: Repository
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var tvPlaylist: TextView
    private lateinit var etSearch: EditText
    private lateinit var rvGroups: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var emptyBox: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var emptyButton: Button

    private var playlist: Playlist? = null
    private var all: List<Channel> = emptyList()
    private var byUrl: Map<String, Channel> = emptyMap()
    private var visible: List<Channel> = emptyList()
    private var selectedGroup = KEY_ALL
    private var loadedPlaylistId: String? = null
    private var autoPlayPending = false
    private var hintShown = false
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = Repository.get(this)

        tvPlaylist = findViewById(R.id.tvPlaylist)
        etSearch = findViewById(R.id.etSearch)
        rvGroups = findViewById(R.id.rvGroups)
        rvChannels = findViewById(R.id.rvChannels)
        progress = findViewById(R.id.progress)
        emptyBox = findViewById(R.id.emptyBox)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyText = findViewById(R.id.emptyText)
        emptyButton = findViewById(R.id.emptyButton)

        val wide = resources.getBoolean(R.bool.wide)
        if (!wide) {
            findViewById<View>(R.id.tvBrand).visibility = View.GONE
            findViewById<View>(R.id.clock).visibility = View.GONE
        }
        groupAdapter = GroupAdapter(horizontal = !wide, selectOnFocus = wide) { key -> selectGroup(key) }
        rvGroups.itemAnimator = null
        rvGroups.layoutManager = LinearLayoutManager(
            this, if (wide) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, false
        )
        rvGroups.adapter = groupAdapter

        channelAdapter = ChannelAdapter(onClick = { pos -> openPlayer(pos) }, onLongClick = { ch -> toggleFavorite(ch) })
        rvChannels.layoutManager = GridLayoutManager(this, spanCount(wide))
        rvChannels.adapter = channelAdapter
        rvChannels.setHasFixedSize(true)
        rvChannels.itemAnimator = null

        etSearch.doAfterTextChanged { applyFilter() }
        tvPlaylist.setOnClickListener { openPlaylists() }
        findViewById<View>(R.id.btnPlaylists).setOnClickListener { openPlaylists() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refreshAll() }
        findViewById<View>(R.id.btnDevice).setOnClickListener { openDevice() }
        if (BuildConfig.DEBUG) findViewById<View>(R.id.btnRefresh).setOnLongClickListener {
            addDebugTestPlaylist(); true
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }

        autoPlayPending = savedInstanceState == null && repo.autoPlayLast
        if (BuildConfig.DEBUG && repo.playlists().none { it.name == "Test2 (debug)" } &&
            runCatching { assets.open("test.m3u").close() }.isSuccess) addDebugTestPlaylist()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        syncFromWeb(force = false)
        startWelcomePolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    /** Pull playlists uploaded on the website (MAC + PIN). Quiet: errors only matter on the device screen. */
    private fun syncFromWeb(force: Boolean, then: (() -> Unit)? = null) {
        if (repo.serverUrl.isEmpty()) { then?.invoke(); return }
        if (!force && System.currentTimeMillis() - repo.lastSync < 30_000) { then?.invoke(); return }
        lifecycleScope.launch {
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
        pollJob = lifecycleScope.launch {
            while (repo.playlists().isEmpty()) {
                delay(15_000)
                val r = RemoteSync.sync(repo)
                if (r is RemoteSync.Result.Ok && r.changed) refreshState()
            }
        }
    }

    private fun refreshAll() {
        syncFromWeb(force = true) {
            repo.activePlaylist()?.let { p ->
                playlist = p
                tvPlaylist.text = p.name
                load(p, true)
            }
        }
    }

    /** Debug builds only: a local test playlist from app/src/debug/assets (never committed), else the public demo. */
    private fun addDebugTestPlaylist() {
        val local = try { assets.open("test.m3u").use { it.readBytes() } } catch (e: Exception) { null }
        if (local != null) {
            val id = repo.newId()
            repo.cacheFile(id).writeBytes(local)
            repo.addPlaylist(Playlist(id, "Test2 (debug)", Playlist.TYPE_FILE, "test.m3u"))
        } else {
            repo.addPlaylist(
                Playlist(repo.newId(), "Demo · Kosovo", Playlist.TYPE_URL, "https://iptv-org.github.io/iptv/countries/xk.m3u")
            )
        }
        loadedPlaylistId = null
        refreshState()
    }

    private fun openDevice() {
        startActivity(Intent(this, DeviceActivity::class.java))
    }

    private fun refreshState() {
        val p = repo.activePlaylist()
        playlist = p
        if (p == null) {
            loadedPlaylistId = null
            all = emptyList(); byUrl = emptyMap(); visible = emptyList()
            groupAdapter.submit(emptyList(), KEY_ALL)
            channelAdapter.submit(emptyList(), emptySet())
            tvPlaylist.text = getString(R.string.add_playlist)
            val msg = if (repo.serverUrl.isEmpty()) getString(R.string.welcome_msg)
            else getString(R.string.welcome_msg) + "\n\n" + getString(R.string.welcome_mac, repo.deviceMac, repo.devicePin,
                repo.webUrl.removePrefix("https://").removePrefix("http://"))
            showEmpty(getString(R.string.welcome_title), msg, getString(R.string.add_playlist)) {
                openPlaylists(add = true)
            }
            findViewById<Button>(R.id.emptyButton2).apply {
                visibility = View.VISIBLE
                setOnClickListener { openDevice() }
            }
            if (BuildConfig.DEBUG) {
                val demo = findViewById<Button>(R.id.emptyButton3)
                demo.visibility = View.VISIBLE
                demo.setOnClickListener {
                    demo.visibility = View.GONE
                    addDebugTestPlaylist()
                }
            }
            return
        }
        findViewById<View>(R.id.emptyButton2).visibility = View.GONE
        findViewById<View>(R.id.emptyButton3).visibility = View.GONE
        tvPlaylist.text = p.name
        if (p.id != loadedPlaylistId) {
            load(p, false)
        } else {
            rebuild(keepScroll = true)
            restoreFocusToLastPlayed()
        }
    }

    /** Coming back from the player on TV: put the D-pad focus on the channel that was playing. */
    private fun restoreFocusToLastPlayed() {
        val url = ChannelStore.lastPlayedUrl ?: return
        if (rvChannels.isInTouchMode) return
        val i = visible.indexOfFirst { it.url == url }
        if (i < 0) return
        rvChannels.scrollToPosition(i)
        rvChannels.post {
            rvChannels.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus()
        }
    }

    private fun spanCount(wide: Boolean): Int {
        val widthDp = resources.configuration.screenWidthDp - if (wide) 280 else 0
        return (widthDp / 340).coerceIn(1, 4)
    }

    private fun load(p: Playlist, force: Boolean) {
        loadJob?.cancel()
        emptyBox.visibility = View.GONE
        progress.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            try {
                val list = repo.loadChannels(p, force)
                all = list
                byUrl = list.associateBy { it.url }
                loadedPlaylistId = p.id
                selectedGroup = KEY_ALL
                progress.visibility = View.GONE
                rebuild(keepScroll = false)
                if (!hintShown && list.isNotEmpty()) {
                    hintShown = true
                    Toast.makeText(this@MainActivity, R.string.hint_long_press, Toast.LENGTH_LONG).show()
                }
                maybeAutoPlay(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.visibility = View.GONE
                loadedPlaylistId = null
                all = emptyList(); byUrl = emptyMap()
                groupAdapter.submit(emptyList(), KEY_ALL)
                channelAdapter.submit(emptyList(), emptySet())
                showEmpty(p.name, getString(R.string.err_load, e.message ?: e.javaClass.simpleName), getString(R.string.retry)) {
                    load(p, true)
                }
            }
        }
    }

    private fun rebuild(keepScroll: Boolean) {
        val p = playlist ?: return
        val favs = repo.favorites(p.id)
        val items = groupItems(favs)
        if (items.none { it.key == selectedGroup }) selectedGroup = KEY_ALL
        groupAdapter.submit(items, selectedGroup)
        applyFilter(keepScroll)
    }

    private fun selectGroup(key: String) {
        if (key == selectedGroup && groupAdapter.selectedKey == key) return
        selectedGroup = key
        groupAdapter.select(key)
        applyFilter()
    }

    private fun applyFilter(keepScroll: Boolean = false) {
        val p = playlist ?: return
        if (loadedPlaylistId == null) return
        val favs = repo.favorites(p.id)
        val base = when (selectedGroup) {
            KEY_ALL -> all
            KEY_FAV -> all.filter { favs.contains(it.url) }
            KEY_RECENT -> repo.recents(p.id).mapNotNull { byUrl[it] }
            else -> all.filter { it.group == selectedGroup }
        }
        val q = etSearch.text.toString().trim().lowercase()
        visible = if (q.isEmpty()) base else base.filter { it.name.lowercase().contains(q) }
        channelAdapter.submit(visible, favs)
        if (!keepScroll) rvChannels.scrollToPosition(0)
        if (visible.isEmpty()) {
            showEmpty("", getString(R.string.no_channels), null, null)
        } else {
            emptyBox.visibility = View.GONE
        }
    }

    private fun showEmpty(title: String, msg: String, button: String?, action: (() -> Unit)?) {
        emptyBox.visibility = View.VISIBLE
        emptyTitle.text = title
        emptyTitle.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
        emptyText.text = msg
        if (button != null && action != null) {
            emptyButton.visibility = View.VISIBLE
            emptyButton.text = button
            emptyButton.setOnClickListener { action() }
            emptyButton.requestFocus()
        } else {
            emptyButton.visibility = View.GONE
        }
    }

    private fun toggleFavorite(ch: Channel) {
        val p = playlist ?: return
        val added = repo.toggleFavorite(p.id, ch.url)
        Toast.makeText(this, if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        if (selectedGroup == KEY_FAV) {
            rebuild(keepScroll = true)
        } else {
            val favs = repo.favorites(p.id)
            channelAdapter.updateFavorites(favs)
            groupAdapter.submit(groupItems(favs), selectedGroup)
        }
    }

    private fun groupItems(favs: Set<String>): List<GroupItem> {
        val p = playlist ?: return emptyList()
        val counts = LinkedHashMap<String, Int>()
        for (c in all) counts[c.group] = (counts[c.group] ?: 0) + 1
        val items = ArrayList<GroupItem>(counts.size + 3)
        items.add(GroupItem(KEY_ALL, getString(R.string.group_all), all.size))
        items.add(GroupItem(KEY_FAV, getString(R.string.group_favorites), all.count { favs.contains(it.url) }))
        items.add(GroupItem(KEY_RECENT, getString(R.string.group_recent), repo.recents(p.id).count { byUrl.containsKey(it) }))
        counts.forEach { (g, n) -> items.add(GroupItem(g, g, n)) }
        return items
    }

    private fun openPlayer(position: Int) {
        val p = playlist ?: return
        ChannelStore.playlistId = p.id
        ChannelStore.all = all
        ChannelStore.current = visible
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, position))
    }

    private fun maybeAutoPlay(p: Playlist) {
        if (!autoPlayPending) return
        autoPlayPending = false
        val last = repo.lastChannel(p.id) ?: return
        val idx = all.indexOfFirst { it.url == last }
        if (idx < 0) return
        ChannelStore.playlistId = p.id
        ChannelStore.all = all
        ChannelStore.current = all
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, idx))
    }

    private fun openPlaylists(add: Boolean = false) {
        startActivity(Intent(this, PlaylistsActivity::class.java).putExtra(PlaylistsActivity.EXTRA_ADD, add))
    }

    private fun showSettings() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val ua = v.findViewById<EditText>(R.id.etUserAgent)
        val cb = v.findViewById<CheckBox>(R.id.cbAutoplay)
        val cbFfmpeg = v.findViewById<CheckBox>(R.id.cbFfmpeg)
        cbFfmpeg.isChecked = repo.useFfmpeg
        val cbStats = v.findViewById<CheckBox>(R.id.cbStats)
        cbStats.isChecked = repo.showStats
        ua.setText(repo.customUserAgent)
        cb.isChecked = repo.autoPlayLast
        v.findViewById<TextView>(R.id.tvAbout).text = getString(R.string.about, BuildConfig.VERSION_NAME)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                repo.customUserAgent = ua.text.toString()
                repo.autoPlayLast = cb.isChecked
                repo.useFfmpeg = cbFfmpeg.isChecked
                repo.showStats = cbStats.isChecked
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val KEY_ALL = "\u0000all"
        const val KEY_FAV = "\u0000fav"
        const val KEY_RECENT = "\u0000recent"
    }
}
