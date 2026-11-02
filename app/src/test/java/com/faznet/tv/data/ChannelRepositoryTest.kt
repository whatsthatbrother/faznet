package com.faznet.tv.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faznet.tv.data.db.ChannelImporter
import com.faznet.tv.data.db.FaznetDatabase
import com.faznet.tv.data.db.ListIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChannelRepositoryTest {

    private lateinit var app: Application
    private lateinit var db: FaznetDatabase
    private lateinit var repo: Repository
    private lateinit var channels: ChannelRepository

    @Before
    fun open() {
        app = ApplicationProvider.getApplicationContext()
        // Otherwise it would stay with the application of the first test.
        Repository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        repo = Repository.get(app)
        db = Room.inMemoryDatabaseBuilder(app, FaznetDatabase::class.java).allowMainThreadQueries().build()
        channels = ChannelRepository(app, db, repo)
    }

    @After
    fun close() {
        db.close()
        Parental.unlocked = false
        Parental.setPin(app, "")
        Parental.setLockedGroups(app, emptySet())
    }

    private fun url(n: Int, account: String = "user/pass") = "http://a.test/live/$account/$n.ts"

    /** Channels as (number in the address, name, group). */
    private fun m3u(vararg entries: Triple<Int, String, String>, account: String = "user/pass") = buildString {
        append("#EXTM3U\n")
        for ((n, name, group) in entries) append("#EXTINF:-1 group-title=\"$group\",$name\n${url(n, account)}\n")
    }

    private fun filePlaylist(text: String, id: String = "p"): Playlist {
        repo.cacheFile(id).writeText(text)
        return Playlist(id, "Test", Playlist.TYPE_FILE, "test.m3u")
    }

    /** Favourites and recently watched as version 4.0 left them on the device. */
    private fun savedByOlderVersion(id: String, favorites: Set<String>, recents: List<String>, last: String?) {
        app.getSharedPreferences("faznet", Context.MODE_PRIVATE).edit()
            .putStringSet("fav_$id", favorites)
            .putString("recent_$id", JSONArray(recents).toString())
            .putString("last_$id", last)
            .commit()
    }

    @Test
    fun whatAnOlderVersionSavedCarriesOver() = runBlocking {
        val p = filePlaylist(m3u(Triple(1, "A", "G"), Triple(2, "B", "G"), Triple(3, "C", "G")))
        savedByOlderVersion("p", setOf(url(2), url(99)), listOf(url(3), url(1)), url(3))

        val list = channels.ensureList(p, false)
        val view = channels.listView(list.listId)

        assertEquals(listOf("B"), channels.channelsOf(view.favorites(p)).map { it.name })
        assertEquals("the last one watched comes first", listOf("C", "A"), channels.channelsOf(view.recents(p)).map { it.name })
        assertEquals("C", channels.lastChannel(p, view)?.name)
        assertEquals("a channel missing from this list is not forgotten", 2, db.library().favoritesNow("p").size)
        assertEquals("the old values stay as they were", 2, repo.favorites("p").size)
    }

    @Test
    fun whatAnOlderVersionSavedIsTakenOverOnlyOnce() = runBlocking {
        val p = filePlaylist(m3u(Triple(1, "A", "G"), Triple(2, "B", "G")))
        savedByOlderVersion("p", setOf(url(1)), emptyList(), null)

        val first = channels.ensureList(p, false)
        val view = channels.listView(first.listId)
        val a = channels.channelsOf(view.all())[0]
        assertTrue(channels.isFavorite(p, a))
        assertFalse("toggling removes it", channels.toggleFavorite(p, a))

        channels.ensureList(p, false)
        assertFalse("it does not come back from the old values", channels.isFavorite(p, a))
    }

    @Test
    fun favoritesFollowAnXtreamChannelToANewAccount() = runBlocking {
        val p = Playlist("x", "Xtream", Playlist.TYPE_XTREAM, "http://a.test:8080", "user", "pass")
        repo.cacheFile("x").writeText(m3u(Triple(1, "A", "G"), Triple(2, "B", "G")))
        savedByOlderVersion("x", setOf(url(2)), listOf(url(1)), url(1))
        channels.ensureList(p, false)
        assertFalse("the database is the copy of a list that can be downloaded again", repo.cacheFile("x").exists())

        val moved = p.copy(username = "user2", password = "pass2")
        repo.cacheFile("x").writeText(m3u(Triple(2, "B new", "G"), Triple(1, "A new", "G"), account = "user2/pass2"))
        val after = channels.ensureList(moved, false)

        val view = channels.listView(after.listId)
        assertEquals("the old account's list is gone", listOf("B new", "A new"), channels.channelsOf(view.all()).map { it.name })
        assertEquals(listOf("B new"), channels.channelsOf(view.favorites(moved)).map { it.name })
        assertEquals(listOf("A new"), channels.channelsOf(view.recents(moved)).map { it.name })
        assertEquals("A new", channels.lastChannel(moved, view)?.name)
    }

    @Test
    fun aListIsReadAgainOnlyWhenItChanged() = runBlocking {
        val p = filePlaylist(m3u(Triple(1, "A", "G")))
        val first = channels.ensureList(p, false)
        assertTrue(first.changed)

        val same = channels.ensureList(p, true)
        assertFalse(same.changed)
        assertEquals(first.listId, same.listId)

        repo.cacheFile("p").writeText(m3u(Triple(1, "A", "G"), Triple(2, "B", "G")))
        assertEquals("nothing asked for: the database answers", first.listId, channels.ensureList(p, false).listId)
        val changed = channels.ensureList(p, true)
        assertTrue(changed.changed)
        assertEquals(2, changed.channels)
        assertNotEquals(first.listId, changed.listId)
    }

    private fun big(n: Int) = buildString {
        append("#EXTM3U\n")
        for (i in 1..n) append("#EXTINF:-1 group-title=\"G${i % 7}\",C$i\n${url(i)}\n")
    }

    private fun rows(listId: Long) = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM channel WHERE id > ${ListIds.base(listId)} AND id <= ${ListIds.last(listId)}")
        .use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun theCleanUpWaitsForARunningImport() = runBlocking {
        val p = filePlaylist(big(20_000))
        repo.addPlaylist(p)
        val running = async(Dispatchers.IO) { channels.ensureList(p, false) }
        withTimeoutOrNull(5_000) { channels.importProgress.first { it != null && it > 0 } }
        channels.cleanUp()
        val list = running.await()
        assertEquals(20_000, rows(list.listId))
        assertEquals("C1", db.channels().byId(ListIds.base(list.listId) + 1)?.name)
    }

    @Test
    fun twoImportsAtOnceLeaveBothListsWhole() = runBlocking {
        val a = filePlaylist(big(6_000), "a")
        val b = filePlaylist(big(6_000), "b")
        val first = async(Dispatchers.IO) { channels.ensureList(a, false) }
        val second = async(Dispatchers.IO) { channels.ensureList(b, false) }
        val listA = first.await()
        val listB = second.await()
        assertNotEquals(listA.listId, listB.listId)
        assertEquals(6_000, rows(listA.listId))
        assertEquals(6_000, rows(listB.listId))
    }

    @Test
    fun aListAlreadyThereOpensWhileAnotherIsImported() = runBlocking {
        val here = filePlaylist(big(10), "here")
        val done = channels.ensureList(here, false)
        val busy = filePlaylist(big(20_000), "busy")
        val running = async(Dispatchers.IO) { channels.ensureList(busy, false) }
        withTimeoutOrNull(5_000) { channels.importProgress.first { it != null && it > 0 } }
        assertEquals(done.listId, channels.ensureList(here, false).listId)
        assertTrue("it did not have to wait", channels.importProgress.value != null)
        running.await()
        Unit
    }

    @Test
    fun theOldListCanBeReadUntilTheCleanUp() = runBlocking {
        val p = filePlaylist(big(100))
        repo.addPlaylist(p)
        val old = channels.ensureList(p, false)
        val pages = channels.pagesOf(channels.listView(old.listId).all())

        repo.cacheFile("p").writeText(big(120))
        val new = channels.ensureList(p, true)
        assertNotEquals(old.listId, new.listId)
        assertEquals("a player opened before the new list goes on", "C50", pages.get(49)?.name)
        assertEquals(new.listId, db.library().stateNow("p")?.listId)

        channels.cleanUp()
        assertEquals(0, rows(old.listId))
        assertEquals(120, rows(new.listId))
    }

    @Test
    fun aCancelledImportStopsAndLeavesTheOldList() = runBlocking {
        val p = filePlaylist(big(100))
        repo.addPlaylist(p)
        val old = channels.ensureList(p, false)
        repo.cacheFile("p").writeText(big(60_000))
        val running = launch(Dispatchers.IO) { channels.ensureList(p, true) }
        withTimeoutOrNull(5_000) { channels.importProgress.first { it != null && it > 0 } }
        running.cancelAndJoin()
        assertEquals(old.listId, db.library().stateNow("p")?.listId)
        assertEquals(100, rows(old.listId))
        channels.cleanUp()
        assertEquals("nothing of the stopped one stays", 100, db.imports().highestChannelId()!! - ListIds.base(old.listId))
    }

    @Test
    fun aMuchShorterListIsTakenOnlyWhenTheUserAsksForIt() = runBlocking {
        val p = filePlaylist(big(100))
        val old = channels.ensureList(p, false)

        repo.cacheFile("p").writeText(big(30))
        try {
            channels.ensureList(p, true, userAsked = false)
            fail("a cut-off file or a bad moment at the provider's")
        } catch (e: ChannelImporter.ListRefused) {
            assertEquals(30, e.channels)
        }
        assertEquals(old.listId, db.library().stateNow("p")?.listId)
        assertEquals("a file of the user's own stays", true, repo.cacheFile("p").exists())

        val asked = channels.ensureList(p, true)
        assertEquals("Refresh on the screen means it", 30, asked.channels)
        assertEquals(asked.listId, db.library().stateNow("p")?.listId)
    }

    @Test
    fun aListHalfAsLongIsStillAList() = runBlocking {
        val p = filePlaylist(big(100))
        channels.ensureList(p, false)
        repo.cacheFile("p").writeText(big(50))
        assertEquals(50, channels.ensureList(p, true, userAsked = false).channels)
    }

    @Test
    fun lockedGroupsStayOutOfEveryList() = runBlocking {
        val p = filePlaylist(
            m3u(Triple(1, "News 1", "News"), Triple(2, "News 2", "News"), Triple(3, "Late 1", "Late"), Triple(4, "Late 2", "Late"), Triple(5, "Film 1", "Film"), Triple(6, "Film 2", "Film"))
        )
        val list = channels.ensureList(p, false)
        val everything = channels.channelsOf(channels.listView(list.listId).all())
        assertEquals(6, everything.size)
        val late = everything.first { it.name == "Late 1" }
        val film = everything.first { it.name == "Film 2" }
        channels.toggleFavorite(p, late)
        channels.toggleFavorite(p, film)
        channels.markWatched(p, late)

        Parental.setPin(app, "1234")
        Parental.setLockedGroups(app, setOf("Late"))
        val view = channels.listView(list.listId)

        assertTrue(view.isLocked("Late"))
        assertEquals(listOf("News", "Film"), view.openGroups.map { it.name })
        assertEquals(4, view.channelCount)
        val all = view.all()
        assertEquals(listOf("News 1", "News 2", "Film 1", "Film 2"), channels.channelsOf(all).map { it.name })
        assertEquals("a page right after the locked group", listOf("Film 1"), all.read(db, 2, 1).map { it.name })
        assertEquals(3, all.indexOf(db, film))
        assertNull(all.indexOf(db, late))
        assertEquals(listOf("Film 2"), channels.channelsOf(view.favorites(p)).map { it.name })
        assertEquals(emptyList<String>(), channels.channelsOf(view.recents(p)).map { it.name })
        assertEquals(listOf("News 1", "Film 1"), channels.channelsOf(channels.search(view, "1")).map { it.name })
        assertNull("the channel last watched is locked now", channels.lastChannel(p, view))

        Parental.unlocked = true
        val open = channels.listView(list.listId)
        assertEquals(6, open.channelCount)
        assertEquals("Late 1", channels.lastChannel(p, open)?.name)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChannelPagesTest {

    private lateinit var db: FaznetDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FaznetDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun channels(n: Int) = (1..n).map { Channel(it, "C$it", "http://a.test/$it.ts", null, "G", null, null) }

    private fun pages(n: Int) = ChannelPages(db, ChannelScope.Fixed(channels(n)))

    @Test
    fun aChannelIsThereOnlyAfterItsPageWasRead() = runBlocking {
        val list = pages(500)
        assertEquals(500, list.size)
        assertNull(list.peek(130))
        assertEquals("C131", list.get(130)?.name)
        assertEquals("C131", list.peek(130)?.name)
        assertEquals("the same page", "C121", list.peek(120)?.name)
        assertNull("the next page", list.peek(180))
    }

    @Test
    fun placesOutsideTheListAreEmpty() = runBlocking {
        val list = pages(10)
        assertNull(list.get(-1))
        assertNull(list.get(10))
        assertEquals("C10", list.get(9)?.name)
    }

    @Test
    fun onlyPagesNearWhereItIsStayInMemory() = runBlocking {
        val list = pages(ChannelPages.PAGE * 40)
        for (p in 0 until 40) list.get(p * ChannelPages.PAGE)
        assertNull("the oldest went away", list.peek(0))
        assertEquals("the newest stayed", "C${39 * ChannelPages.PAGE + 1}", list.peek(39 * ChannelPages.PAGE)?.name)
        assertEquals("it can be read again", "C1", list.get(0)?.name)
    }

    @Test
    fun theLastPlaceOfAChannelIsFound() = runBlocking {
        val all = channels(300)
        val list = ChannelPages(db, ChannelScope.Fixed(all))
        assertEquals(250, list.indexOf(all[250]))
        assertNull(list.indexOf(channels(301).last()))
    }
}
