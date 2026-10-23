package com.faznet.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.faznet.tv.ui.compose.EmptyInfo
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.HomeActions
import com.faznet.tv.ui.compose.HomeScreen
import com.faznet.tv.ui.compose.HomeState
import com.faznet.tv.BuildConfig
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelCheck
import com.faznet.tv.data.ChannelStore
import com.faznet.tv.data.Parental
import com.faznet.tv.data.ProblemLog
import com.faznet.tv.data.Playlist
import com.faznet.tv.data.RemoteSync
import com.faznet.tv.data.Repository
import com.faznet.tv.data.safeMessage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), HomeActions {

    private lateinit var repo: Repository
    /** Everything the (Compose) home screen shows. */
    private val ui = HomeState()

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
        repo = Repository.get(this)
        val wide = resources.getBoolean(R.bool.wide)
        val tv = isTv()
        setContent { FaznetTheme { HomeScreen(ui, this, wide, tv) } }

        autoPlayPending = savedInstanceState == null && repo.autoPlayLast
        if (savedInstanceState == null) {
            checkForUpdate()
            lifecycleScope.launch {
                com.faznet.tv.data.Reports.sendPendingCrash(this@MainActivity, repo)
                com.faznet.tv.data.Reports.sendDailyIfNeeded(this@MainActivity, repo)
            }
            // The VLC engine is prepared in the background, so the first channel opens sooner.
            if (repo.useVlc) com.faznet.tv.player.VlcEngine.warmUp(applicationContext)
        }
        if (BuildConfig.DEBUG && repo.playlists().none { it.name == "Test2 (debug)" } &&
            runCatching { assets.open("test.m3u").close() }.isSuccess) addDebugTestPlaylist()
    }

    // ---------- HomeActions (called by the Compose screen) ----------
    override fun openChannel(index: Int) = openPlayer(index)
    override fun checkGroup(key: String) = confirmCheck(key)
    override fun playlists() = openPlaylists()
    override fun refresh() = refreshAll()
    override fun debugPlaylist() { if (BuildConfig.DEBUG) addDebugTestPlaylist() }
    override fun device() = openDevice()
    override fun settings() = showSettings()
    override fun movies() = LibraryActivity.start(this, series = false)
    override fun series() = LibraryActivity.start(this, series = true)
    override fun guide() {
        ChannelStore.playlistId = playlist?.id ?: ""
        ChannelStore.all = all
        GuideActivity.start(this)
    }

    /** Search: a small dialog with a text field (works with the TV remote's keyboard); the list filters while typing. */
    override fun search() {
        val et = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            setText(ui.query)
            setSelection(text.length)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            doAfterTextChanged { ui.query = it?.toString() ?: ""; applyFilter() }
        }
        val d = MaterialAlertDialogBuilder(this)
            .setView(padded(et))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.clear) { _, _ -> ui.query = ""; applyFilter() }
            .show()
        et.setOnEditorActionListener { _, _, _ -> d.dismiss(); true }
        et.requestFocus()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
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
                ui.playlistName = p.name
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
            ui.groups = emptyList(); ui.channels = emptyList(); ui.xtream = false
            ui.playlistName = ""
            val msg = if (repo.serverUrl.isEmpty()) getString(R.string.welcome_msg)
            else getString(R.string.welcome_msg) + "\n\n" + getString(R.string.welcome_mac, repo.deviceMac)
            ui.empty = EmptyInfo(
                getString(R.string.welcome_title), msg, getString(R.string.add_playlist), { openPlaylists(add = true) },
                showDevice = true, showDemo = BuildConfig.DEBUG,
            )
            return
        }
        ui.playlistName = p.name
        // Movies, series and the TV guide come from the Xtream account behind the playlist.
        ui.xtream = com.faznet.tv.data.XtreamAccount.from(p) != null
        if (p.id != loadedPlaylistId) {
            load(p, false)
        } else {
            rebuild(keepScroll = true)
            restoreFocusToLastPlayed()
        }
    }

    /** Coming back from the player on TV: put the D-pad focus on the channel that was playing. */
    private fun restoreFocusToLastPlayed() {
        if (!isTv()) return
        val url = ChannelStore.lastPlayedUrl ?: return
        if (visible.any { it.url == url }) ui.focusUrl = url
    }

    private fun load(p: Playlist, force: Boolean) {
        loadJob?.cancel()
        ui.empty = null
        ui.loading = true
        loadJob = lifecycleScope.launch {
            try {
                val list = repo.loadChannels(p, force)
                all = list
                byUrl = list.associateBy { it.url }
                loadedPlaylistId = p.id
                selectedGroup = KEY_ALL
                ui.loading = false
                rebuild(keepScroll = false)
                if (isTv()) visible.firstOrNull { it.url == repo.lastChannel(p.id) }?.let { ui.focusUrl = it.url }
                    ?: visible.firstOrNull()?.let { ui.focusUrl = it.url }
                if (!hintShown && list.isNotEmpty()) {
                    hintShown = true
                    Toast.makeText(this@MainActivity, R.string.hint_long_press, Toast.LENGTH_LONG).show()
                }
                maybeAutoPlay(p)
                // Opened from the copy on the device (instant); refresh it from the server in the background.
                if (!force && repo.cacheAgeMs(p) > BACKGROUND_REFRESH_MS) refreshInBackground(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.loading = false
                loadedPlaylistId = null
                all = emptyList(); byUrl = emptyMap(); visible = emptyList()
                ui.groups = emptyList(); ui.channels = emptyList()
                ui.empty = EmptyInfo(p.name, getString(R.string.err_load, e.safeMessage()), getString(R.string.retry), { load(p, true) })
            }
        }
    }

    /** Long-press on a group: test its channels one by one and dim the ones that don't answer. */
    private fun confirmCheck(key: String) {
        val p = playlist ?: return
        val favs = repo.favorites(p.id)
        val list = when (key) {
            KEY_ALL -> all
            KEY_FAV -> all.filter { favs.contains(it.url) }
            KEY_RECENT -> repo.recents(p.id).mapNotNull { byUrl[it] }
            else -> all.filter { it.group == key }
        }
        if (list.isEmpty()) return
        val title = groupItems(favs).firstOrNull { it.key == key }?.title ?: key
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.check_title)
            .setMessage(getString(R.string.check_confirm, title, list.size))
            .setPositiveButton(R.string.check_start) { _, _ -> runCheck(list) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runCheck(list: List<Channel>) {
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.check_title)
            .setMessage(getString(R.string.check_progress, 0, list.size, 0))
            .setNegativeButton(R.string.cancel) { _, _ -> job?.cancel() }
            .setCancelable(false)
            .show()
        job = lifecycleScope.launch {
            var bad = 0
            list.forEachIndexed { i, ch ->
                if (!ChannelCheck.check(ch, repo.userAgent)) { bad++; ui.checkVersion++ }
                dialog.setMessage(getString(R.string.check_progress, i + 1, list.size, bad))
                delay(250) // gentle on the provider (connection limits)
            }
            dialog.dismiss()
            ui.checkVersion++
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.check_title)
                .setMessage(getString(R.string.check_done, list.size - bad, bad))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        job?.invokeOnCompletion { ui.checkVersion++ }
    }

    private fun refreshInBackground(p: Playlist) {
        lifecycleScope.launch {
            val fresh = try { repo.loadChannels(p, forceRefresh = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { return@launch }
            if (playlist?.id != p.id || loadedPlaylistId != p.id || fresh.isEmpty()) return@launch
            all = fresh
            byUrl = fresh.associateBy { it.url }
            rebuild(keepScroll = true)
        }
    }

    private fun rebuild(keepScroll: Boolean) {
        val p = playlist ?: return
        val favs = repo.favorites(p.id)
        val items = groupItems(favs)
        if (items.none { it.key == selectedGroup }) selectedGroup = KEY_ALL
        ui.groups = items
        ui.selectedGroup = selectedGroup
        applyFilter(keepScroll)
    }

    override fun selectGroup(key: String, fromClick: Boolean) {
        if (Parental.isLocked(this, key)) {
            // Locked group: only a real click asks for the PIN (not the D-pad passing over it).
            if (fromClick) askParentalPin { rebuild(keepScroll = false); selectGroup(key, true) }
            return
        }
        if (key == selectedGroup && ui.selectedGroup == key) return
        selectedGroup = key
        ui.selectedGroup = key
        applyFilter()
    }

    private fun applyFilter(keepScroll: Boolean = false) {
        val p = playlist ?: return
        if (loadedPlaylistId == null) return
        val favs = repo.favorites(p.id)
        if (Parental.isLocked(this, selectedGroup)) { selectedGroup = KEY_ALL; ui.selectedGroup = KEY_ALL }
        val base = Parental.visible(this, when (selectedGroup) {
            KEY_ALL -> all
            KEY_FAV -> all.filter { favs.contains(it.url) }
            KEY_RECENT -> repo.recents(p.id).mapNotNull { byUrl[it] }
            else -> all.filter { it.group == selectedGroup }
        })
        val q = ui.query.trim().lowercase()
        visible = if (q.isEmpty()) base else base.filter { it.name.lowercase().contains(q) }
        ui.favs = favs
        ui.channels = visible
        if (!keepScroll) ui.scrollTop++
        ui.empty = if (visible.isEmpty()) EmptyInfo("", getString(R.string.no_channels)) else null
    }

    override fun toggleFavorite(ch: Channel) {
        val p = playlist ?: return
        val added = repo.toggleFavorite(p.id, ch.url)
        Toast.makeText(this, if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        if (selectedGroup == KEY_FAV) {
            rebuild(keepScroll = true)
        } else {
            val favs = repo.favorites(p.id)
            ui.favs = favs
            ui.groups = groupItems(favs)
        }
    }

    private fun groupItems(favs: Set<String>): List<GroupItem> {
        val p = playlist ?: return emptyList()
        val counts = LinkedHashMap<String, Int>()
        for (c in all) counts[c.group] = (counts[c.group] ?: 0) + 1
        val shown = Parental.visible(this, all)
        val shownUrls = shown.mapTo(HashSet()) { it.url }
        val items = ArrayList<GroupItem>(counts.size + 3)
        items.add(GroupItem(KEY_ALL, getString(R.string.group_all), shown.size))
        items.add(GroupItem(KEY_FAV, getString(R.string.group_favorites), shown.count { favs.contains(it.url) }))
        items.add(GroupItem(KEY_RECENT, getString(R.string.group_recent), repo.recents(p.id).count { it in shownUrls }))
        counts.forEach { (g, n) -> items.add(GroupItem(g, if (Parental.isLocked(this, g)) "🔒 $g" else g, n)) }
        return items
    }

    private fun openPlayer(position: Int) {
        val p = playlist ?: return
        ChannelStore.playlistId = p.id
        ChannelStore.all = all
        ChannelStore.current = visible
        ChannelStore.groupKey = if (ui.query.isBlank()) selectedGroup else null
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, position))
    }

    private fun maybeAutoPlay(p: Playlist) {
        if (!autoPlayPending && !ChannelStore.autoPlayNext) return
        autoPlayPending = false
        ChannelStore.autoPlayNext = false
        val last = repo.lastChannel(p.id) ?: return
        val idx = all.indexOfFirst { it.url == last }
        if (idx < 0 || Parental.isLocked(this, all[idx].group)) return
        ChannelStore.playlistId = p.id
        ChannelStore.all = all
        ChannelStore.current = all
        ChannelStore.groupKey = KEY_ALL
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
        val cbReports = v.findViewById<CheckBox>(R.id.cbReports)
        cbReports.isChecked = repo.autoReports
        val cbVlc = v.findViewById<CheckBox>(R.id.cbVlc)
        cbVlc.isChecked = repo.useVlc
        val cbSoftSd = v.findViewById<CheckBox>(R.id.cbSoftSd)
        cbSoftSd.isChecked = repo.softwareSd
        ua.setText(repo.customUserAgent)
        cb.isChecked = repo.autoPlayLast
        v.findViewById<TextView>(R.id.tvAbout).text = getString(R.string.about, BuildConfig.VERSION_NAME)
        var dialog: androidx.appcompat.app.AlertDialog? = null
        v.findViewById<Button>(R.id.btnLog).setOnClickListener { showProblemLog() }
        v.findViewById<Button>(R.id.btnParental).setOnClickListener { dialog?.dismiss(); setupParental() }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                repo.customUserAgent = ua.text.toString()
                repo.autoPlayLast = cb.isChecked
                repo.useFfmpeg = cbFfmpeg.isChecked
                repo.showStats = cbStats.isChecked
                repo.softwareSd = cbSoftSd.isChecked
                repo.useVlc = cbVlc.isChecked
                repo.autoReports = cbReports.isChecked
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- problem log ----------
    private fun showProblemLog() {
        val lines = ProblemLog.read(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.problem_log)
            .setMessage(if (lines.isEmpty()) getString(R.string.problem_log_empty) else lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.clear) { _, _ -> ProblemLog.clear(this) }
            .setNegativeButton(R.string.report_send) { _, _ -> sendReport() }
            .show()
    }

    /** Sends the problem log + device info to the developer (Faznet server). */
    private fun sendReport() {
        val et = EditText(this).apply { hint = getString(R.string.report_note_hint) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.report_send)
            .setMessage(R.string.report_explain)
            .setView(padded(et))
            .setPositiveButton(R.string.report_send) { _, _ ->
                lifecycleScope.launch {
                    val ok = com.faznet.tv.data.Reports.sendManual(this@MainActivity, repo, et.text.toString())
                    Toast.makeText(this@MainActivity, if (ok) R.string.report_sent else R.string.report_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- parental lock ----------
    private fun pinField(): EditText = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(4))
        hint = "••••"
    }

    private fun padded(view: View): View = android.widget.FrameLayout(this).apply {
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(view)
    }

    /** Asks for the parental PIN; on success the locked groups stay open until the app is closed. */
    private fun askParentalPin(onOk: () -> Unit) {
        val et = pinField()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.parental_enter_pin)
            .setView(padded(et))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (et.text.toString() == Parental.pin(this)) {
                    Parental.unlocked = true
                    onOk()
                } else Toast.makeText(this, R.string.parental_wrong_pin, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        et.requestFocus()
    }

    private fun setupParental() {
        if (Parental.pin(this).length == 4 && !Parental.unlocked) {
            askParentalPin { setupParental() }
            return
        }
        val et = pinField().apply { setText(Parental.pin(this@MainActivity)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.parental_title)
            .setMessage(R.string.parental_set_pin)
            .setView(padded(et))
            .setPositiveButton(R.string.next) { _, _ ->
                val pin = et.text.toString()
                if (pin.isEmpty()) {
                    Parental.setPin(this, ""); Parental.setLockedGroups(this, emptySet())
                    Toast.makeText(this, R.string.parental_off, Toast.LENGTH_SHORT).show()
                    rebuild(keepScroll = true)
                } else if (pin.length != 4) {
                    Toast.makeText(this, R.string.parental_pin_4, Toast.LENGTH_SHORT).show()
                } else chooseLockedGroups(pin)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseLockedGroups(pin: String) {
        val groups = all.map { it.group }.distinct()
        if (groups.isEmpty()) return
        val locked = Parental.lockedGroups(this)
        val checked = BooleanArray(groups.size) { groups[it] in locked }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.parental_groups)
            .setMultiChoiceItems(groups.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(R.string.save) { _, _ ->
                Parental.setPin(this, pin)
                Parental.setLockedGroups(this, groups.filterIndexed { i, _ -> checked[i] }.toSet())
                Parental.unlocked = false
                Toast.makeText(this, R.string.parental_saved, Toast.LENGTH_SHORT).show()
                rebuild(keepScroll = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- update notice ----------
    /** Once a day: if a newer Faznet is published on GitHub, say so. */
    private fun checkForUpdate() {
        val prefs = getSharedPreferences("faznet_update", MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) < 24 * 3600_000L) return
        lifecycleScope.launch {
            val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val req = okhttp3.Request.Builder().url(UPDATE_URL).header("Accept", "application/vnd.github+json").build()
                    com.faznet.tv.net.Http.api.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null
                        else org.json.JSONObject(r.body?.string() ?: "").let { it.optString("tag_name") to it.optString("html_url") }
                    }
                }.getOrNull()
            } ?: return@launch
            prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
            val tag = latest.first.trimStart('v', 'V')
            if (!isNewer(tag, BuildConfig.VERSION_NAME) || prefs.getString("dismissed", "") == tag) return@launch
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.update_title)
                .setMessage(getString(R.string.update_msg, tag, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.update_open) { _, _ ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(latest.second))) }
                        .onFailure { Toast.makeText(this@MainActivity, R.string.update_no_browser, Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton(R.string.update_later) { _, _ -> prefs.edit().putString("dismissed", tag).apply() }
                .show()
        }
    }

    private fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    companion object {
        private const val UPDATE_URL = "https://api.github.com/repos/whatsthatbrother/faznet/releases/latest"
        const val KEY_ALL = "\u0000all"
        const val KEY_FAV = "\u0000fav"
        const val KEY_RECENT = "\u0000recent"
        private const val BACKGROUND_REFRESH_MS = 3 * 60 * 60 * 1000L
    }
}
