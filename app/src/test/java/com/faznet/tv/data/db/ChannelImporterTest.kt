package com.faznet.tv.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faznet.tv.data.ChannelPagingSource
import com.faznet.tv.data.ChannelScope
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
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChannelImporterTest {

    private lateinit var db: FaznetDatabase
    private lateinit var importer: ChannelImporter

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FaznetDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = ChannelImporter(db)
    }

    @After
    fun close() = db.close()

    private fun m3u(vararg entries: Pair<String, String>) = buildString {
        append("#EXTM3U\n")
        for ((name, group) in entries) {
            append("#EXTINF:-1 group-title=\"$group\",$name\nhttp://a.test/live/user/pass/${name.hashCode() and 0xFFFF}.ts\n")
        }
    }

    private fun import(text: String, playlist: String = "p", xtream: Boolean = false, fingerprint: String = "f") =
        importer.import(playlist, ByteArrayInputStream(text.toByteArray()), "Other", xtream, fingerprint, "tag", 1L)

    @Test
    fun groupsAndPositionsAreInOrderOfAppearance() = runBlocking {
        val r = import(m3u("A" to "One", "B" to "Two", "C" to "One", "D" to "Three", "E" to "Two"))
        assertEquals(5, r.channels)
        assertEquals(3, r.groups)
        val groups = db.channels().groupsNow(r.listId)
        assertEquals(listOf("One", "Two", "Three"), groups.map { it.name })
        assertEquals(listOf(2, 2, 1), groups.map { it.channelCount })
        assertEquals(listOf("A", "C"), db.channels().groupPage(groups[0].id, 0, 10).map { it.name })
        assertEquals(listOf(0, 1), db.channels().groupPage(groups[0].id, 0, 10).map { it.position })
        assertEquals(listOf("E"), db.channels().groupPage(groups[1].id, 1, 2).map { it.name })
    }

    @Test
    fun allChannelsReadByNumberInAnyPlace() = runBlocking {
        val names = (1..5000).map { "C$it" to "G${it % 7}" }.toTypedArray()
        val r = import(m3u(*names))
        val page = db.channels().range(ListIds.base(r.listId), 4000, 4010)
        assertEquals((4000 until 4010).toList(), page.map { it.number })
        assertEquals("C4000", page[0].name)
    }

    @Test
    fun aNewImportReplacesTheOldListCompletely() = runBlocking {
        val first = import(m3u("A" to "One", "B" to "One"), fingerprint = "1")
        val second = import(m3u("X" to "Other"), fingerprint = "2")
        assertTrue(second.listId > first.listId)
        assertEquals(second.listId, db.library().stateNow("p")?.listId)
        assertEquals("2", db.library().stateNow("p")?.fingerprint)
        assertEquals(emptyList<Any>(), db.channels().range(ListIds.base(first.listId), 1, 100))
        assertEquals(emptyList<Any>(), db.channels().groupsNow(first.listId))
        assertEquals(listOf("X"), db.channels().range(ListIds.base(second.listId), 1, 100).map { it.name })
    }

    @Test
    fun aFailedImportLeavesTheOldListInPlace() = runBlocking {
        val first = import(m3u("A" to "One"), fingerprint = "1")
        val broken = object : InputStream() {
            private val head = ByteArrayInputStream(m3u("Half" to "Way").toByteArray())
            override fun read(): Int = head.read().also { if (it < 0) throw IOException("cut off") }
        }
        try {
            importer.import("p", broken, "Other", false, "2", "tag", 1L)
            fail("the error has to reach the caller")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(first.listId, db.library().stateNow("p")?.listId)
        assertEquals("1", db.library().stateNow("p")?.fingerprint)
        assertEquals(listOf("A"), db.channels().range(ListIds.base(first.listId), 1, 100).map { it.name })
        assertEquals(1, db.imports().highestChannelId()!! - ListIds.base(first.listId))
    }

    @Test
    fun favoritesFollowTheChannelEvenWhenTheAccountChanges() = runBlocking {
        val key = ChannelKeys.of("http://a.test/live/olduser/oldpass/777.ts", xtream = true)
        db.library().addFavorites(listOf(FavoriteEntity("p", key, null, 1L), FavoriteEntity("p", "x:999", null, 1L)))
        val r = import(
            "#EXTM3U\n#EXTINF:-1,Sport\nhttp://other.test:8080/live/newuser/newpass/777.ts\n#EXTINF:-1,Film\nhttp://other.test:8080/live/newuser/newpass/888.ts\n",
            xtream = true,
        )
        val favorites = db.library().favoritesNow("p").associateBy { it.key }
        assertEquals(ListIds.base(r.listId) + 1, favorites["x:777"]?.channelId)
        assertNull("not in this list, but not forgotten either", favorites["x:999"]?.channelId)
        assertEquals(setOf("x:777", "x:999"), favorites.keys)
    }

    @Test
    fun whatAnInterruptedImportLeftBehindIsRemoved() = runBlocking {
        val r = import(m3u("A" to "One"))
        // An import stopped half way: channels of a newer list without any groups.
        db.imports().insertChannels(
            listOf(ChannelEntity(ListIds.base(r.listId + 1) + 1, ListIds.group(r.listId + 1, 0), 0, 1, "Lost", null, "u", null, null, null))
        )
        importer.removeUnusedLists()
        assertEquals(ListIds.base(r.listId) + 1, db.imports().highestChannelId())
        assertEquals(listOf("A"), db.channels().range(ListIds.base(r.listId), 1, 10).map { it.name })
    }

    @Test
    fun searchFindsAPartOfTheNameWithoutRegardToCaseOrAccents() = runBlocking {
        val r = import(m3u("AL| KLAN HD" to "G", "Shqipëri Live" to "G", "Çamëria" to "G", "Sport 100%" to "G"))
        suspend fun find(text: String): List<String> {
            val pattern = "%" + ChannelKeys.normalize(text).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            return db.channels().search(ListIds.base(r.listId), ListIds.last(r.listId), pattern, 10).map { it.name }
        }
        assertEquals(listOf("AL| KLAN HD"), find("klan"))
        assertEquals(listOf("Shqipëri Live"), find("SHQIPERI"))
        assertEquals(listOf("Shqipëri Live"), find("shqipëri"))
        assertEquals(listOf("Çamëria"), find("camer"))
        assertEquals(listOf("Sport 100%"), find("100%"))
        assertEquals(emptyList<String>(), find("0%x"))
    }

    @Test
    fun aPageCanBeReadFromAnywhereInAScope() = runBlocking {
        val names = (1..1000).map { "C$it" to if (it % 2 == 0) "Even" else "Odd" }.toTypedArray()
        val r = import(m3u(*names))
        val rows = db.channels().groupsNow(r.listId)
        val namesById = rows.associate { it.id to it.name }
        val even = ChannelScope.Group(rows.first { it.name == "Even" }.id, 500, namesById)
        val all = ChannelScope.All(r.listId, 1000, namesById)

        assertEquals(listOf("C2", "C4"), even.read(db, 0, 2).map { it.name })
        assertEquals(listOf("C1000"), even.read(db, 499, 10).map { it.name })
        assertEquals(listOf(999, 1000), all.read(db, 998, 10).map { it.number })
        assertEquals("Even", all.read(db, 1, 1)[0].group)

        val ch = all.read(db, 99, 1)[0] // C100, an even one
        assertEquals(99, all.indexOf(db, ch))
        assertEquals(49, even.indexOf(db, ch))
        assertNull(even.indexOf(db, all.read(db, 0, 1)[0]))

        val source = ChannelPagingSource(db, all)
        val page = source.load(androidx.paging.PagingSource.LoadParams.Refresh(500, 120, true))
            as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(500, page.itemsBefore)
        assertEquals(380, page.itemsAfter)
        assertEquals(620, page.nextKey)
        assertEquals(500, page.prevKey)
        val before = source.load(androidx.paging.PagingSource.LoadParams.Prepend(500, 60, true))
            as androidx.paging.PagingSource.LoadResult.Page
        assertEquals((441..500).toList(), before.data.map { it.number })
        assertEquals(440, before.prevKey)
    }
}
