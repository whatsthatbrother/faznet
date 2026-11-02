package com.faznet.tv.data

import android.app.Application
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faznet.tv.data.db.FaznetDatabase
import com.faznet.tv.data.db.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A TV stick with its storage full: nothing that only saves something may close the channel being watched. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FullStorageTest {

    private lateinit var app: Application
    private lateinit var db: FaznetDatabase
    private lateinit var channels: ChannelRepository
    private lateinit var p: Playlist

    @Before
    fun open() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        Repository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        val repo = Repository.get(app)
        db = Room.inMemoryDatabaseBuilder(app, FaznetDatabase::class.java).allowMainThreadQueries().build()
        channels = ChannelRepository(app, db, repo)
        repo.cacheFile("p").writeText("#EXTM3U\n#EXTINF:-1 group-title=\"G\",A\nhttp://a.test/1.ts\n")
        p = Playlist("p", "Test", Playlist.TYPE_FILE, "test.m3u")
        channels.ensureList(p, false)
        Unit
    }

    @After
    fun close() {
        db.close()
        File(app.filesDir, "problems.log").deleteRecursively()
    }

    private suspend fun channel() = channels.channelsOf(channels.listView(channels.activeList("p")!!).all())[0]

    /** The database may not grow any more: the same error as a full device. */
    private suspend fun fill() {
        db.openHelper.writableDatabase.query("PRAGMA max_page_count = 1").use { it.moveToFirst() }
        try {
            for (i in 1..1_000_000) db.library().addHistory(listOf(HistoryEntity("f", "$i", null, 1L)))
            fail("it never got full")
        } catch (e: SQLiteFullException) {
            // the free room in its last page is used up as well now
        }
    }

    @Test
    fun theDatabaseIsReallyFullInTheseTests() = runBlocking {
        val ch = channel()
        fill()
        try {
            channels.markWatched(p, ch)
            fail("the save should have failed")
        } catch (e: SQLiteException) {
            // expected; SQLite has given up the transaction by itself by then, so not always a SQLiteFullException
        }
    }

    @Test
    fun aSaveThatFailsDoesNotCloseTheApp() = runBlocking {
        val ch = channel()
        fill()
        var uncaught: Throwable? = null
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught = e }
        try {
            CoroutineScope(Job() + Dispatchers.IO).launch(DbErrors) { channels.markWatched(p, ch) }.join()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertNull(uncaught)
    }

    @Test
    fun anotherErrorStillClosesTheAppAsABugShould() = runBlocking {
        var uncaught: Throwable? = null
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught = e }
        try {
            CoroutineScope(Job() + Dispatchers.IO).launch(DbErrors) { throw IllegalStateException("a bug") }.join()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertTrue("got $uncaught", uncaught is IllegalStateException)
    }

    @Test
    fun aPageThatCannotBeReadIsAskedForAgainNextTime() = runBlocking {
        val scope = channels.listView(channels.activeList("p")!!).all()
        val pages = ChannelPages(db, scope)
        val paging = ChannelPagingSource(db, scope)
        db.openHelper.writableDatabase.execSQL("ALTER TABLE channel RENAME TO gone")
        assertNull(pages.get(0))
        assertTrue(paging.load(PagingSource.LoadParams.Refresh(0, 10, false)) is PagingSource.LoadResult.Error)

        db.openHelper.writableDatabase.execSQL("ALTER TABLE gone RENAME TO channel")
        assertEquals("A", pages.get(0)?.name)
    }

    @Test
    fun theProblemLogIsNoReasonToCloseAnything() {
        val log = File(app.filesDir, "problems.log")
        log.mkdirs() // reading and writing it now fail, as on a full device
        ProblemLog.add(app, "A", "frozen")
        assertEquals(emptyList<String>(), ProblemLog.read(app))
    }
}
