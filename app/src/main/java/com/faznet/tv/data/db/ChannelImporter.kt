package com.faznet.tv.data.db

import com.faznet.tv.data.M3uParser
import java.io.InputStream

/**
 * Reads a playlist into a new list, then makes it the playlist's list in one step, so that the screens see either the
 * old list or the whole new one. Channels go in as they are read, without the list ever being in memory.
 */
class ChannelImporter(private val db: FaznetDatabase) {

    class Result(val listId: Long, val channels: Int, val groups: Int)

    /**
     * Blocks: call it from a background thread.
     * @param xtream the playlist belongs to an Xtream account, see [ChannelKeys]
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

        val total = try {
            M3uParser.parse(input, defaultGroup) { c ->
                val g = groups.getOrPut(c.group) { (groups.size).let { GroupCount(ListIds.group(listId, it), it, c.group) } }
                val id = base + c.number
                batch.add(ChannelEntity(id, g.id, g.count, c.number, c.name, ChannelKeys.searchName(c.name), c.url, c.logo, c.userAgent, c.referrer))
                g.count++
                if (favorites.isNotEmpty() || watched.isNotEmpty()) {
                    val key = ChannelKeys.of(c.url, xtream)
                    if (key in favorites) favoriteLinks.putIfAbsent(key, id)
                    if (key in watched) historyLinks.putIfAbsent(key, id)
                }
                if (batch.size >= BATCH) {
                    flush()
                    onProgress(c.number)
                }
            }
        } catch (e: Exception) {
            // The old list stays; this one's channels have no group yet, so nothing can reach them.
            deleteChannelsOf(listId)
            throw e
        }
        flush()

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
        previous?.listId?.let { deleteList(it) }
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

    private companion object {
        const val BATCH = 2000
        const val DELETE_STEP = 5000L
    }
}
