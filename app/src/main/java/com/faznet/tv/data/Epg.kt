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

    /** All the programmes the server sent (up to three), so "next" is still there after the first one ended. */
    private class Entry(val at: Long, val value: List<Programme>?)

    /** At most [MAX_ENTRIES] channels, the least recently asked go first: hours of zapping must not grow it forever. */
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }
    private const val MAX_ENTRIES = 400
    /**
     * http://host[/sub/path][/live]/user/pass/123[.ts][?…]: the address a channel of an Xtream account has, as the
     * provider's own M3U list writes it (the learnt [XtreamLive.Template] can have a sub-path and a query). The API is
     * asked where the stream is, so a provider under a sub-path gets it right.
     */
    private val XTREAM_LIVE =
        Regex("""^(https?://[^/?#]+(?:/[^?#]+?)??)(?:/live)?/([^/?#]+)/([^/?#]+)/(\d+)(?:\.[A-Za-z0-9]+)?(?:[?#].*)?$""")
    private const val TTL_MS = 5 * 60 * 1000L

    /** Where a channel's now/next is asked: the API base, the login and the stream id. */
    internal data class Target(val base: String, val user: String, val pass: String, val id: String)

    /**
     * From the channel's address; else, for a channel of an Xtream playlist ([account]) whose address has another
     * form, the account and the stream id at the end of the address. Null: no EPG for this channel.
     */
    internal fun target(url: String, account: XtreamAccount?): Target? {
        XTREAM_LIVE.matchEntire(url)?.destructured?.let { (base, user, pass, id) -> return Target(base, user, pass, id) }
        account ?: return null
        val id = XtreamAccount.liveStreamId(url) ?: return null
        return Target(account.server, account.user, account.pass, id)
    }

    fun supports(ch: Channel, account: XtreamAccount?): Boolean = target(ch.url, account) != null

    /** Cached value if fresh (null = unknown or no EPG). */
    fun cached(ch: Channel): NowNext? {
        val e = synchronized(cache) { cache[ch.url] } ?: return null
        return if (System.currentTimeMillis() - e.at < TTL_MS) e.value?.let { current(it) } else null
    }

    suspend fun nowNext(ch: Channel, account: XtreamAccount?, userAgent: String): NowNext? {
        val (base, user, pass, id) = target(ch.url, account) ?: return null
        synchronized(cache) { cache[ch.url] }?.let { e ->
            if (System.currentTimeMillis() - e.at < TTL_MS) return e.value?.let { current(it) }
        }
        val value = withContext(Dispatchers.IO) {
            try {
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

    internal fun parse(body: String): List<Programme>? {
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
        return list
    }

    /** Re-pick now/next from what we have, so a cached answer stays right as programmes change. */
    internal fun current(all: List<Programme>, t: Long = System.currentTimeMillis()): NowNext? {
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
