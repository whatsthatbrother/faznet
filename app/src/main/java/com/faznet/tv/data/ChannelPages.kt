package com.faznet.tv.data

import com.faznet.tv.data.db.FaznetDatabase

/**
 * A list of channels for screens that move through it by place, holding only the pages near where they are: a list of
 * 175 000 channels read whole took 4 s and 60 MB on a phone. For the main thread only.
 */
class ChannelPages(private val db: FaznetDatabase, val scope: ChannelScope) {

    val size get() = scope.size

    private val pages = object : LinkedHashMap<Int, List<Channel>>(MAX_PAGES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Channel>>) = size > MAX_PAGES
    }

    /** The channel at [i] if its page is here. */
    fun peek(i: Int): Channel? = pages[i / PAGE]?.getOrNull(i % PAGE)

    /** True if the page of [i] has been read: [peek] then says all there is to know about that place. */
    fun isRead(i: Int): Boolean = pages.containsKey(i / PAGE)

    /** The channel at [i], reading its page if needed. */
    suspend fun get(i: Int): Channel? {
        if (i !in 0 until size) return null
        val page = i / PAGE
        val rows = pages[page] ?: scope.read(db, page * PAGE, PAGE).also { pages[page] = it }
        return rows.getOrNull(i % PAGE)
    }

    suspend fun indexOf(ch: Channel): Int? = scope.indexOf(db, ch)

    companion object {
        const val PAGE = 60
        private const val MAX_PAGES = 12
    }
}
