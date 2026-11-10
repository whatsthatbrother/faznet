package com.faznet.tv.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Reader
import java.io.StringReader

/** The big answers (all movies, all series, the live list for catch-up) are read as a stream. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class XtreamApiTest {

    @Test
    fun moviesWithNumbersAsTextOrNumbersAndMissingFields() {
        val json = """[
            {"stream_id": 101, "name": "Film A", "stream_icon": "http://i/a.jpg", "container_extension": "mkv",
             "rating": "7.5", "year": "2024", "plot": "${"x".repeat(2000)}", "nested": {"a": [1, 2]}},
            {"stream_id": "102", "name": "Film B", "stream_icon": "", "container_extension": null, "rating": null},
            "not an object",
            {"stream_id": "103", "name": "null", "stream_icon": "null"}
        ]"""
        val list = XtreamApi.readVods(StringReader(json))
        assertEquals(3, list.size)
        assertEquals(XVod("101", "Film A", "http://i/a.jpg", "mkv", "7.5", "2024"), list[0])
        assertEquals(XVod("102", "Film B", null, "mp4", "", ""), list[1])
        assertEquals(XVod("103", "", null, "mp4", "", ""), list[2])
    }

    @Test
    fun series() {
        val list = XtreamApi.readSeries(StringReader("""[{"series_id": 7, "name": "S", "cover": "c", "plot": "p", "rating": 8}]"""))
        assertEquals(listOf(XSeries("7", "S", "c", "p", "8")), list)
    }

    @Test
    fun anErrorObjectIsAnEmptyList() {
        assertTrue(XtreamApi.readVods(StringReader("""{"user_info": {"auth": 0}}""")).isEmpty())
        assertTrue(XtreamApi.readVods(StringReader("")).isEmpty())
    }

    @Test(expected = Exception::class)
    fun anAnswerThatBreaksOffIsAFailureNotAShorterList() {
        XtreamApi.readVods(StringReader("""[{"stream_id": 1, "name": "A"}, {"stream_id": 2, "na"""))
    }

    @Test
    fun catchUpDaysOnlyForChannelsWithAnArchive() {
        val json = """[
            {"stream_id": 1, "tv_archive": 1, "tv_archive_duration": "3"},
            {"stream_id": 2, "tv_archive": "0", "tv_archive_duration": 5},
            {"stream_id": 3, "tv_archive": "1"},
            {"stream_id": 4, "tv_archive": 1, "tv_archive_duration": 0}
        ]"""
        assertEquals(mapOf("1" to 3, "3" to 1, "4" to 1), XtreamApi.readArchive(StringReader(json)))
    }

    /** A list of 60 000 movies is read without ever being one String in memory. */
    @Test
    fun aHugeListIsReadPieceByPiece() {
        val count = 60_000
        var maxAsked = 0
        val reader = object : Reader() {
            private var item = -1
            private var pending = "["
            override fun read(buf: CharArray, off: Int, len: Int): Int {
                maxAsked = maxOf(maxAsked, len)
                if (pending.isEmpty()) {
                    item++
                    pending = when {
                        item < count -> (if (item > 0) "," else "") +
                            """{"stream_id":$item,"name":"Film $item","plot":"${"p".repeat(200)}"}"""
                        item == count -> "]"
                        else -> return -1
                    }
                }
                val n = minOf(len, pending.length)
                pending.toCharArray(buf, off, 0, n)
                pending = pending.substring(n)
                return n
            }
            override fun close() = Unit
        }
        val list = XtreamApi.readVods(reader)
        assertEquals(count, list.size)
        assertEquals("Film 59999", list.last().name)
        assertTrue("read in small pieces, asked for $maxAsked", maxAsked <= 64 * 1024)
        assertNull(list.first().icon)
    }
}
