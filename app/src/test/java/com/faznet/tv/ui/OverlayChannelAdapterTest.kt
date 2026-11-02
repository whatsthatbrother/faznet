package com.faznet.tv.ui

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faznet.tv.R
import com.faznet.tv.data.Channel
import com.faznet.tv.data.ChannelPages
import com.faznet.tv.data.ChannelScope
import com.faznet.tv.data.db.ChannelImporter
import com.faznet.tv.data.db.FaznetDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OverlayChannelAdapterTest {

    private lateinit var db: FaznetDatabase
    private lateinit var rv: RecyclerView
    private lateinit var adapter: OverlayChannelAdapter
    private var crash: Throwable? = null

    @Before
    fun open() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, FaznetDatabase::class.java).allowMainThreadQueries().build()
        val ctx = ContextThemeWrapper(app, R.style.Theme_Faznet)
        // Like lifecycleScope; an error in a coroutine closes the app, here it is kept for the test.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> crash = e })
        adapter = OverlayChannelAdapter(scope) {}
        rv = RecyclerView(ctx)
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter
    }

    @After
    fun close() = db.close()

    private fun layout() {
        rv.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        rv.layout(0, 0, 600, 1600)
    }

    /** Lets pages arrive from the database and the list draw what came, a few times over. */
    private fun settle() {
        repeat(6) {
            Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idle()
            layout()
        }
    }

    private fun row(position: Int) = rv.findViewHolderForAdapterPosition(position) as OverlayChannelAdapter.VH?

    private fun channels(n: Int) = (1..n).map { Channel(it, "C$it", "http://a.test/$it.ts", null, "G", null, null) }

    @Test
    fun scrollingPastAPageOfSearchResultsKeepsTheListAlive() {
        adapter.submit(ChannelPages(db, ChannelScope.Fixed(channels(200))), null)
        layout()
        settle()
        assertEquals("C1", row(0)?.name?.text?.toString())

        rv.scrollToPosition(150)
        layout()
        settle()
        assertNull(crash)
        assertEquals("C151", row(150)?.name?.text?.toString())
    }

    @Test
    fun aPageFromTheDatabaseFillsItsRows() {
        val text = buildString {
            append("#EXTM3U\n")
            for (i in 1..200) append("#EXTINF:-1 group-title=\"G\",N$i\nhttp://a.test/$i.ts\n")
        }
        val r = ChannelImporter(db).import("p", ByteArrayInputStream(text.toByteArray()), "Other", false, "f", "t", 1L)
        val names = runBlocking { db.channels().groupsNow(r.listId) }.associate { it.id to it.name }
        adapter.submit(ChannelPages(db, ChannelScope.All(r.listId, 200, names)), null)
        layout()
        settle()
        rv.scrollToPosition(120)
        layout()
        settle()
        assertNull(crash)
        assertEquals("N121", row(120)?.name?.text?.toString())
    }

    /** The list is shorter than it says: an older list is gone, or rows are missing. Those rows stay empty, nothing more. */
    @Test
    fun aRowWithoutAChannelStaysEmptyAndIsNotReadForever() {
        val text = buildString {
            append("#EXTM3U\n")
            for (i in 1..10) append("#EXTINF:-1 group-title=\"G\",N$i\nhttp://a.test/$i.ts\n")
        }
        val r = ChannelImporter(db).import("p", ByteArrayInputStream(text.toByteArray()), "Other", false, "f", "t", 1L)
        val names = runBlocking { db.channels().groupsNow(r.listId) }.associate { it.id to it.name }
        var changes = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { changes++ }
        })
        adapter.submit(ChannelPages(db, ChannelScope.All(r.listId, 30, names)), null)
        layout()
        settle()
        settle()
        assertNull(crash)
        assertEquals("N1", row(0)?.name?.text?.toString())
        assertEquals("", row(12)?.name?.text?.toString())
        assertTrue("the page is told about once, it came $changes times", changes <= 1)
    }
}
