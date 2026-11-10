package com.faznet.tv.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryCacheTest {

    private val a = XtreamAccount("http://h", "u", "p")

    @After fun reset() { LibraryCache.clear(); LibraryCache.maxTitles = 60_000 }

    @Test
    fun aCategorySeenBeforeComesFromMemory() {
        val k = LibraryCache.key(a, series = false, category = "5")
        LibraryCache.put(k, listOf("x", "y"), now = 0)
        assertEquals(listOf("x", "y"), LibraryCache.get<String>(k, now = 60_000))
        assertNull("series of the same category are another list", LibraryCache.get<String>(LibraryCache.key(a, true, "5"), now = 0))
        assertNull("another account", LibraryCache.get<String>(LibraryCache.key(a.copy(user = "v"), false, "5"), now = 0))
    }

    @Test
    fun afterHalfAnHourItIsAskedAgain() {
        val k = LibraryCache.key(a, false, null)
        LibraryCache.put(k, listOf("x"), now = 0)
        assertNull(LibraryCache.get<String>(k, now = 31 * 60_000L))
    }

    @Test
    fun theOldestGoWhenTooManyTitlesAreKept() {
        LibraryCache.maxTitles = 5
        val k1 = LibraryCache.key(a, false, "1")
        val k2 = LibraryCache.key(a, false, "2")
        val k3 = LibraryCache.key(a, false, "3")
        LibraryCache.put(k1, List(2) { "a" }, now = 0)
        LibraryCache.put(k2, List(2) { "b" }, now = 0)
        LibraryCache.get<String>(k1, now = 0) // used again: k2 is now the oldest
        LibraryCache.put(k3, List(2) { "c" }, now = 0)
        assertNotNull(LibraryCache.get<String>(k1, now = 0))
        assertNull(LibraryCache.get<String>(k2, now = 0))
        assertNotNull(LibraryCache.get<String>(k3, now = 0))
        LibraryCache.put(LibraryCache.key(a, false, null), List(6) { "all" }, now = 0)
        assertNull("bigger than the whole budget: not kept", LibraryCache.get<String>(LibraryCache.key(a, false, null), now = 0))
        assertNotNull("and nothing else was thrown out for it", LibraryCache.get<String>(k3, now = 0))
    }
}
