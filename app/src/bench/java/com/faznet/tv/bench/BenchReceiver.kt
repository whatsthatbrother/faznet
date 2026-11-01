package com.faznet.tv.bench

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.faznet.tv.data.M3uParser
import com.faznet.tv.data.Playlist
import com.faznet.tv.data.Repository
import com.faznet.tv.data.db.ChannelImporter
import com.faznet.tv.data.db.FaznetDatabase
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

/**
 * Measuring build only (-Pbench). Lists come from the PC through `adb reverse` (tools/bench.ps1):
 *   add   makes a list the active playlist of this app, then the real screens can be measured with it
 *   data  times reading the list without any screen, so it can run while the stick is in use
 *   hold  a key held down on the open screen for a while, as the remote does it (adb can't do that)
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        val mode = i.getStringExtra("mode") ?: "add"
        thread(name = "bench") {
            runCatching {
                when (mode) {
                    "hold" -> hold(i.getIntExtra("key", KeyEvent.KEYCODE_DPAD_DOWN), i.getIntExtra("ms", 5000))
                    else -> {
                        val file = fetch(app, i.getStringExtra("list") ?: return@runCatching)
                        if (mode == "data") data(app, file, i.getIntExtra("rounds", 3)) else add(app, file)
                    }
                }
            }.onFailure { Log.e(TAG, "FAILED", it) }
            Log.i(TAG, "DONE $mode")
        }
    }

    /** The system repeats a held key every 50 ms after 500 ms; this sends the same events into this app's window. */
    private fun hold(key: Int, ms: Int) {
        val i = Instrumentation()
        val down = SystemClock.uptimeMillis()
        i.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, key, 0))
        Thread.sleep(500)
        var repeat = 1
        while (SystemClock.uptimeMillis() - down < ms) {
            i.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, key, repeat++))
            Thread.sleep(50)
        }
        i.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
    }

    private fun fetch(c: Context, name: String): File {
        val f = File(c.filesDir, name)
        if (!f.exists()) URL("http://127.0.0.1:8765/$name").openStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    private fun add(c: Context, file: File) {
        val repo = Repository.get(c)
        val id = "bench" + file.name.hashCode().toString(36)
        file.copyTo(repo.cacheFile(id), overwrite = true)
        if (repo.playlists().none { it.id == id }) repo.addPlaylist(Playlist(id, file.name, Playlist.TYPE_FILE, file.name))
        repo.activeId = id
    }

    private inline fun <R> timed(label: String, block: () -> R): R {
        val w0 = SystemClock.elapsedRealtime()
        val c0 = SystemClock.currentThreadTimeMillis()
        val r = block()
        Log.i(TAG, "%-28s wall %6d ms   cpu %6d ms".format(label, SystemClock.elapsedRealtime() - w0, SystemClock.currentThreadTimeMillis() - c0))
        return r
    }

    private fun data(c: Context, file: File, rounds: Int) {
        Log.i(TAG, "=== ${file.name} ${file.length() / 1024} KB")
        var count = 0
        repeat(rounds) { n ->
            count = timed("parse #${n + 1}") { file.inputStream().use { M3uParser.parse(it, "Other") }.size }
        }
        Log.i(TAG, "channels $count")
        val db = FaznetDatabase.create(c)
        val importer = ChannelImporter(db)
        repeat(rounds) { n ->
            val r = timed("import #${n + 1}") { file.inputStream().use { importer.import("benchdata", it, "Other", false, "$n", null, 0) } }
            Log.i(TAG, "list ${r.listId}: ${r.channels} channels, ${r.groups} groups, database ${c.getDatabasePath("faznet.db").length() / 1024} KB")
        }
        db.close()
    }

    private companion object {
        const val TAG = "FZB"
    }
}
