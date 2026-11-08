package com.faznet.tv.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class XtreamLiveTest {

    private val account = XtreamAccount("http://s.test:8080", "u", "p")

    @Test
    fun liveChannelsWithTheirCategoryNamesInServerOrder() {
        // What servers really send: numbers as strings or numbers, nulls, extra fields, HTML entities in names.
        val json = """
            [
              {"num":1,"name":"RTSH 1 HD","stream_type":"live","stream_id":"101","stream_icon":"http://i/1.png",
               "epg_channel_id":"rtsh1","category_id":"7","tv_archive":1},
              {"num":2,"name":"Tom &amp; Jerry","stream_type":"live","stream_id":102,"stream_icon":"","category_id":8},
              {"num":3,"name":"No category","stream_id":"103","category_id":null},
              {"num":4,"name":"Broken","stream_id":"abc","category_id":"7"},
              {"num":5,"name":"","stream_id":"105","category_id":"99","extra":{"a":[1,2]}}
            ]
        """.trimIndent()
        val out = ArrayList<Channel>()
        val n = XtreamLive.parse(json.byteInputStream(), account, mapOf("7" to "News", "8" to "Kids"), "Other") { out.add(it) }
        assertEquals(4, n)
        assertEquals(listOf("RTSH 1 HD", "Tom & Jerry", "No category", "105"), out.map { it.name })
        assertEquals(listOf("News", "Kids", "Other", "Other"), out.map { it.group })
        assertEquals(listOf(1, 2, 3, 4), out.map { it.number })
        assertEquals("http://s.test:8080/live/u/p/101.ts", out[0].url)
        assertEquals("http://i/1.png", out[0].logo)
        assertEquals(null, out[1].logo)
    }

    @Test
    fun jsonOrM3u() {
        val f = File.createTempFile("list", ".bin")
        try {
            f.writeText("\uFEFF  [ {\"stream_id\":\"1\"} ]")
            assertTrue(XtreamLive.isJson(f))
            f.writeText("#EXTM3U\n#EXTINF:-1,A\nhttp://a/1\n")
            assertFalse(XtreamLive.isJson(f))
        } finally {
            f.delete()
        }
        assertTrue(XtreamLive.looksLikeList("  [{\"num\":1"))
        assertFalse(XtreamLive.looksLikeList("{\"user_info\":{\"auth\":0}}"))
        assertFalse(XtreamLive.looksLikeList("<html>"))
    }
}
