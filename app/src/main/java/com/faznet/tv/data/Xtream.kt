package com.faznet.tv.data

import android.util.Base64
import com.faznet.tv.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** Login of an Xtream Codes account: from an Xtream playlist, or from an M3U link of the get.php kind. */
data class XtreamAccount(val server: String, val user: String, val pass: String) {
    companion object {
        fun from(p: Playlist?): XtreamAccount? {
            p ?: return null
            if (p.type == Playlist.TYPE_XTREAM && p.username.isNotEmpty())
                return XtreamAccount(Playlist.normalizeServer(p.source), p.username, p.password)
            if (p.type == Playlist.TYPE_URL) {
                val url = p.source.toHttpUrlOrNull() ?: return null
                val u = url.queryParameter("username") ?: return null
                val pw = url.queryParameter("password") ?: return null
                val base = url.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')
                return XtreamAccount(base, u, pw)
            }
            return null
        }

        /** "http://host/[live/]user/pass/123[.ts]" → stream id 123 (live channels of an Xtream account). */
        private val LIVE_ID = Regex("""/(\d+)(?:\.[A-Za-z0-9]+)?$""")
        fun liveStreamId(url: String): String? = LIVE_ID.find(url.substringBefore('?'))?.groupValues?.get(1)
    }
}

data class XCategory(val id: String, val name: String)
data class XVod(val id: String, val name: String, val icon: String?, val ext: String, val rating: String, val year: String)
data class XSeries(val id: String, val name: String, val cover: String?, val plot: String, val rating: String)
data class XEpisode(val id: String, val season: Int, val number: Int, val title: String, val ext: String, val plot: String)
data class XProgramme(val title: String, val desc: String, val start: Long, val end: Long, val hasArchive: Boolean)

object XtreamApi {

    /**
     * The User-Agent of every API request: the one chosen in Settings, as for the playlist and the channels. Set by
     * FaznetApp. A provider that only answers that one used to show Live TV but no movies, series or guide.
     */
    @Volatile var userAgent: () -> String = { Repository.DEFAULT_UA }

    private fun request(a: XtreamAccount, action: String, params: Array<out Pair<String, String>>): Request? {
        val b = "${a.server}/player_api.php".toHttpUrlOrNull()?.newBuilder() ?: return null
        b.addQueryParameter("username", a.user).addQueryParameter("password", a.pass)
        if (action.isNotEmpty()) b.addQueryParameter("action", action)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return Request.Builder().url(b.build()).header("User-Agent", userAgent()).build()
    }

    /**
     * One API request; [read] gets the answer as it arrives, on a background thread. Cancelling the caller (another
     * category chosen, screen closed) cancels the download too: a blocking OkHttp call would otherwise keep loading a
     * big list in the background and use one of the account's few connections.
     * @return null when the server could not be reached, did not say yes, or the answer broke off
     */
    private suspend fun <T> fetch(
        a: XtreamAccount, action: String, vararg params: Pair<String, String>, read: (java.io.Reader) -> T,
    ): T? {
        val req = request(a, action, params) ?: return null
        val http = Http.api.newCall(req)
        return coroutineScope {
            val result = async(Dispatchers.IO) {
                // Also an OutOfMemoryError: the screen then offers to try again instead of the app closing.
                runCatching {
                    http.execute().use { r -> if (r.isSuccessful) r.body?.charStream()?.let(read) else null }
                }.getOrNull()
            }
            try {
                result.await()
            } catch (e: CancellationException) {
                http.cancel()
                throw e
            }
        }
    }

    /** A small answer (categories, one series, one channel's guide) as text. */
    private suspend fun call(a: XtreamAccount, action: String, vararg params: Pair<String, String>): String? =
        fetch(a, action, *params) { it.readText() }

    /**
     * Reads an answer off the main thread: "all movies" or every live channel of a big account is several MB of JSON,
     * which held the screen still for seconds on the Mi TV Stick.
     */
    private suspend fun <T> parsed(body: String?, parse: (String?) -> T): T = withContext(Dispatchers.Default) { parse(body) }

    private fun arr(s: String?): JSONArray = runCatching { JSONArray(s ?: "[]") }.getOrElse { JSONArray() }
    private fun obj(s: String?): JSONObject = runCatching { JSONObject(s ?: "{}") }.getOrElse { JSONObject() }
    private fun JSONObject.str(k: String) = optString(k).takeUnless { it == "null" } ?: ""

    /** Live categories in the server's order, or null when the account did not answer with a list (fall back to M3U). */
    suspend fun liveCategories(a: XtreamAccount): List<XCategory>? {
        val body = call(a, "get_live_categories") ?: return null
        return parsed(body) { s -> if (s?.trimStart()?.startsWith("[") == true) categories(s) else null }
    }

    /** The address of the whole live channel list (JSON), for downloading it into a file. */
    fun liveStreamsUrl(a: XtreamAccount): String? = "${a.server}/player_api.php".toHttpUrlOrNull()?.newBuilder()
        ?.addQueryParameter("username", a.user)?.addQueryParameter("password", a.pass)
        ?.addQueryParameter("action", "get_live_streams")?.build()?.toString()

    suspend fun vodCategories(a: XtreamAccount) = parsed(call(a, "get_vod_categories"), ::categories)
    suspend fun seriesCategories(a: XtreamAccount) = parsed(call(a, "get_series_categories"), ::categories)

    private fun categories(s: String?): List<XCategory> {
        val j = arr(s)
        return (0 until j.length()).mapNotNull { i ->
            j.optJSONObject(i)?.let { XCategory(it.str("category_id"), it.str("category_name")) }
        }
    }

    /**
     * Null when the server could not be reached: the screen says so and offers to try again, instead of "nothing here".
     * Read as it arrives: "all movies" of a big account is tens of MB, which as one String and a JSONArray did not fit
     * a TV stick's memory.
     */
    suspend fun vodStreams(a: XtreamAccount, categoryId: String?): List<XVod>? =
        if (categoryId != null) fetch(a, "get_vod_streams", "category_id" to categoryId, read = ::readVods)
        else fetch(a, "get_vod_streams", read = ::readVods)

    /** Null when the server could not be reached. Read as it arrives, like [vodStreams]. */
    suspend fun series(a: XtreamAccount, categoryId: String?): List<XSeries>? =
        if (categoryId != null) fetch(a, "get_series", "category_id" to categoryId, read = ::readSeries)
        else fetch(a, "get_series", read = ::readSeries)

    internal fun readVods(r: java.io.Reader): List<XVod> = XtreamJson.objects(r, VOD_FIELDS) { o ->
        XVod(o["stream_id"].orEmpty(), o["name"].orEmpty(), o["stream_icon"]?.ifEmpty { null },
            o["container_extension"]?.ifEmpty { null } ?: "mp4", o["rating"].orEmpty(), o["year"].orEmpty())
    }

    internal fun readSeries(r: java.io.Reader): List<XSeries> = XtreamJson.objects(r, SERIES_FIELDS) { o ->
        XSeries(o["series_id"].orEmpty(), o["name"].orEmpty(), o["cover"]?.ifEmpty { null }, o["plot"].orEmpty(), o["rating"].orEmpty())
    }

    /** Stream id → days kept, of the live channels that have a catch-up archive. */
    internal fun readArchive(r: java.io.Reader): Map<String, Int> {
        val out = HashMap<String, Int>()
        XtreamJson.objects(r, ARCHIVE_FIELDS) { o ->
            if (o["tv_archive"]?.toDoubleOrNull()?.toInt() == 1) {
                o["stream_id"]?.let { out[it] = (o["tv_archive_duration"]?.toDoubleOrNull()?.toInt() ?: 1).coerceAtLeast(1) }
            }
        }
        return out
    }

    private val VOD_FIELDS = setOf("stream_id", "name", "stream_icon", "container_extension", "rating", "year")
    private val SERIES_FIELDS = setOf("series_id", "name", "cover", "plot", "rating")
    private val ARCHIVE_FIELDS = setOf("stream_id", "tv_archive", "tv_archive_duration")

    /** Null when the server could not be reached. */
    suspend fun episodes(a: XtreamAccount, seriesId: String): List<XEpisode>? = parsed(call(a, "get_series_info", "series_id" to seriesId) ?: return null) { body ->
        val eps = obj(body).optJSONObject("episodes") ?: return@parsed emptyList()
        val out = ArrayList<XEpisode>()
        for (season in eps.keys()) {
            val list = eps.optJSONArray(season) ?: continue
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                out.add(
                    XEpisode(
                        o.str("id"), season.toIntOrNull() ?: o.optInt("season"), o.optInt("episode_num"),
                        o.str("title"), o.str("container_extension").ifEmpty { "mp4" },
                        o.optJSONObject("info")?.str("plot") ?: ""
                    )
                )
            }
        }
        out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    private class Archive(val at: Long, val days: Map<String, Int>)
    private val archives = ConcurrentHashMap<XtreamAccount, Archive>()
    private val timeZones = ConcurrentHashMap<XtreamAccount, TimeZone>()

    /**
     * Live channels that have a catch-up archive: stream id → number of days kept. This is the account's whole channel
     * list, so it is kept for a while instead of being downloaded again every time the guide opens.
     */
    suspend fun archiveDays(a: XtreamAccount): Map<String, Int> {
        archives[a]?.takeIf { System.currentTimeMillis() - it.at < ARCHIVE_TTL_MS }?.let { return it.days }
        // Read as it arrives, keeping only the three fields: the whole list was held twice in memory before.
        val days = fetch(a, "get_live_streams", read = ::readArchive) ?: return emptyMap()
        archives[a] = Archive(System.currentTimeMillis(), days)
        return days
    }

    /** The day's programme of one live channel (past programmes marked when they are in the archive). */
    suspend fun guide(a: XtreamAccount, streamId: String): List<XProgramme> = parsed(call(a, "get_simple_data_table", "stream_id" to streamId)) { body ->
        val list = obj(body).optJSONArray("epg_listings") ?: return@parsed emptyList()
        val out = ArrayList<XProgramme>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val start = o.str("start_timestamp").toLongOrNull() ?: continue
            val end = o.str("stop_timestamp").toLongOrNull() ?: continue
            out.add(XProgramme(b64(o.str("title")), b64(o.str("description")), start * 1000, end * 1000, o.optInt("has_archive") == 1))
        }
        out.sortedBy { it.start }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: IllegalArgumentException) {
        s
    }

    /** The server's time zone (catch-up start times are written in it). Asked once per account and app run. */
    suspend fun serverTimeZone(a: XtreamAccount): TimeZone {
        timeZones[a]?.let { return it }
        val body = call(a, "") ?: return TimeZone.getDefault() // no answer: ask again next time
        val tz = parsed(body) { obj(it).optJSONObject("server_info")?.str("timezone") }
        return (if (tz.isNullOrEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(tz)).also { timeZones[a] = it }
    }

    fun vodUrl(a: XtreamAccount, v: XVod) = "${a.server}/movie/${a.user}/${a.pass}/${v.id}.${v.ext}"
    fun episodeUrl(a: XtreamAccount, e: XEpisode) = "${a.server}/series/${a.user}/${a.pass}/${e.id}.${e.ext}"

    /** Catch-up of a past programme (Xtream "timeshift"): start time in the server's format, length in minutes. */
    fun catchupUrl(a: XtreamAccount, streamId: String, p: XProgramme, serverTz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = serverTz }
        val minutes = ((p.end - p.start) / 60_000).coerceAtLeast(1)
        return "${a.server}/timeshift/${a.user}/${a.pass}/$minutes/${f.format(p.start)}/$streamId.ts"
    }

    private const val ARCHIVE_TTL_MS = 30 * 60 * 1000L
}
