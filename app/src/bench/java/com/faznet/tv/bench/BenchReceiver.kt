package com.faznet.tv.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.faznet.tv.data.M3uParser
import com.faznet.tv.data.Playlist
import com.faznet.tv.data.Repository
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

/**
 * Measuring build only (-Pbench). Lists come from the PC through `adb reverse` (tools/bench.ps1):
 *   add   makes a list the active playlist of this app, then the real screens can be measured with it
 *   data  times reading the list without any screen, so it can run while the stick is in use
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        val list = i.getStringExtra("list") ?: return
        val mode = i.getStringExtra("mode") ?: "add"
        val rounds = i.getIntExtra("rounds", 3)
        thread(name = "bench") {
            runCatching {
                val file = fetch(app, list)
                if (mode == "data") data(app, file, rounds) else add(app, file)
            }.onFailure { Log.e(TAG, "FAILED", it) }
            Log.i(TAG, "DONE $mode $list")
        }
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

    private fun heapMb(): Double {
        repeat(2) { System.gc(); System.runFinalization(); Thread.sleep(150) }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    }

    private fun data(c: Context, file: File, rounds: Int) {
        Log.i(TAG, "=== ${file.name} ${file.length() / 1024} KB")
        val repo = Repository.get(c)
        val heap0 = heapMb()
        var count = 0
        repeat(rounds) { n ->
            count = timed("parse #${n + 1}") {
                file.inputStream().buffered(256 * 1024).reader(Charsets.UTF_8).buffered(256 * 1024).use { M3uParser.parse(it, "Other") }.size
            }
        }
        Log.i(TAG, "channels $count")
        // First load parses and writes the device copy; a new process would read that copy.
        val p = Playlist("benchdata", file.name, Playlist.TYPE_FILE, file.name)
        file.copyTo(repo.cacheFile(p.id), overwrite = true)
        val list = timed("repository first load") { runBlocking { repo.loadChannels(p, false) } }
        val heap1 = heapMb()
        Log.i(TAG, "heap with the list +%.1f MB (%d channels)".format(heap1 - heap0, list.size))
        repo.removePlaylist(p.id)
    }

    private companion object {
        const val TAG = "FZB"
    }
}
