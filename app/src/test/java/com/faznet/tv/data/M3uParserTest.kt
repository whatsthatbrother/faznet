package com.faznet.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(BufferedReader(StringReader(text)), "Other")

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
}
