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

    /** Address of a live channel, as the M3U list of the same account writes it with output=ts. */
    fun url(a: XtreamAccount, streamId: String) = "${a.server}/live/${a.user}/${a.pass}/$streamId.ts"

    /**
     * Calls [sink] for every live channel, in the server's order (its own numbering), numbered from 1.
     * @param categories category id → name; a channel whose category is unknown goes to [defaultGroup]
     * @return how many channels there were
     */
    fun parse(
        input: InputStream, a: XtreamAccount, categories: Map<String, String>, defaultGroup: String, sink: (Channel) -> Unit,
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
                sink(Channel(count, title, url(a, sid), icon, category?.let { categories[it] } ?: defaultGroup, null, null))
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
