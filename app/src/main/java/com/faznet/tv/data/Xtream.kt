package com.faznet.tv.data

import android.util.Base64
import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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

    private suspend fun call(a: XtreamAccount, action: String, vararg params: Pair<String, String>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val b = "${a.server}/player_api.php".toHttpUrlOrNull()?.newBuilder() ?: return@runCatching null
                b.addQueryParameter("username", a.user).addQueryParameter("password", a.pass)
                if (action.isNotEmpty()) b.addQueryParameter("action", action)
                params.forEach { (k, v) -> b.addQueryParameter(k, v) }
                val req = Request.Builder().url(b.build()).header("User-Agent", com.faznet.tv.data.Repository.DEFAULT_UA).build()
                Http.api.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
        }

    private fun arr(s: String?): JSONArray = runCatching { JSONArray(s ?: "[]") }.getOrElse { JSONArray() }
    private fun JSONObject.str(k: String) = optString(k).takeUnless { it == "null" } ?: ""

    suspend fun vodCategories(a: XtreamAccount) = categories(call(a, "get_vod_categories"))
    suspend fun seriesCategories(a: XtreamAccount) = categories(call(a, "get_series_categories"))

    private fun categories(s: String?): List<XCategory> {
        val j = arr(s)
        return (0 until j.length()).mapNotNull { i ->
            j.optJSONObject(i)?.let { XCategory(it.str("category_id"), it.str("category_name")) }
        }
    }

    suspend fun vodStreams(a: XtreamAccount, categoryId: String?): List<XVod> {
        val j = arr(if (categoryId != null) call(a, "get_vod_streams", "category_id" to categoryId) else call(a, "get_vod_streams"))
        return (0 until j.length()).mapNotNull { i ->
            val o = j.optJSONObject(i) ?: return@mapNotNull null
            XVod(o.str("stream_id"), o.str("name"), o.str("stream_icon").ifEmpty { null },
                o.str("container_extension").ifEmpty { "mp4" }, o.str("rating"), o.str("year"))
        }
    }

    suspend fun series(a: XtreamAccount, categoryId: String?): List<XSeries> {
        val j = arr(if (categoryId != null) call(a, "get_series", "category_id" to categoryId) else call(a, "get_series"))
        return (0 until j.length()).mapNotNull { i ->
            val o = j.optJSONObject(i) ?: return@mapNotNull null
            XSeries(o.str("series_id"), o.str("name"), o.str("cover").ifEmpty { null }, o.str("plot"), o.str("rating"))
        }
    }

    suspend fun episodes(a: XtreamAccount, seriesId: String): List<XEpisode> {
        val root = runCatching { JSONObject(call(a, "get_series_info", "series_id" to seriesId) ?: "{}") }.getOrElse { JSONObject() }
        val eps = root.optJSONObject("episodes") ?: return emptyList()
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
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }

    /** Live channels that have a catch-up archive: stream id → number of days kept. */
    suspend fun archiveDays(a: XtreamAccount): Map<String, Int> {
        val j = arr(call(a, "get_live_streams"))
        val out = HashMap<String, Int>()
        for (i in 0 until j.length()) {
            val o = j.optJSONObject(i) ?: continue
            if (o.optInt("tv_archive") == 1) out[o.str("stream_id")] = o.optInt("tv_archive_duration", 1).coerceAtLeast(1)
        }
        return out
    }

    /** The day's programme of one live channel (past programmes marked when they are in the archive). */
    suspend fun guide(a: XtreamAccount, streamId: String): List<XProgramme> {
        val root = runCatching { JSONObject(call(a, "get_simple_data_table", "stream_id" to streamId) ?: "{}") }.getOrElse { JSONObject() }
        val list = root.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<XProgramme>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val start = o.str("start_timestamp").toLongOrNull() ?: continue
            val end = o.str("stop_timestamp").toLongOrNull() ?: continue
            out.add(XProgramme(b64(o.str("title")), b64(o.str("description")), start * 1000, end * 1000, o.optInt("has_archive") == 1))
        }
        return out.sortedBy { it.start }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: IllegalArgumentException) {
        s
    }

    /** The server's time zone (catch-up start times are written in it). */
    suspend fun serverTimeZone(a: XtreamAccount): TimeZone {
        val tz = runCatching { JSONObject(call(a, "") ?: "{}").optJSONObject("server_info")?.str("timezone") }.getOrNull()
        return if (tz.isNullOrEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(tz)
    }

    fun vodUrl(a: XtreamAccount, v: XVod) = "${a.server}/movie/${a.user}/${a.pass}/${v.id}.${v.ext}"
    fun episodeUrl(a: XtreamAccount, e: XEpisode) = "${a.server}/series/${a.user}/${a.pass}/${e.id}.${e.ext}"

    /** Catch-up of a past programme (Xtream "timeshift"): start time in the server's format, length in minutes. */
    fun catchupUrl(a: XtreamAccount, streamId: String, p: XProgramme, serverTz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = serverTz }
        val minutes = ((p.end - p.start) / 60_000).coerceAtLeast(1)
        return "${a.server}/timeshift/${a.user}/${a.pass}/$minutes/${f.format(p.start)}/$streamId.ts"
    }
}
