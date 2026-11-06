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

    internal fun format(info: Info) = "${info.type ?: ""}|${info.height}|${info.fixes}|${if (info.software) 1 else 0}"

    private fun update(c: Context, url: String, change: (Info) -> Info) {
        val old = get(c, url) ?: Info(null, 0, 0)
        val new = change(old)
        if (new == old) return
        val sp = prefs(c)
        if (sp.all.size > MAX_ENTRIES) sp.edit().clear().apply()
        sp.edit().putString(key(url), format(new)).apply()
    }

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

    @Synchronized
    fun add(c: Context, channel: String, what: String, at: Long = System.currentTimeMillis()) {
        val f = file(c)
        try {
            val lines = (if (f.exists()) f.readLines() else emptyList()).takeLast(MAX - 1)
            val line = "${fmt.format(Date(at))}  ${channel.take(40)}  —  $what".replace('\n', ' ')
            f.writeText((lines + line).joinToString("\n"))
        } catch (e: IOException) {
            return // the device's storage is full: not a reason to close the channel someone is watching
        }
        Reports.problemAdded(c)
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

    /** Channels the user may see now (locked groups removed until unlocked). */
    fun visible(c: Context, list: List<Channel>): List<Channel> {
        if (unlocked || !enabled(c)) return list
        val locked = lockedGroups(c)
        return list.filter { it.group !in locked }
    }
}

