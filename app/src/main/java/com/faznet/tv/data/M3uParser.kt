package com.faznet.tv.data

import java.io.InputStream

/**
 * Tolerant parser for extended M3U playlists (#EXTINF, #EXTGRP, #EXTVLCOPT, Kodi-style "url|User-Agent=...").
 *
 * It works on the raw bytes and streams: a String is made only for the fields that are kept, and every group name
 * exists once. On the Mi TV Stick that reads 50 000 channels in about 1 s (the regular-expression version it
 * replaced took 8 s), and channels can be stored while the file is still downloading.
 */
object M3uParser {

    fun parse(input: InputStream, defaultGroup: String): List<Channel> {
        val out = ArrayList<Channel>(1024)
        parse(input, defaultGroup) { out.add(it) }
        return out
    }

    /** Hands every channel to [sink] as soon as its address line is read. @return how many there were. */
    fun parse(input: InputStream, defaultGroup: String, sink: (Channel) -> Unit): Int {
        val state = State(defaultGroup, sink)
        var buf = ByteArray(256 * 1024)
        var len = 0
        while (true) {
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) break
            var i = len // only the new bytes can hold a line break
            len += n
            var start = 0
            while (i < len) {
                val b = buf[i]
                if (b == LF || b == CR) {
                    state.line(buf, start, i)
                    start = i + 1
                }
                i++
            }
            if (start > 0) {
                len -= start
                System.arraycopy(buf, start, buf, 0, len)
            }
            if (len == buf.size) buf = buf.copyOf(buf.size * 2) // one very long line
        }
        state.line(buf, 0, len) // the last line may have no line break
        return state.count
    }

    private const val LF = '\n'.code.toByte()
    private const val CR = '\r'.code.toByte()
    private const val HASH = '#'.code.toByte()
    private const val COLON = ':'.code.toByte()
    private const val QUOTE = '"'.code.toByte()
    private const val COMMA = ','.code.toByte()
    private const val EQUALS = '='.code.toByte()

    private class State(val defaultGroup: String, val sink: (Channel) -> Unit) {
        var count = 0
        private val groups = HashMap<String, String>()
        private var name: String? = null
        private var logo: String? = null
        private var group: String? = null
        private var extGroup: String? = null
        private var ua: String? = null
        private var ref: String? = null

        fun line(b: ByteArray, from: Int, to: Int) {
            var a = from
            var e = to
            if (e - a >= 3 && b[a] == 0xEF.toByte() && b[a + 1] == 0xBB.toByte() && b[a + 2] == 0xBF.toByte()) a += 3 // BOM
            while (a < e && isSpace(b[a])) a++
            while (e > a && isSpace(b[e - 1])) e--
            if (a >= e) return
            if (b[a] != HASH) address(text(b, a, e))
            else if (starts(b, a, e, "#EXTINF")) extInf(b, a + 7, e)
            else if (starts(b, a, e, "#EXTGRP:")) extGroup = text(b, a + 8, e).trim().ifEmpty { null }?.let(::shared)
            else if (starts(b, a, e, "#EXTVLCOPT:")) vlcOption(text(b, a + 11, e))
            // other directives (#EXTM3U, #KODIPROP, ...) are ignored
        }

        private fun extInf(b: ByteArray, from: Int, end: Int) {
            logo = null; group = null; ua = null; ref = null
            name = null
            var body = from
            while (body < end && b[body] != COLON) body++
            body++ // what follows the first ':'; nothing when there is none
            if (body > end) return
            // The comma between the attributes and the display name: the first one outside quotes.
            var inQuote = false
            var comma = -1
            var i = body
            while (i < end) {
                val c = b[i]
                if (c == QUOTE) inQuote = !inQuote
                else if (c == COMMA && !inQuote) { comma = i; break }
                i++
            }
            val attrEnd = if (comma >= 0) comma else end
            if (comma >= 0) name = text(b, comma + 1, end).trim()
            // key="value" pairs, with or without spaces around the '='
            i = body
            while (i < attrEnd) {
                if (b[i] != EQUALS) { i++; continue }
                var q = i + 1
                while (q < attrEnd && isSpace(b[q])) q++
                if (q >= attrEnd || b[q] != QUOTE) { i++; continue }
                var close = q + 1
                while (close < attrEnd && b[close] != QUOTE) close++
                if (close >= attrEnd) return
                var keyEnd = i
                while (keyEnd > body && isSpace(b[keyEnd - 1])) keyEnd--
                var keyStart = keyEnd
                while (keyStart > body && isKeyChar(b[keyStart - 1])) keyStart--
                if (keyEnd > keyStart && close > q + 1) attribute(b, keyStart, keyEnd - keyStart, q + 1, close)
                i = close + 1
            }
        }

        private fun attribute(b: ByteArray, key: Int, keyLen: Int, from: Int, to: Int) {
            when (keyLen) {
                4 -> if (same(b, key, "logo")) value(b, from, to)?.let { logo = it }
                7 -> if (same(b, key, "referer")) value(b, from, to)?.let { ref = it }
                8 -> when {
                    same(b, key, "tvg-logo") -> value(b, from, to)?.let { logo = it }
                    same(b, key, "tvg-name") -> if (name.isNullOrEmpty()) value(b, from, to)?.let { name = it }
                    same(b, key, "referrer") -> value(b, from, to)?.let { ref = it }
                }
                10 -> if (same(b, key, "user-agent")) value(b, from, to)?.let { ua = it }
                11 -> if (same(b, key, "group-title")) value(b, from, to)?.let { group = shared(it) }
                12 -> if (same(b, key, "http-referer")) value(b, from, to)?.let { ref = it }
                13 -> if (same(b, key, "http-referrer")) value(b, from, to)?.let { ref = it }
                15 -> if (same(b, key, "http-user-agent")) value(b, from, to)?.let { ua = it }
            }
        }

        private fun vlcOption(opt: String) {
            val key = opt.substringBefore('=').trim().lowercase()
            val value = opt.substringAfter('=', "").trim()
            if (value.isEmpty()) return
            if (key == "http-user-agent") ua = value
            else if (key == "http-referrer" || key == "http-referer") ref = value
        }

        private fun address(line: String) {
            var url = line
            val pipe = url.indexOf('|')
            if (pipe > 0) {
                val opts = url.substring(pipe + 1)
                url = url.substring(0, pipe)
                for (part in opts.split('&')) {
                    val k = part.substringBefore('=').trim().lowercase()
                    val raw = part.substringAfter('=', "")
                    // A stray "%" must not stop the whole playlist from loading: keep the text as it is.
                    val v = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    if (k == "user-agent") ua = v
                    else if (k == "referer" || k == "referrer") ref = v
                }
            }
            val title = name?.takeIf { it.isNotEmpty() } ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { url }
            count++
            sink(Channel(count, title, url, logo, group ?: extGroup ?: defaultGroup, ua, ref))
            name = null; logo = null; group = null; extGroup = null; ua = null; ref = null
        }

        /** Every channel of a group shares one String: a big playlist repeats each name thousands of times. */
        private fun shared(s: String) = groups.getOrPut(s) { s }

        private fun value(b: ByteArray, from: Int, to: Int): String? = text(b, from, to).trim().ifEmpty { null }

        private fun text(b: ByteArray, from: Int, to: Int) = String(b, from, to - from, Charsets.UTF_8)

        private fun isSpace(c: Byte) = c in 0..32

        private fun isKeyChar(c: Byte): Boolean {
            val x = c.toInt()
            return x == '-'.code || x == '_'.code || x in '0'.code..'9'.code || x in 'a'.code..'z'.code || x in 'A'.code..'Z'.code
        }

        private fun starts(b: ByteArray, a: Int, e: Int, s: String): Boolean {
            if (e - a < s.length) return false
            for (i in s.indices) {
                val x = b[a + i].toInt()
                val y = s[i].code
                if (x != y && (x or 0x20) != (y or 0x20)) return false
            }
            return true
        }

        /** [s] in lower case, compared without regard to case. */
        private fun same(b: ByteArray, at: Int, s: String): Boolean {
            for (i in s.indices) {
                val x = b[at + i].toInt()
                if (x != s[i].code && (x or 0x20) != s[i].code) return false
            }
            return true
        }
    }
}
