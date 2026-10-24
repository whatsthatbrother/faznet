package com.faznet.tv.data

import android.content.Context
import android.net.Uri
import com.faznet.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class Repository private constructor(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("faznet", Context.MODE_PRIVATE)
    private val cacheDir = File(ctx.filesDir, "playlists").apply { mkdirs() }
    private val isTv: Boolean by lazy {
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature("android.software.leanback")
    }

    // ---------- playlists ----------
    fun playlists(): List<Playlist> {
        val arr = JSONArray(prefs.getString(KEY_PLAYLISTS, "[]"))
        return (0 until arr.length()).map { Playlist.fromJson(arr.getJSONObject(it)) }
    }

    private fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun addPlaylist(p: Playlist) {
        savePlaylists(playlists() + p)
        activeId = p.id
    }

    fun removePlaylist(id: String) {
        val rest = playlists().filter { it.id != id }
        savePlaylists(rest)
        cacheFile(id).delete()
        binFile(id).delete()
        prefs.edit().remove("fav_$id").remove("recent_$id").remove("last_$id").apply()
        if (activeId == id) activeId = rest.firstOrNull()?.id
    }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(v) = prefs.edit().putString(KEY_ACTIVE, v).apply()

    fun activePlaylist(): Playlist? {
        val all = playlists()
        return all.firstOrNull { it.id == activeId } ?: all.firstOrNull()?.also { activeId = it.id }
    }

    fun cacheFile(id: String) = File(cacheDir, "$id.m3u")

    suspend fun loadChannels(p: Playlist, forceRefresh: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        val file = cacheFile(p.id)
        val url = p.m3uUrl()
        if (url != null && (forceRefresh || !file.exists() || file.length() == 0L)) {
            download(url, file)
        }
        if (!file.exists()) throw IOException("Playlist file missing")
        // Big lists (thousands of channels) are parsed once and kept in memory while the file is unchanged,
        // so coming back to the main screen or switching groups never re-parses them.
        val stamp = file.lastModified() xor file.length()
        parsed[p.id]?.let { (s, list) -> if (s == stamp) return@withContext list }
        // After a restart: the ready-made copy of the parsed list opens several times faster than the M3U text.
        val list = readBinary(p.id, stamp) ?: file.inputStream().buffered(256 * 1024).reader(Charsets.UTF_8).buffered(256 * 1024).use {
            M3uParser.parse(it, ctx.getString(R.string.group_uncategorized))
        }.also { writeBinary(p.id, stamp, it) }
        parsed[p.id] = stamp to list
        list
    }

    private fun binFile(id: String) = File(cacheDir, "$id.bin")

    private fun readBinary(id: String, stamp: Long): List<Channel>? = try {
        val f = binFile(id)
        if (!f.exists()) null
        else java.io.DataInputStream(f.inputStream().buffered(256 * 1024)).use { d ->
            if (d.readInt() != BIN_MAGIC || d.readLong() != stamp) return@use null
            val n = d.readInt()
            val out = ArrayList<Channel>(n)
            fun opt(): String? = if (d.readBoolean()) d.readUTF() else null
            repeat(n) {
                out.add(Channel(d.readInt(), d.readUTF(), d.readUTF(), opt(), d.readUTF(), opt(), opt()))
            }
            out
        }
    } catch (e: Exception) {
        binFile(id).delete()
        null
    }

    private fun writeBinary(id: String, stamp: Long, list: List<Channel>) {
        val f = binFile(id)
        val tmp = File(cacheDir, "$id.bin.part")
        try {
            java.io.DataOutputStream(tmp.outputStream().buffered(256 * 1024)).use { d ->
                fun utf(v: String) = d.writeUTF(if (v.length > 20_000) v.take(20_000) else v)
                fun opt(v: String?) { d.writeBoolean(v != null); if (v != null) utf(v) }
                d.writeInt(BIN_MAGIC)
                d.writeLong(stamp)
                d.writeInt(list.size)
                for (c in list) {
                    d.writeInt(c.number); utf(c.name); utf(c.url); opt(c.logo); utf(c.group); opt(c.userAgent); opt(c.referrer)
                }
            }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Channel>>>()

    /** How old the cached copy of a downloadable playlist is (Long.MAX_VALUE if there is none). */
    fun cacheAgeMs(p: Playlist): Long {
        val f = cacheFile(p.id)
        return if (p.m3uUrl() == null || !f.exists()) Long.MAX_VALUE else System.currentTimeMillis() - f.lastModified()
    }

    suspend fun importFile(uri: Uri, id: String) = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        val tmp = File(cacheDir, "$id.tmp")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (!tmp.renameTo(cacheFile(id))) throw IOException("Cannot save file")
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
        }

    private fun download(start: String, dest: File) {
        var url = start
        var conn = open(url)
        var redirects = 0
        var code = conn.responseCode
        while (code in 300..399 && redirects < 6) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            url = URL(URL(url), loc).toString()
            conn = open(url)
            code = conn.responseCode
            redirects++
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        } finally {
            conn.disconnect()
        }
        val head = tmp.inputStream().use { s -> ByteArray(256).let { b -> String(b, 0, maxOf(0, s.read(b))) } }
        if (!head.contains("#EXT", ignoreCase = true) && !head.contains("://")) {
            tmp.delete()
            throw IOException("Response is not an M3U playlist")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Cannot save playlist")
    }

    /** Replace the server-managed playlists with [remote]; local ones are kept. @return true if anything changed. */
    fun mergeRemote(remote: List<Playlist>): Boolean {
        val current = playlists()
        val oldRemote = current.filter { it.isRemote }.associateBy { it.remoteId }
        val newRemote = remote.associateBy { it.remoteId }
        for ((rid, old) in oldRemote) {
            val now = newRemote[rid]
            if (now == null) {
                cacheFile(old.id).delete()
            } else if (now.source != old.source || now.username != old.username || now.password != old.password || now.type != old.type) {
                cacheFile(old.id).delete()
            }
        }
        val merged = current.filter { !it.isRemote } + remote
        if (merged == current) return false
        savePlaylists(merged)
        if (merged.none { it.id == activeId }) activeId = merged.firstOrNull()?.id
        return true
    }

    // ---------- device identity for web upload ----------
    val deviceMac: String
        get() = prefs.getString(KEY_MAC, null) ?: stableIdentity().first

    val devicePin: String
        get() = prefs.getString(KEY_PIN, null) ?: stableIdentity().second

    /**
     * First start: MAC + PIN are derived from this device's Android id, so after uninstalling and installing
     * again the device keeps the same MAC + PIN and gets its playlists back from the website by itself.
     * (The Android id is per device and per app signing key; no other app can compute it.)
     */
    private fun stableIdentity(): Pair<String, String> {
        val aid = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (aid.isNullOrEmpty() || aid == "9774d56d682e549c") return newDeviceIdentity()
        val h = java.security.MessageDigest.getInstance("SHA-256").digest("faznet|$aid".toByteArray())
        h[0] = (h[0].toInt() and 0xFE).toByte() // unicast
        val mac = (0 until 6).joinToString(":") { "%02X".format(h[it].toInt() and 0xFF) }
        val pinNum = ((h[6].toInt() and 0xFF) shl 16 or ((h[7].toInt() and 0xFF) shl 8) or (h[8].toInt() and 0xFF)) % 10000
        val pin = "%04d".format(pinNum)
        prefs.edit().putString(KEY_MAC, mac).putString(KEY_PIN, pin).apply()
        return mac to pin
    }

    /** Creates a random MAC-style id (Android no longer exposes the real MAC) and a 4-digit PIN. */
    fun newDeviceIdentity(): Pair<String, String> {
        val rnd = java.security.SecureRandom()
        val bytes = ByteArray(6).also { rnd.nextBytes(it) }
        bytes[0] = (bytes[0].toInt() and 0xFE).toByte() // unicast
        val mac = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        val pin = "%04d".format(rnd.nextInt(10000))
        prefs.edit().putString(KEY_MAC, mac).putString(KEY_PIN, pin).apply()
        return mac to pin
    }

    var customServer: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SERVER, v.trim().trimEnd('/')).apply()

    /** Upload API. A placeholder default (not yet deployed) counts as "not set". */
    val serverUrl: String
        get() = customServer.ifEmpty { com.faznet.tv.BuildConfig.SERVER_URL }.trimEnd('/')
            .takeUnless { it.contains("REPLACE") } ?: ""

    /** Public upload website shown to the user (GitHub Pages). Falls back to the API host (Flask server serves its own page). */
    val webUrl: String
        get() = if (customServer.isNotEmpty()) customServer.trimEnd('/')
        else com.faznet.tv.BuildConfig.WEB_URL.trimEnd('/')

    var lastSync: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0)
        set(v) = prefs.edit().putLong(KEY_LAST_SYNC, v).apply()

    // ---------- favorites / recents / last ----------
    fun favorites(pid: String): Set<String> = prefs.getStringSet("fav_$pid", emptySet())!!.toSet()

    /** @return true if the channel is now a favorite */
    fun toggleFavorite(pid: String, url: String): Boolean {
        val set = favorites(pid).toMutableSet()
        val added = if (set.contains(url)) { set.remove(url); false } else { set.add(url); true }
        prefs.edit().putStringSet("fav_$pid", set).apply()
        return added
    }

    fun recents(pid: String): List<String> {
        val arr = JSONArray(prefs.getString("recent_$pid", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun markWatched(pid: String, url: String) {
        val list = (listOf(url) + recents(pid).filter { it != url }).take(40)
        prefs.edit()
            .putString("recent_$pid", JSONArray(list).toString())
            .putString("last_$pid", url)
            .apply()
    }

    fun lastChannel(pid: String): String? = prefs.getString("last_$pid", null)

    // ---------- settings ----------
    var customUserAgent: String
        get() = prefs.getString(KEY_UA, "") ?: ""
        set(v) = prefs.edit().putString(KEY_UA, v.trim()).apply()

    val userAgent: String get() = customUserAgent.ifEmpty { DEFAULT_UA }

    var autoPlayLast: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTOPLAY, v).apply()

    var useFfmpeg: Boolean
        get() = prefs.getBoolean(KEY_FFMPEG, true)
        set(v) = prefs.edit().putBoolean(KEY_FFMPEG, v).apply()

    /** Android TV tunneled playback: picture and sound go straight through the TV chip (smoother on Amlogic). */
    var tunneling: Boolean
        get() = prefs.getBoolean("tunneling", false)
        set(v) = prefs.edit().putBoolean("tunneling", v).apply()

    /** Software video decoder for SD channels (the Mi TV Stick's hardware decoder sometimes stalls on 576i). */
    var softwareSd: Boolean
        get() = prefs.getBoolean("software_sd", false)
        set(v) = prefs.edit().putBoolean("software_sd", v).apply()

    /** Player engine: false = ExoPlayer (Media3), true = VLC (more tolerant of broken IPTV streams). */
    var useVlc: Boolean
        // ExoPlayer is the default everywhere (it proved the most reliable on the Mi TV Stick); VLC is optional
        // and is also used by itself for single channels that ExoPlayer cannot play.
        get() = prefs.getBoolean("use_vlc", false)
        set(v) = prefs.edit().putBoolean("use_vlc", v).apply()

    /** Send crash reports to the Faznet server automatically (no playlist data). */
    var autoReports: Boolean
        get() = prefs.getBoolean("auto_reports", true)
        set(v) = prefs.edit().putBoolean("auto_reports", v).apply()

    var showStats: Boolean
        get() = prefs.getBoolean(KEY_STATS, false)
        set(v) = prefs.edit().putBoolean(KEY_STATS, v).apply()

    var aspectMode: Int
        get() = prefs.getInt(KEY_ASPECT, 0)
        set(v) = prefs.edit().putInt(KEY_ASPECT, v).apply()

    companion object {
        const val DEFAULT_UA = "Faznet/1.0 (Linux; Android)"
        private const val KEY_PLAYLISTS = "playlists"
        private const val BIN_MAGIC = 0x465A4E31 // "FZN1"
        private const val KEY_ACTIVE = "active"
        private const val KEY_UA = "user_agent"
        private const val KEY_AUTOPLAY = "autoplay_last"
        private const val KEY_ASPECT = "aspect"
        private const val KEY_FFMPEG = "ffmpeg"
        private const val KEY_STATS = "show_stats"
        private const val KEY_MAC = "device_mac"
        private const val KEY_PIN = "device_pin"
        private const val KEY_SERVER = "server_url"
        private const val KEY_LAST_SYNC = "last_sync"

        @Volatile private var instance: Repository? = null
        fun get(c: Context): Repository =
            instance ?: synchronized(this) { instance ?: Repository(c.applicationContext).also { instance = it } }
    }
}

/** In-memory hand-off of the channel list to the player (lists can be too large for an Intent). */
object ChannelStore {
    var playlistId: String = ""
    var all: List<Channel> = emptyList()
    var current: List<Channel> = emptyList()
    /** Group the player starts in (MainActivity.KEY_* or a group name); null = the `current` list as-is (search). */
    var groupKey: String? = null
    var lastPlayedUrl: String? = null
    /** Set by the player's "switch playlist": open the new playlist's last channel once it is loaded. */
    var autoPlayNext = false
}
