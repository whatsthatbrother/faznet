package com.faznet.tv.data

import android.content.Context
import android.net.Uri
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
        forget(id)
        if (activeId == id) activeId = rest.firstOrNull()?.id
    }

    /** The copy of a playlist's file on the device. */
    private fun dropCache(id: String) {
        cacheFile(id).delete()
        deleteOldCopy(id)
    }

    /**
     * What was kept here for a playlist that is gone. Its list and favourites in the database are removed by
     * [ChannelRepository.cleanUp].
     */
    private fun forget(id: String) {
        dropCache(id)
        prefs.edit().remove("fav_$id").remove("recent_$id").remove("last_$id").apply()
    }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(v) = prefs.edit().putString(KEY_ACTIVE, v).apply()

    fun activePlaylist(): Playlist? {
        val all = playlists()
        return all.firstOrNull { it.id == activeId } ?: all.firstOrNull()?.also { activeId = it.id }
    }

    fun cacheFile(id: String) = File(cacheDir, "$id.m3u")

    /** The old parsed copy of a playlist's file, from before there was a database. */
    fun deleteOldCopy(id: String) {
        File(cacheDir, "$id.bin").delete()
    }

    suspend fun importFile(uri: Uri, id: String) = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        val tmp = File(cacheDir, "$id.tmp")
        try {
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(cacheFile(id))) throw IOException("Cannot save file")
        } finally {
            tmp.delete() // gone after a rename; after a failure, no half-copied file stays in the cache
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
        }

    /**
     * Downloads to the side and swaps the file in when it is whole, so a failed download changes nothing. What does not
     * begin like a playlist is dropped at once: the address of a channel never ends, and would fill the device.
     * @param keepGoing called now and then, and throws when the download is no longer wanted
     */
    fun download(start: String, dest: File, maxBytes: Long = MAX_PLAYLIST_BYTES, keepGoing: () -> Unit = {}) {
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
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val head = ByteArray(256)
                    var headSize = 0
                    while (headSize < head.size) {
                        val n = input.read(head, headSize, head.size - headSize)
                        if (n < 0) break
                        headSize += n
                    }
                    val start = String(head, 0, headSize)
                    if (!start.contains("#EXT", ignoreCase = true) && !start.contains("://")) {
                        throw IOException("Response is not an M3U playlist")
                    }
                    output.write(head, 0, headSize)
                    var total = headSize.toLong()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        keepGoing()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("Playlist too big")
                        output.write(buffer, 0, n)
                    }
                    val expected = if (conn.contentEncoding == null) conn.contentLength else -1
                    if (expected > 0 && total != expected.toLong()) throw IOException("Download cut off")
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Cannot save playlist")
        } finally {
            conn.disconnect()
            tmp.delete() // renamed by now if all went well
        }
    }

    /** Replace the server-managed playlists with [remote]; local ones are kept. @return true if anything changed. */
    fun mergeRemote(remote: List<Playlist>): Boolean {
        val current = playlists()
        val oldRemote = current.filter { it.isRemote }.associateBy { it.remoteId }
        val newRemote = remote.associateBy { it.remoteId }
        for ((rid, old) in oldRemote) {
            val now = newRemote[rid]
            if (now == null) {
                forget(old.id) // removed on the website
            } else if (now.source != old.source || now.username != old.username || now.password != old.password || now.type != old.type) {
                dropCache(old.id) // changed on the website: download it again, keep the favourites
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

    // ---------- favorites / recents / last from before the database: only read, once, by ChannelRepository ----------
    fun favorites(pid: String): Set<String> = prefs.getStringSet("fav_$pid", emptySet())!!.toSet()

    fun recents(pid: String): List<String> {
        val arr = JSONArray(prefs.getString("recent_$pid", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
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

    /** Skip the start screen (Live TV, Movies, Series) and open Live TV straight away. */
    var startWithLive: Boolean
        get() = prefs.getBoolean(KEY_START_LIVE, false)
        set(v) = prefs.edit().putBoolean(KEY_START_LIVE, v).apply()

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


    /** Send crash reports to the Faznet server automatically (no playlist data). */
    var autoReports: Boolean
        get() = prefs.getBoolean("auto_reports", true)
        set(v) = prefs.edit().putBoolean("auto_reports", v).apply()

    /** Look for a new Faznet on the website and offer it (downloaded in the background). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(v) = prefs.edit().putBoolean("auto_update", v).apply()

    var showStats: Boolean
        get() = prefs.getBoolean(KEY_STATS, false)
        set(v) = prefs.edit().putBoolean(KEY_STATS, v).apply()

    var aspectMode: Int
        get() = prefs.getInt(KEY_ASPECT, 0)
        set(v) = prefs.edit().putInt(KEY_ASPECT, v).apply()

    companion object {
        const val DEFAULT_UA = "Faznet/1.0 (Linux; Android)"
        /** A list of 175 000 channels is about 40 MB. */
        private const val MAX_PLAYLIST_BYTES = 500L * 1024 * 1024
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_ACTIVE = "active"
        private const val KEY_UA = "user_agent"
        private const val KEY_AUTOPLAY = "autoplay_last"
        private const val KEY_START_LIVE = "start_with_live"
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

/** What the home screen hands to the player when it opens a channel. */
object ChannelStore {
    var playlistId: String = ""
    var startChannel: Channel? = null
    /** The list the channel was opened from; the player zaps through it. */
    var scope: ChannelScope? = null
    /** Group it stands for (HomeViewModel.KEY_* or a group name); null for one that is no group, like search results. */
    var groupKey: String? = null
    var groupTitle: String = ""
    var lastPlayed: Channel? = null

    fun start(playlistId: String, channel: Channel, scope: ChannelScope, key: String?, title: String) {
        this.playlistId = playlistId
        startChannel = channel
        this.scope = scope
        groupKey = key
        groupTitle = title
    }

    /** Set by the player's "switch playlist": open the new playlist's last channel once it is loaded. */
    var autoPlayNext = false
}
