package com.faznet.tv.player

import com.faznet.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

enum class StreamType { HLS, DASH, PROGRESSIVE }

/**
 * Works out a stream's container before playback, so extension-less IPTV URLs open on the first try
 * instead of failing as TS and being retried as HLS.
 */
object StreamProbe {
    private val cache = ConcurrentHashMap<String, StreamType>()
    private val XTREAM_LIVE = Regex("""^[a-z]+://[^/]+(/live)?/[^/]+/[^/]+/\d+$""")

    fun guess(url: String): StreamType? {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains("/hls/") -> StreamType.HLS
            lower.contains(".m3u8") || lower.contains("type=m3u8") || lower.contains("output=m3u8") -> StreamType.HLS
            path.endsWith(".mpd") -> StreamType.DASH
            path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") ||
                path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".flv") || path.endsWith(".mov") ||
                lower.contains("output=ts") -> StreamType.PROGRESSIVE
            lower.startsWith("rtmp") || lower.startsWith("rtsp") || lower.startsWith("udp") -> StreamType.PROGRESSIVE
            // Xtream Codes live URLs: http://host/[live/]user/pass/12345 — MPEG-TS by default.
            // Never probe these: many panels allow a single connection and cut the stream if a second one appears.
            XTREAM_LIVE.containsMatchIn(path) -> StreamType.PROGRESSIVE
            else -> null
        }
    }

    suspend fun detect(url: String, userAgent: String, referer: String?): StreamType {
        guess(url)?.let { return it }
        cache[url]?.let { return it }
        val type = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", userAgent)
                    .apply { if (referer != null) header("Referer", referer) }
                    .build()
                Http.probe.newCall(req).execute().use { resp ->
                    guess(resp.request.url.toString())?.let { return@use it }
                    val ct = (resp.header("Content-Type") ?: "").lowercase()
                    val head = ByteArray(512)
                    var n = 0
                    resp.body?.byteStream()?.let { s ->
                        while (n < head.size) {
                            val r = s.read(head, n, head.size - n)
                            if (r <= 0) break
                            n += r
                        }
                    }
                    val text = String(head, 0, n, Charsets.ISO_8859_1)
                    when {
                        ct.contains("mpegurl") || text.trimStart().startsWith("#EXTM3U") -> StreamType.HLS
                        ct.contains("dash+xml") || text.contains("<MPD") -> StreamType.DASH
                        else -> StreamType.PROGRESSIVE
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        return if (type != null) {
            cache[url] = type
            type
        } else {
            StreamType.PROGRESSIVE
        }
    }

    fun remember(url: String, type: StreamType) {
        cache[url] = type
    }
}
