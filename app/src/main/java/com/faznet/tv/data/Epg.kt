package com.faznet.tv.data

import android.util.Base64
import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * "Now / next" programme info for Xtream Codes channels, from the provider's short EPG API
 * (player_api.php?action=get_short_epg). One small request per channel, cached for a few minutes.
 * Channels that are not Xtream live streams simply have no EPG.
 */
object Epg {

    class Programme(val title: String, val start: Long, val end: Long)
    class NowNext(val now: Programme?, val next: Programme?)

    private class Entry(val at: Long, val value: NowNext?)

    private val cache = HashMap<String, Entry>()
    private val XTREAM_LIVE = Regex("""^(https?://[^/]+)(?:/live)?/([^/]+)/([^/]+)/(\d+)(?:\.[A-Za-z0-9]+)?$""")
    private const val TTL_MS = 5 * 60 * 1000L

    fun supports(ch: Channel): Boolean = XTREAM_LIVE.matches(ch.url)

    /** Cached value if fresh (null = unknown or no EPG). */
    fun cached(ch: Channel): NowNext? {
        val e = synchronized(cache) { cache[ch.url] } ?: return null
        return if (System.currentTimeMillis() - e.at < TTL_MS) e.value?.let { current(it) } else null
    }

    suspend fun nowNext(ch: Channel, userAgent: String): NowNext? {
        val m = XTREAM_LIVE.matchEntire(ch.url) ?: return null
        synchronized(cache) { cache[ch.url] }?.let { e ->
            if (System.currentTimeMillis() - e.at < TTL_MS) return e.value?.let { current(it) }
        }
        val value = withContext(Dispatchers.IO) {
            try {
                val (base, user, pass, id) = m.destructured
                val url = "$base/player_api.php".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("username", user)
                    ?.addQueryParameter("password", pass)
                    ?.addQueryParameter("action", "get_short_epg")
                    ?.addQueryParameter("stream_id", id)
                    ?.addQueryParameter("limit", "3")
                    ?.build() ?: return@withContext null
                val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                Http.probe.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    parse(r.body?.string() ?: return@withContext null)
                }
            } catch (e: Exception) {
                null
            }
        }
        synchronized(cache) { cache[ch.url] = Entry(System.currentTimeMillis(), value) }
        return value?.let { current(it) }
    }

    private fun parse(body: String): NowNext? {
        val arr = JSONObject(body).optJSONArray("epg_listings") ?: return null
        val list = ArrayList<Programme>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp").toLongOrNull() ?: continue
            val end = o.optString("stop_timestamp").toLongOrNull() ?: continue
            list.add(Programme(decode(o.optString("title")), start * 1000, end * 1000))
        }
        if (list.isEmpty()) return null
        list.sortBy { it.start }
        return NowNext(list.getOrNull(0), list.getOrNull(1))
    }

    /** Re-pick now/next from what we have, so a cached answer stays right as programmes change. */
    private fun current(v: NowNext): NowNext? {
        val all = listOfNotNull(v.now, v.next)
        val t = System.currentTimeMillis()
        val i = all.indexOfFirst { t < it.end }
        if (i < 0) return null
        return NowNext(all[i], all.getOrNull(i + 1))
    }

    private fun decode(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: IllegalArgumentException) {
        s
    }.ifEmpty { "—" }
}
