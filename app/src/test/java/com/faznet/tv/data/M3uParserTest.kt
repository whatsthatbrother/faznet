package com.faznet.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStream
import java.io.StringReader
import java.util.Random

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.byteInputStream(), "Other")

    @Test
    fun strayPercentInKodiOptionsKeepsTheWholePlaylist() {
        val list = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",One
            http://example.test/one.ts|User-Agent=Box%ZZ
            #EXTINF:-1 group-title="News",Two
            http://example.test/two.ts|User-Agent=My%20Box&Referer=http%3A%2F%2Fexample.test
            """.trimIndent()
        )
        assertEquals(2, list.size)
        assertEquals("Box%ZZ", list[0].userAgent) // kept as written instead of failing the load
        assertEquals("http://example.test/one.ts", list[0].url)
        assertEquals("My Box", list[1].userAgent)
        assertEquals("http://example.test", list[1].referrer)
    }

    @Test
    fun groupsNamesAndLogos() {
        val list = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="http://example.test/a.png" group-title="Sport",Sport 1, HD
            http://example.test/1
            #EXTINF:-1,No group
            #EXTGRP:Movies
            http://example.test/2
            """.trimIndent()
        )
        assertEquals(listOf("Sport 1, HD", "No group"), list.map { it.name })
        assertEquals(listOf("Sport", "Movies"), list.map { it.group })
        assertEquals("http://example.test/a.png", list[0].logo)
        assertEquals(listOf(1, 2), list.map { it.number })
    }

    @Test
    fun everyChannelOfAGroupSharesOneString() {
        val text = buildString {
            repeat(50) { append("#EXTINF:-1 group-title=\"Sport\",C$it\nhttp://example.test/$it\n") }
        }
        val list = parse(text)
        assertEquals(1, list.map { System.identityHashCode(it.group) }.toSet().size)
    }

    /** Odd but real files: each of these must read exactly as the old regular-expression parser read them. */
    private val tricky = listOf(
        "", "\n\n", "#EXTM3U", "http://a.test/bare.ts", "http://a.test/q?x=1", "http://a.test/",
        "﻿#EXTM3U\n#EXTINF:-1,Bom\nhttp://a.test/1\n",
        "#EXTINF:-1,Windows\r\nhttp://a.test/1\r\n#EXTINF:-1,Second\r\nhttp://a.test/2\r\n",
        "#EXTINF:-1 tvg-name=\"Attr name\" group-title=\"G\",\nhttp://a.test/1\n",
        "#EXTINF:-1 tvg-name=\"Attr name\" group-title=\"G\",Own name\nhttp://a.test/1\n",
        "#EXTINF:-1 group-title=\"a,b\" tvg-logo=\"l,o,g\",Name, with, commas\nhttp://a.test/1\n",
        "#EXTINF:-1 GROUP-TITLE=\"Upper\" TVG-LOGO=\"L\",Up\nhttp://a.test/1\n",
        "#extinf:-1 group-title=\"lower\",low\nhttp://a.test/1\n",
        "#EXTINF:-1 group-title = \"Spaces\" tvg-logo =\"L\",S\nhttp://a.test/1\n",
        "#EXTINF:-1 group-title=\"\" tvg-logo=\"\",Empty\nhttp://a.test/1\n",
        "#EXTINF:-1 group-title=\"  Padded  \",  Padded name  \nhttp://a.test/1\n",
        "#EXTINF:-1 tvg-id=\"x\" foo=\"bar\" group-title=\"G\",N\nhttp://a.test/1\n",
        "#EXTINF\nhttp://a.test/nocolon\n",
        "#EXTINF:\nhttp://a.test/empty\n",
        "#EXTINF:-1\nhttp://a.test/nocomma.ts?x=1\n",
        "#EXTINF:-1 unterminated=\"abc,Name\nhttp://a.test/1\n",
        "#EXTINF:-1,Ç'kemi Shqipëri ✦●✦\nhttp://a.test/1\n",
        "#EXTINF:-1 group-title=\"Lajme ë\",Ç\nhttp://a.test/1\n",
        "#EXTGRP:Before\n#EXTINF:-1,N\nhttp://a.test/1\n#EXTINF:-1,M\nhttp://a.test/2\n",
        "#EXTINF:-1 group-title=\"Title\",N\n#EXTGRP:Ignored\nhttp://a.test/1\n",
        "#EXTINF:-1,N\n#EXTVLCOPT:http-user-agent=VLC UA\n#EXTVLCOPT:http-referrer=http://r.test\nhttp://a.test/1\n",
        "#EXTINF:-1 user-agent=\"U1\" http-referrer=\"R1\",N\nhttp://a.test/1\n",
        "#EXTINF:-1 http-user-agent=\"U2\" referer=\"R2\",N\nhttp://a.test/1\n",
        "#EXTINF:-1 referrer=\"R3\" http-referer=\"R4\",N\nhttp://a.test/1\n",
        "#EXTINF:-1,N\nhttp://a.test/1|User-Agent=K&Referer=http%3A%2F%2Fr.test&Other=1\n",
        "#EXTINF:-1,N\n|odd.ts\n",
        "#KODIPROP:x=y\n#EXTINF:-1,N\nhttp://a.test/1\n",
        "#EXTINF:-1,First\n#EXTINF:-1,Second wins\nhttp://a.test/1",
        "   #EXTINF:-1,Indented\n\t http://a.test/1  \n",
    )

    @Test
    fun readsOddFilesLikeTheOldParser() {
        for (text in tricky) {
            assertEquals("input: $text", referenceParse(text), parse(text))
        }
    }

    @Test
    fun readsRandomFilesLikeTheOldParser() {
        val rnd = Random(7)
        val attrs = listOf("tvg-id", "tvg-name", "tvg-logo", "logo", "group-title", "user-agent", "http-user-agent", "referer", "referrer", "http-referrer", "http-referer", "TVG-LOGO", "x")
        val values = listOf("", "a", "a b", " pad ", "ë✦", "c,d", "http://a.test/x?y=1", "%41")
        val names = listOf("", "N", "N, M", " pad ", "Ç✦", "=", "\"q\"")
        val text = buildString {
            append("#EXTM3U\n")
            repeat(3000) { i ->
                when (rnd.nextInt(12)) {
                    0 -> append("#EXTGRP:${values[rnd.nextInt(values.size)]}\n")
                    1 -> append("#EXTVLCOPT:http-user-agent=${values[rnd.nextInt(values.size)]}\n")
                    2 -> append("\n")
                    3 -> append("#OTHER\n")
                    else -> {
                        append("#EXTINF:-1")
                        repeat(rnd.nextInt(5)) {
                            append(' ').append(attrs[rnd.nextInt(attrs.size)]).append("=\"").append(values[rnd.nextInt(values.size)]).append('"')
                        }
                        append(',').append(names[rnd.nextInt(names.size)]).append('\n')
                    }
                }
                val tail = if (rnd.nextInt(10) == 0) "|User-Agent=K%20$i" else ""
                append("http://a.test/$i.ts$tail\n")
            }
        }
        assertEquals(referenceParse(text), parse(text))
    }

    /** The network hands data over in pieces of any size: a line cut in two must read the same. */
    @Test
    fun readsTheSameWhenDataArrivesInSmallPieces() {
        val text = tricky.joinToString("\n") + "\n#EXTINF:-1 group-title=\"Long\",${"x".repeat(600_000)}\nhttp://a.test/long\n"
        val whole = parse(text)
        for (piece in listOf(1, 2, 3, 7, 1000)) {
            val small = object : InputStream() {
                private val data = text.toByteArray()
                private var at = 0
                override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (at >= data.size) return -1
                    val n = minOf(len, piece, data.size - at)
                    System.arraycopy(data, at, b, off, n)
                    at += n
                    return n
                }
            }
            assertEquals("pieces of $piece", whole, M3uParser.parse(small, "Other"))
        }
    }

    /** The parser that was replaced, kept here as the definition of the expected result. */
    private fun referenceParse(text: String): List<Channel> {
        val reader = BufferedReader(StringReader(text))
        val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var extGroup: String? = null
        var ua: String? = null
        var ref: String? = null
        fun findNameComma(s: String): Int {
            var inQuote = false
            for (i in s.indices) {
                val c = s[i]
                if (c == '"') inQuote = !inQuote
                else if (c == ',' && !inQuote) return i
            }
            return -1
        }
        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("﻿").trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val body = line.substringAfter(':', "")
                val comma = findNameComma(body)
                val attrs = if (comma >= 0) body.substring(0, comma) else body
                name = if (comma >= 0) body.substring(comma + 1).trim() else null
                logo = null; group = null; ua = null; ref = null
                for (m in attrRegex.findAll(attrs)) {
                    val key = m.groupValues[1].lowercase()
                    val value = m.groupValues[2].trim()
                    if (value.isEmpty()) continue
                    when (key) {
                        "tvg-logo", "logo" -> logo = value
                        "group-title" -> group = value
                        "tvg-name" -> if (name.isNullOrEmpty()) name = value
                        "user-agent", "http-user-agent" -> ua = value
                        "referer", "referrer", "http-referrer", "http-referer" -> ref = value
                    }
                }
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                extGroup = line.substring(8).trim().ifEmpty { null }
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val opt = line.substring(11)
                val key = opt.substringBefore('=').trim().lowercase()
                val value = opt.substringAfter('=', "").trim()
                if (value.isNotEmpty()) {
                    if (key == "http-user-agent") ua = value
                    else if (key == "http-referrer" || key == "http-referer") ref = value
                }
            } else if (line.startsWith("#")) {
                // ignored
            } else {
                var url = line
                val pipe = url.indexOf('|')
                if (pipe > 0) {
                    val opts = url.substring(pipe + 1)
                    url = url.substring(0, pipe)
                    for (part in opts.split('&')) {
                        val k = part.substringBefore('=').trim().lowercase()
                        val raw2 = part.substringAfter('=', "")
                        val v = runCatching { java.net.URLDecoder.decode(raw2, "UTF-8") }.getOrDefault(raw2)
                        if (k == "user-agent") ua = v
                        else if (k == "referer" || k == "referrer") ref = v
                    }
                }
                val title = name?.takeIf { it.isNotEmpty() } ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { url }
                out.add(Channel(out.size + 1, title, url, logo, group ?: extGroup ?: "Other", ua, ref))
                name = null; logo = null; group = null; extGroup = null; ua = null; ref = null
            }
        }
        return out
    }
}
