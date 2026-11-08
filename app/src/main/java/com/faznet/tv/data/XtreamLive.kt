package com.faznet.tv.data

import android.util.JsonReader
import android.util.JsonToken
import com.faznet.tv.data.db.ChannelKeys
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * The live channels of an Xtream account from its API (get_live_streams + get_live_categories) instead of its M3U list.
 * The M3U list also holds every movie and episode: on the test account 386,838 entries for 6,014 live channels, so
 * the download was some sixty times bigger than what Live TV shows. The JSON is read as a stream, never whole in memory.
 */
object XtreamLive {

    /** True when the downloaded file is the API's JSON array (else it is an M3U list). */
    fun isJson(file: File): Boolean {
        file.inputStream().buffered().use { input ->
            var b = input.read()
            // Spaces and a UTF-8 byte order mark may come first.
            while (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code || b == 0xEF || b == 0xBB || b == 0xBF) {
                b = input.read()
            }
            return b == '['.code
        }
    }

    /** The start of a download: the API's answer is a JSON array (an error page or an object is not). */
    fun looksLikeList(head: String): Boolean = head.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("[")

    /**
     * How the provider writes a live channel's address in its own M3U list: everything before the stream id and after
     * it. It has to be exactly that: on the test account the same channel played for minutes at the M3U's address but
     * stopped every 20 s at /live/user/pass/id.ts (the server sent broken audio there).
     */
    class Template(val prefix: String, val suffix: String) {
        fun url(streamId: String) = prefix + streamId + suffix
        fun encode() = "$prefix\n$suffix"

        companion object {
            fun decode(s: String?): Template? {
                val parts = s?.split('\n') ?: return null
                return if (parts.size == 2 && parts[0].startsWith("http")) Template(parts[0], parts[1]) else null
            }
        }
    }

    /** Without a template: the classic address of an M3U list with output=ts, http://server/user/pass/id. */
    fun classic(a: XtreamAccount) = Template("${a.server}/${a.user}/${a.pass}/", "")

    private val ADDRESS = Regex("""^(https?://.*/)(\d+)(\.[A-Za-z0-9]{1,5})?(\?.*)?$""")

    /**
     * Learns [Template] from the start of the account's M3U list: reads lines until [WANTED] live addresses with the
     * account in them were seen (a few KB, never the whole list with its movies) and takes the most common form, so a
     * stray entry such as an "Account information" clip does not decide it. Null when none was found.
     */
    fun learnTemplate(lines: Sequence<String>, a: XtreamAccount): Template? {
        val votes = HashMap<Pair<String, String>, Int>()
        var seen = 0
        val account = "/${a.user}/${a.pass}/"
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#' || !line.contains(account)) continue
            if (com.faznet.tv.data.db.ChannelImporter.isOnDemand(line)) continue
            val m = ADDRESS.matchEntire(line) ?: continue
            val key = m.groupValues[1] to (m.groupValues[3] + m.groupValues[4])
            votes[key] = (votes[key] ?: 0) + 1
            if (++seen >= WANTED) break
        }
        return votes.maxByOrNull { it.value }?.key?.let { (p, s) -> Template(p, s) }
    }

    /** Reads the start of [m3uUrl] for [learnTemplate]; at most [MAX_HEAD] bytes, null on any failure. */
    fun learnTemplate(m3uUrl: String, a: XtreamAccount, userAgent: String): Template? = runCatching {
        val req = okhttp3.Request.Builder().url(m3uUrl).header("User-Agent", userAgent).build()
        com.faznet.tv.net.Http.api.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val reader = java.io.BufferedReader(InputStreamReader(LimitedStream(r.body!!.byteStream(), MAX_HEAD), Charsets.UTF_8))
            learnTemplate(generateSequence { reader.readLine() }, a)
        }
    }.getOrNull()

    private const val WANTED = 40
    private const val MAX_HEAD = 512L * 1024

    /** Stops reading after [max] bytes: the rest of a big list is never downloaded. */
    private class LimitedStream(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else inner.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = inner.close()
    }

    /**
     * Calls [sink] for every live channel, in the server's order (its own numbering), numbered from 1.
     * @param categories category id → name; a channel whose category is unknown goes to [defaultGroup]
     * @return how many channels there were
     */
    fun parse(
        input: InputStream, a: XtreamAccount, categories: Map<String, String>, defaultGroup: String,
        template: Template = classic(a), sink: (Channel) -> Unit,
    ): Int {
        var count = 0
        JsonReader(InputStreamReader(input.buffered(64 * 1024), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                var id: String? = null
                var name = ""
                var icon: String? = null
                var category: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "stream_id" -> id = string(r)
                        "name" -> name = string(r).orEmpty()
                        "stream_icon" -> icon = string(r)?.takeIf { it.isNotBlank() }
                        "category_id" -> category = string(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val sid = id?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: continue
                count++
                val title = ChannelKeys.plainName(name.trim()).ifEmpty { sid }
                sink(Channel(count, title, template.url(sid), icon, category?.let { categories[it] } ?: defaultGroup, null, null))
            }
            r.endArray()
        }
        return count
    }

    /** A value as text: servers send numbers sometimes as strings, sometimes as numbers, and null. */
    private fun string(r: JsonReader): String? = when (r.peek()) {
        JsonToken.NULL -> { r.nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        else -> { r.skipValue(); null }
    }
}
