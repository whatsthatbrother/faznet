package com.faznet.tv.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faznet.tv.data.db.FaznetDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
