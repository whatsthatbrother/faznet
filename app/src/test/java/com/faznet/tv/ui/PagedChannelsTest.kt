package com.faznet.tv.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelPages
import com.faznet.tv.data.ChannelScope
import com.faznet.tv.data.db.ChannelImporter
import com.faznet.tv.data.db.FaznetDatabase
import com.faznet.tv.ui.compose.PagedChannels
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** The channel list over the picture and the guide read their channels a page at a time (ui/compose/ChannelRows.kt). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PagedChannelsTest {

    private lateinit var db: FaznetDatabase

    @Before
    fun open() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, FaznetDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun channels(n: Int) = (1..n).map { Channel(it, "C$it", "http://a.test/$it.ts", null, "G", null, null) }

    private fun import(n: Int): Pair<Long, Map<Long, String>> {
        val text = buildString {
            append("#EXTM3U\n")
            for (i in 1..n) append("#EXTINF:-1 group-title=\"G\",N$i\nhttp://a.test/$i.ts\n")
        }
        val r = ChannelImporter(db).import("p", ByteArrayInputStream(text.toByteArray()), "Other", false, "f", "t", 1L)
        return r.listId to runBlocking { db.channels().groupsNow(r.listId) }.associate { it.id to it.name }
    }

    @Test
    fun scrollingPastAPageOfSearchResultsReadsTheNextPage() = runBlocking {
        val list = PagedChannels(ChannelPages(db, ChannelScope.Fixed(channels(200))))
        list.read(0)
        assertEquals("C1", list.peek(0)?.name)
        assertNull(list.peek(150))
        list.read(150)
        assertEquals("C151", list.peek(150)?.name)
    }

    @Test
    fun aPageFromTheDatabaseFillsItsRowsAndRedrawsThem() = runBlocking {
        val (listId, names) = import(200)
        val list = PagedChannels(ChannelPages(db, ChannelScope.All(listId, 200, names)))
        val before = list.version.intValue
        list.read(120)
        assertEquals("N121", list.peek(120)?.name)
        assertTrue("rows on screen are told the page came", list.version.intValue > before)
    }

    /** The list is shorter than it says: an older list is gone, or rows are missing. Those rows stay empty, nothing more. */
    @Test
    fun aRowWithoutAChannelStaysEmptyAndIsNotReadForever() = runBlocking {
        val (listId, names) = import(10)
        val list = PagedChannels(ChannelPages(db, ChannelScope.All(listId, 30, names)))
        list.read(0)
        assertEquals("N1", list.peek(0)?.name)
        assertNull(list.peek(12))
        val after = list.version.intValue
        list.read(12) // its page was read: no second read, no new redraw
        assertNull(list.peek(12))
        assertEquals(after, list.version.intValue)
    }
}
