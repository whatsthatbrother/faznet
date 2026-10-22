package com.faznet.tv.data

import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Quick "does this channel answer?" test, and the results for this session (shown dimmed in the lists). */
object ChannelCheck {
    private val dead = HashSet<String>()

    fun isDead(url: String) = synchronized(dead) { url in dead }

    /** @return true if the server answers with stream data within a few seconds. */
    suspend fun check(ch: Channel, userAgent: String): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            val req = Request.Builder().url(ch.url).header("User-Agent", ch.userAgent ?: userAgent)
                .apply { ch.referrer?.let { header("Referer", it) } }
                .build()
            Http.probe.newCall(req).execute().use { r ->
                if (!r.isSuccessful) false
                else {
                    val buf = ByteArray(2048)
                    (r.body?.byteStream()?.read(buf) ?: -1) > 0
                }
            }
        } catch (e: Exception) {
            false
        }
        synchronized(dead) { if (ok) dead.remove(ch.url) else dead.add(ch.url) }
        ok
    }
}
