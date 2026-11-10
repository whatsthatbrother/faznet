package com.faznet.tv.data

import android.content.Context
import android.database.sqlite.SQLiteException
import com.faznet.tv.Perf
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * For the screens' coroutines that use the database: with the device's storage full or failing, a read or a save does
 * not happen and the screen carries on without it. Anything else is a bug, and closes the app as it would without this.
 */
val DbErrors = CoroutineExceptionHandler { _, e ->
    if (e is SQLiteException) Perf.log("database: ${e.javaClass.simpleName}")
    else Thread.currentThread().let { it.uncaughtExceptionHandler?.uncaughtException(it, e) }
}

/**
 * What the app learned about each channel, kept across restarts: stream type (so it is never probed again), picture
 * height, how often it needed an auto-fix, and whether the hardware video decoder keeps getting stuck on it.
 */
object ChannelMemory {
    private const val FILE = "channel_memory"
    private const val MAX_ENTRIES = 3000

    /** [software]: open this channel with the software video decoder, whatever its size. */
    data class Info(val type: String?, val height: Int, val fixes: Int, val software: Boolean = false)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(url: String) = url.hashCode().toString(36) + "_" + url.length

    fun get(c: Context, url: String): Info? {
        val raw = prefs(c).getString(key(url), null) ?: return null
        return parse(raw)
    }

    /** "type|height|fixes|software"; entries written before the last field existed have three. */
    internal fun parse(raw: String): Info {
        val p = raw.split('|')
        return Info(
            p.getOrNull(0)?.ifEmpty { null }, p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0,
            p.getOrNull(3) == "1",
        )
    }

    /** Written with the time of the change (minutes), so the oldest can make room: "type|height|fixes|software|minute". */
    internal fun format(info: Info, minute: Long = System.currentTimeMillis() / 60_000) =
        "${info.type ?: ""}|${info.height}|${info.fixes}|${if (info.software) 1 else 0}|$minute"

    /** When it was last changed; 0 for entries from versions before the time was kept (they go first). */
    internal fun minute(raw: String): Long = raw.split('|').getOrNull(4)?.toLongOrNull() ?: 0

    /** The number of entries, counted once: before, every change copied the whole map ([SharedPreferences.getAll]). */
    @Volatile private var count = -1

    @Synchronized
    private fun update(c: Context, url: String, change: (Info) -> Info) {
        val old = get(c, url) ?: Info(null, 0, 0)
        val new = change(old)
        if (new == old) return
        val sp = prefs(c)
        val k = key(url)
        if (count < 0) count = sp.all.size
        if (!sp.contains(k)) count++
        val edit = sp.edit()
        // Full: the channels changed longest ago make room. Before, everything learnt (decoder choices too) was wiped.
        if (count > MAX_ENTRIES) {
            val drop = oldest(sp.all, MAX_ENTRIES / 10)
            drop.forEach { edit.remove(it) }
            count -= drop.size
        }
        edit.putString(k, format(new)).apply()
    }

    /** The [n] keys changed longest ago. */
    internal fun oldest(all: Map<String, *>, n: Int): List<String> =
        all.entries.sortedBy { (it.value as? String)?.let(::minute) ?: 0 }.take(n).map { it.key }

    fun rememberType(c: Context, url: String, type: String) = update(c, url) { it.copy(type = type) }

    fun rememberHeight(c: Context, url: String, height: Int) {
        if (height > 0) update(c, url) { it.copy(height = height) }
    }

    fun addFix(c: Context, url: String) = update(c, url) { it.copy(fixes = (it.fixes + 1).coerceAtMost(99)) }

    /** The hardware decoder kept getting stuck on this channel (or, with false, the software one did too). */
    fun useSoftware(c: Context, url: String, on: Boolean) = update(c, url) { it.copy(software = on) }
}

/** The last problems seen in the player (auto-fixes, refused channels, errors), viewable in Settings. */
object ProblemLog {
    private const val MAX = 50
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    private fun file(c: Context) = File(c.applicationContext.filesDir, "problems.log")

    /**
     * Called from the player at the moment of a freeze or an error: the file is read and written on a thread of its
     * own, in the order the problems came, not on the main thread while the picture is being fixed.
     */
    fun add(c: Context, channel: String, what: String, at: Long = System.currentTimeMillis()) {
        val app = c.applicationContext
        val line = "${synchronized(fmt) { fmt.format(Date(at)) }}  ${channel.take(40)}  —  $what".replace('\n', ' ')
        io.execute { if (write(app, line)) Reports.problemAdded(app) }
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "problem-log").apply { isDaemon = true } }

    @Synchronized
    internal fun write(c: Context, line: String): Boolean {
        val f = file(c)
        return try {
            val lines = (if (f.exists()) f.readLines() else emptyList()).takeLast(MAX - 1)
            f.writeText((lines + line).joinToString("\n"))
            true
        } catch (e: IOException) {
            false // the device's storage is full: not a reason to close the channel someone is watching
        }
    }

    @Synchronized
    fun read(c: Context): List<String> = try {
        file(c).let { if (it.exists()) it.readLines().reversed() else emptyList() }
    } catch (e: IOException) {
        emptyList()
    }

    @Synchronized
    fun clear(c: Context) {
        file(c).delete()
    }
}

/** Parental lock: chosen groups need the parental PIN once per app session. */
object Parental {
    private const val FILE = "parental"
    @Volatile var unlocked = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun pin(c: Context): String = prefs(c).getString("pin", "") ?: ""
    fun setPin(c: Context, pin: String) = prefs(c).edit().putString("pin", pin).apply()

    fun lockedGroups(c: Context): Set<String> = prefs(c).getStringSet("groups", emptySet())!!.toSet()
    fun setLockedGroups(c: Context, groups: Set<String>) = prefs(c).edit().putStringSet("groups", groups).apply()

    fun enabled(c: Context) = pin(c).length == 4 && lockedGroups(c).isNotEmpty()

    /** True if this group must stay hidden right now. */
    fun isLocked(c: Context, group: String) = !unlocked && enabled(c) && group in lockedGroups(c)
}

