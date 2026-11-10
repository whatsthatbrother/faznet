package com.faznet.tv.data

/**
 * The movie and series lists already downloaded in this session, so going back to a category (or opening Movies again)
 * does not download it again. Bounded by the number of titles kept, not by entries: "All" can be tens of thousands.
 * Emptied when Android asks for memory ([FaznetApp.onTrimMemory]).
 */
object LibraryCache {

    private class Entry(val at: Long, val list: List<Any>)

    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var titles = 0

    /** Tens of thousands of titles are some 15-20 MB; the stick has a 384 MB heap and a player to run. */
    internal var maxTitles = 60_000
    private const val TTL_MS = 30 * 60 * 1000L

    fun key(a: XtreamAccount, series: Boolean, category: String?) =
        "${a.server}\n${a.user}\n${if (series) "s" else "v"}\n${category.orEmpty()}"

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T : Any> get(key: String, now: Long = System.currentTimeMillis()): List<T>? {
        val e = map[key] ?: return null
        if (now - e.at > TTL_MS) { remove(key); return null }
        return e.list as List<T>
    }

    /** Only answers the server gave: a failure is asked again next time. */
    @Synchronized
    fun put(key: String, list: List<Any>, now: Long = System.currentTimeMillis()) {
        remove(key)
        if (list.size > maxTitles) return
        map[key] = Entry(now, list)
        titles += list.size
        val it = map.entries.iterator()
        while (titles > maxTitles && it.hasNext()) {
            val eldest = it.next()
            titles -= eldest.value.list.size
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        titles = 0
    }

    private fun remove(key: String) {
        map.remove(key)?.let { titles -= it.list.size }
    }
}
