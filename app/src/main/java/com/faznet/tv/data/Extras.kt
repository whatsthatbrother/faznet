package com.faznet.tv.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app learned about each channel, kept across restarts:
 * stream type (so it is never probed again), picture height, and how often it needed an auto-fix.
 */
object ChannelMemory {
    private const val FILE = "channel_memory"
    private const val MAX_ENTRIES = 3000

    class Info(val type: String?, val height: Int, val fixes: Int)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(url: String) = url.hashCode().toString(36) + "_" + url.length

    fun get(c: Context, url: String): Info? {
        val raw = prefs(c).getString(key(url), null) ?: return null
        val p = raw.split('|')
        return Info(p.getOrNull(0)?.ifEmpty { null }, p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0)
    }

    private fun put(c: Context, url: String, info: Info) {
        val sp = prefs(c)
        if (sp.all.size > MAX_ENTRIES) sp.edit().clear().apply()
        sp.edit().putString(key(url), "${info.type ?: ""}|${info.height}|${info.fixes}").apply()
    }

    fun rememberType(c: Context, url: String, type: String) {
        val old = get(c, url)
        if (old?.type == type) return
        put(c, url, Info(type, old?.height ?: 0, old?.fixes ?: 0))
    }

    fun rememberHeight(c: Context, url: String, height: Int) {
        val old = get(c, url)
        if (height <= 0 || old?.height == height) return
        put(c, url, Info(old?.type, height, old?.fixes ?: 0))
    }

    fun addFix(c: Context, url: String) {
        val old = get(c, url)
        put(c, url, Info(old?.type, old?.height ?: 0, ((old?.fixes ?: 0) + 1).coerceAtMost(99)))
    }

    // ---- channels whose first opening freezes (the provider starts them badly): open them twice at once ----
    private fun startPrefs(c: Context) = c.applicationContext.getSharedPreferences("start_freeze", Context.MODE_PRIVATE)

    fun startFreezes(c: Context, url: String): Int = startPrefs(c).getInt(key(url), 0)

    fun addStartFreeze(c: Context, url: String) {
        val sp = startPrefs(c)
        if (sp.all.size > MAX_ENTRIES) sp.edit().clear().apply()
        sp.edit().putInt(key(url), (startFreezes(c, url) + 1).coerceAtMost(5)).apply()
    }

    fun cleanStart(c: Context, url: String) {
        val n = startFreezes(c, url)
        if (n > 0) startPrefs(c).edit().putInt(key(url), n - 1).apply()
    }

    /** A channel that played cleanly several times is forgiven. */
    fun clearFixes(c: Context, url: String) {
        val old = get(c, url) ?: return
        if (old.fixes > 0) put(c, url, Info(old.type, old.height, 0))
    }
}

/** The last problems seen in the player (auto-fixes, refused channels, errors), viewable in Settings. */
object ProblemLog {
    private const val MAX = 50
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    private fun file(c: Context) = File(c.applicationContext.filesDir, "problems.log")

    @Synchronized
    fun add(c: Context, channel: String, what: String) {
        val f = file(c)
        val lines = (if (f.exists()) f.readLines() else emptyList()).takeLast(MAX - 1)
        val line = "${fmt.format(Date())}  ${channel.take(40)}  —  $what".replace('\n', ' ')
        f.writeText((lines + line).joinToString("\n"))
    }

    @Synchronized
    fun read(c: Context): List<String> = file(c).let { if (it.exists()) it.readLines().reversed() else emptyList() }

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
