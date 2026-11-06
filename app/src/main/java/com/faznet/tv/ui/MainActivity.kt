package com.faznet.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.faznet.tv.BuildConfig
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.DbErrors
import com.faznet.tv.data.Parental
import com.faznet.tv.data.Playlist
import com.faznet.tv.data.Repository
import com.faznet.tv.ui.compose.FaznetTheme
import com.faznet.tv.ui.compose.HomeActions
import com.faznet.tv.ui.compose.HomeScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), HomeActions {

    private lateinit var repo: Repository
    private val vm: HomeViewModel by viewModels()
    private val ui get() = vm.ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository.get(this)
        val wide = resources.getBoolean(R.bool.wide)
        val tv = isTv()
        ui.addPlaylist = { openPlaylists(add = true) }
        setContent { FaznetTheme { HomeScreen(ui, vm.pages, this, wide, tv) } }
        // The screen paints its own background: the window's would be one more full-screen fill for the TV's GPU.
        window.setBackgroundDrawable(null)

        // 3.0: "open the last channel on start" is off unless the user turns it on again in Settings.
        getSharedPreferences("faznet_migrate", MODE_PRIVATE).let { m ->
            if (!m.getBoolean("v30", false)) {
                repo.autoPlayLast = false
                m.edit().putBoolean("v30", true).apply()
            }
        }
        lifecycleScope.launch {
            vm.events.collect { e ->
                when (e) {
                    is HomeViewModel.Event.Toast ->
                        Toast.makeText(this@MainActivity, e.text, if (e.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                    is HomeViewModel.Event.AskPin -> askParentalPin { vm.unlockedOpen(e.group) }
                    is HomeViewModel.Event.Play -> startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
                }
            }
        }
        if (savedInstanceState == null) {
            vm.autoPlayOnce()
            checkForUpdate()
            lifecycleScope.launch {
                com.faznet.tv.data.Reports.sendPendingCrash(this@MainActivity, repo)
                com.faznet.tv.data.Reports.sendExits(this@MainActivity, repo)
                com.faznet.tv.data.Reports.sendNewProblems(this@MainActivity, repo)
            }
        }
        if (BuildConfig.DEBUG && repo.playlists().none { it.name == "Test2 (debug)" } &&
            runCatching { assets.open("test.m3u").close() }.isSuccess) addDebugTestPlaylist()
    }

    // ---------- HomeActions (called by the Compose screen) ----------
    override fun openChannel(channel: Channel) = vm.openChannel(channel)
    override fun toggleFavorite(ch: Channel) = vm.toggleFavorite(ch)
    override fun selectGroup(key: String, fromClick: Boolean) = vm.selectGroup(key, fromClick)
    override fun checkGroup(key: String) = confirmCheck(key)
    override fun playlists() = openPlaylists()
    override fun refresh() = vm.refreshAll()
    override fun debugPlaylist() { if (BuildConfig.DEBUG) addDebugTestPlaylist() }
    override fun device() = startActivity(Intent(this, DeviceActivity::class.java))
    override fun settings() = startActivity(Intent(this, SettingsActivity::class.java))
    override fun movies() = LibraryActivity.start(this, series = false)
    override fun series() = LibraryActivity.start(this, series = true)
    override fun guide() = GuideActivity.start(this)
    override fun update() = UpdateUi.run(this, interactive = true) { info -> ui.updateVersion = info.version }

    /** Search: a small dialog with a text field (works with the TV remote's keyboard); the list filters while typing. */
    override fun search() {
        val et = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            setText(ui.query)
            setSelection(text.length)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            doAfterTextChanged { vm.setQuery(it?.toString() ?: "") }
        }
        val d = MaterialAlertDialogBuilder(this)
            .setView(padded(et))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.clear) { _, _ -> vm.setQuery("") }
            .show()
        et.setOnEditorActionListener { _, _, _ -> d.dismiss(); true }
        et.requestFocus()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    override fun onResume() {
        super.onResume()
        UpdateUi.resume(this)
        checkForUpdate()
        vm.onResume()
    }

    override fun onPause() {
        super.onPause()
        vm.onPause()
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
        vm.reload()
    }

    /** Long-press on a group: test its channels one by one and dim the ones that don't answer. */
    private fun confirmCheck(key: String) {
        lifecycleScope.launch(DbErrors) {
            val (title, list) = vm.channelsForCheck(key) ?: return@launch
            if (list.isEmpty()) return@launch
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.check_title)
                .setMessage(getString(R.string.check_confirm, title, list.size))
                .setPositiveButton(R.string.check_start) { _, _ -> runCheck(list) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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
                if (!vm.checkChannel(ch)) bad++
                dialog.setMessage(getString(R.string.check_progress, i + 1, list.size, bad))
                delay(250) // gentle on the provider (connection limits)
            }
            dialog.dismiss()
            vm.checkProgress()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.check_title)
                .setMessage(getString(R.string.check_done, list.size - bad, bad))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        job?.invokeOnCompletion { vm.checkProgress() }
    }

    private fun openPlaylists(add: Boolean = false) {
        startActivity(Intent(this, PlaylistsActivity::class.java).putExtra(PlaylistsActivity.EXTRA_ADD, add))
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

    // ---------- update notice ----------
    /** Every start and every return to the home screen, at most every 30 minutes (version.json is tiny). */
    private fun checkForUpdate() {
        if (!repo.autoUpdate) return
        val prefs = getSharedPreferences("faznet_update", MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) < 30 * 60_000L) return
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
        UpdateUi.run(this, interactive = false) { info -> ui.updateVersion = info.version }
    }

    companion object {
        const val KEY_ALL = HomeViewModel.KEY_ALL
        const val KEY_FAV = HomeViewModel.KEY_FAV
        const val KEY_RECENT = HomeViewModel.KEY_RECENT
    }
}
