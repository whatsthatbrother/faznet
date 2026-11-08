package com.faznet.tv.data.db

import com.faznet.tv.data.M3uParser
import java.io.IOException
import java.io.InputStream

/**
 * Reads a playlist into a new list, then makes it the playlist's list in one step, so that the screens see either the
 * old list or the whole new one. Channels go in as they are read, without the list ever being in memory. The old list
 * stays until [removeUnusedLists]: a player open on it has to go on working. One import at a time, see ChannelRepository.
 */
class ChannelImporter(private val db: FaznetDatabase) {

    class Result(val listId: Long, val channels: Int, val groups: Int)

    /** Too few channels to take the place of a list: an empty answer from an expired account, a web page, a cut-off file. */
    class ListRefused(val channels: Int) : IOException("A list of $channels channels")

    /**
     * Blocks: call it from a background thread.
     * @param xtream the playlist belongs to an Xtream account, see [ChannelKeys]
     * @param minChannels with fewer than this the list already there stays, and this throws [ListRefused]
     * @param onProgress called now and then with the number of channels read so far
     */
    fun import(
        playlistId: String,
        input: InputStream,
        defaultGroup: String,
        xtream: Boolean,
        fingerprint: String?,
        sourceTag: String?,
        checkedAt: Long,
        minChannels: Int = 1,
        onProgress: (Int) -> Unit = {},
    ): Result {
        val dao = db.imports()
        val listId = nextListId()
        val base = ListIds.base(listId)
        val favorites = dao.favoriteKeys(playlistId).toHashSet()
        val watched = dao.historyKeys(playlistId).toHashSet()
        val favoriteLinks = HashMap<String, Long>()
        val historyLinks = HashMap<String, Long>()

        class GroupCount(val id: Long, val index: Int, val name: String) { var count = 0 }
        val groups = LinkedHashMap<String, GroupCount>()
        val batch = ArrayList<ChannelEntity>(BATCH)

        fun flush() {
            if (batch.isEmpty()) return
            db.runInTransaction { dao.insertChannels(batch) }
            batch.clear()
        }

        // Live channels only, numbered 1, 2, 3… without gaps. An Xtream list (m3u_plus) also holds every movie and
        // episode of the account, often ten times the live channels: they have their own screens (Movies, Series),
        // and leaving them out makes the import, the database and the memory on a TV stick that much smaller.
        var total = 0
        try {
            M3uParser.parse(input, defaultGroup) { c ->
                if (isOnDemand(c.url)) return@parse
                val number = ++total
                val g = groups.getOrPut(c.group) { (groups.size).let { GroupCount(ListIds.group(listId, it), it, c.group) } }
                val id = base + number
                batch.add(ChannelEntity(id, g.id, g.count, number, c.name, ChannelKeys.searchName(c.name), c.url, c.logo, c.userAgent, c.referrer))
                g.count++
                if (favorites.isNotEmpty() || watched.isNotEmpty()) {
                    val key = ChannelKeys.of(c.url, xtream)
                    if (key in favorites && key !in favoriteLinks) favoriteLinks[key] = id
                    if (key in watched && key !in historyLinks) historyLinks[key] = id
                }
                if (batch.size >= BATCH) {
                    flush()
                    onProgress(number)
                }
            }
        } catch (e: Exception) {
            // The old list stays; this one's channels have no group yet, so nothing can reach them.
            deleteChannelsOf(listId)
            throw e
        }
        flush()
        if (total < minChannels) {
            deleteChannelsOf(listId)
            throw ListRefused(total)
        }

        val previous = dao.stateNow(playlistId)
        db.runInTransaction {
            dao.insertGroups(groups.values.map { GroupEntity(it.id, listId, it.index, it.name, it.count) })
            dao.clearFavoriteLinks(playlistId)
            dao.clearHistoryLinks(playlistId)
            favoriteLinks.forEach { (key, id) -> dao.linkFavorite(playlistId, key, id) }
            historyLinks.forEach { (key, id) -> dao.linkHistory(playlistId, key, id) }
            dao.saveState(
                PlaylistStateEntity(playlistId, listId, total, fingerprint, checkedAt, previous?.lastChannelKey, sourceTag)
            )
        }
        return Result(listId, total, groups.size)
    }

    /** Lists an import left behind when the app was stopped half way, or whose playlist is gone. */
    fun removeUnusedLists() {
        val dao = db.imports()
        val active = dao.activeLists().toHashSet()
        for (list in dao.listsWithGroups()) if (list !in active) deleteList(list)
        // A list that never got as far as its groups: everything above the newest known list.
        val top = (dao.highestChannelId() ?: return) / ListIds.base(1)
        val known = (active + dao.listsWithGroups()).maxOrNull() ?: 0
        for (list in known + 1..top) deleteChannelsOf(list)
    }

    fun deleteList(listId: Long) {
        db.imports().deleteGroups(listId)
        deleteChannelsOf(listId)
    }

    private fun deleteChannelsOf(listId: Long) {
        val dao = db.imports()
        var from = ListIds.base(listId)
        val last = ListIds.last(listId)
        // Small steps: readers wait for a write to finish, and removing a big list at once takes a second or two.
        while (from < last) {
            val to = minOf(from + DELETE_STEP, last)
            dao.deleteChannels(from, to)
            if (to == last || dao.highestChannelId()?.let { it <= to } != false) break
            from = to
        }
    }

    private fun nextListId(): Long {
        val dao = db.imports()
        val byChannel = (dao.highestChannelId() ?: 0L) / ListIds.base(1)
        val byGroup = dao.highestGroupList() ?: 0L
        return maxOf(byChannel, byGroup) + 1
    }

    companion object {
        private const val BATCH = 2000
        private const val DELETE_STEP = 5000L

        private val ON_DEMAND = arrayOf("/movie/", "/series/")

        /**
         * A movie or a series episode of an Xtream server: …/movie/user/pass/id.ext or …/series/user/pass/id.ext, also
         * under a sub-path (…/xc/movie/u/p/1.mp4). A live channel whose name merely contains "movie" is not one: the
         * word has to be a path segment followed by exactly user, password and file.
         */
        fun isOnDemand(url: String): Boolean {
            for (kind in ON_DEMAND) {
                var at = url.indexOf(kind)
                while (at >= 0) {
                    val from = at + kind.length
                    val end = url.indexOf('?', from).let { if (it < 0) url.length else it }
                    var slashes = 0
                    for (i in from until end) if (url[i] == '/') slashes++
                    if (slashes == 2) return true
                    at = url.indexOf(kind, at + 1)
                }
            }
            return false
        }
    }
}
