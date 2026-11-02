package com.faznet.tv.data

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.faznet.tv.Perf
import com.faznet.tv.R
import com.faznet.tv.data.db.ChannelImporter
import com.faznet.tv.data.db.ChannelKeys
import com.faznet.tv.data.db.FavoriteEntity
import com.faznet.tv.data.db.FaznetDatabase
import com.faznet.tv.data.db.GroupEntity
import com.faznet.tv.data.db.HistoryEntity
import com.faznet.tv.data.db.ListIds
import com.faznet.tv.data.db.PlaylistStateEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Channel lists, favourites and recently watched, in the database. The playlists themselves and the settings are
 * still with [Repository]. Everything big happens on background threads, and nothing here holds a whole list.
 */
@Singleton
class ChannelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FaznetDatabase,
    private val repo: Repository,
) {
    private val channels = db.channels()
    private val library = db.library()
    private val importer = ChannelImporter(db)

    /**
     * What changes which lists exist goes one at a time. Two imports at once would take the same list number and wipe
     * each other's channels, and to the clean-up a running import looks like what an interrupted one left behind.
     */
    private val listLock = Mutex()

    /** How far the running import has come, in channels read; null when none is running. */
    private val importing = MutableStateFlow<Int?>(null)
    val importProgress: StateFlow<Int?> = importing.asStateFlow()

    /** The groups of a list as the user may see them right now, and how to read the lists in it. */
    inner class ListView(val listId: Long, val groups: List<GroupEntity>, private val locked: Set<String>) {
        private val names: Map<Long, String> = groups.associate { it.id to it.name }
        private val blocked: List<Long> = groups.filter { it.name in locked }.map { it.id }

        /** The groups that are not locked. */
        val openGroups: List<GroupEntity> get() = if (locked.isEmpty()) groups else groups.filter { it.name !in locked }

        val channelCount: Int get() = openGroups.sumOf { it.channelCount }

        fun isLocked(group: String) = group in locked

        fun all(): ChannelScope =
            if (blocked.isEmpty()) ChannelScope.All(listId, channelCount, names)
            else ChannelScope.AllExcept(listId, channelCount, names, blocked)

        fun group(name: String): ChannelScope? = groups.firstOrNull { it.name == name }?.let { ChannelScope.Group(it.id, it.channelCount, names) }

        /** Favourites by channel number. */
        suspend fun favorites(p: Playlist): ChannelScope =
            ChannelScope.Ids(open(library.favoritesNow(p.id).mapNotNull { it.channelId }.sorted()), names)

        /** Recently watched, the last one first. */
        suspend fun recents(p: Playlist): ChannelScope =
            ChannelScope.Ids(open(library.historyNow(p.id).mapNotNull { it.channelId }), names)

        fun search(results: List<Channel>): ChannelScope =
            ChannelScope.Fixed(if (locked.isEmpty()) results else results.filter { it.group !in locked })

        /** Without the channels of locked groups and of older lists. */
        private suspend fun open(ids: List<Long>): List<Long> {
            val inList = ids.filter { it > ListIds.base(listId) && it <= ListIds.last(listId) }
            if (blocked.isEmpty() || inList.isEmpty()) return inList
            val allowed = HashSet<Long>()
            for (part in inList.chunked(500)) channels.byIds(part).filterTo(ArrayList()) { it.groupId !in blocked }.mapTo(allowed) { it.id }
            return inList.filter { it in allowed }
        }
    }

    fun state(playlistId: String): Flow<PlaylistStateEntity?> = library.state(playlistId)

    fun groups(listId: Long): Flow<List<GroupEntity>> = channels.groups(listId)

    fun favoriteIds(playlistId: String): Flow<List<Long>> =
        library.favorites(playlistId).map { rows -> rows.mapNotNull { it.channelId } }

    fun recentIds(playlistId: String): Flow<List<Long>> =
        library.history(playlistId).map { rows -> rows.mapNotNull { it.channelId } }

    suspend fun listView(listId: Long): ListView {
        val locked = if (Parental.unlocked || !Parental.enabled(context)) emptySet() else Parental.lockedGroups(context)
        return ListView(listId, channels.groupsNow(listId), locked)
    }

    suspend fun activeList(playlistId: String): Long? = library.stateNow(playlistId)?.listId

    // ---------- reading ----------

    fun pages(scope: ChannelScope): Flow<PagingData<Channel>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE,
                prefetchDistance = PAGE / 2,
                initialLoadSize = PAGE * 2,
                enablePlaceholders = true,
                // At most this many channels in memory, however far the user scrolls.
                maxSize = PAGE * 8,
                jumpThreshold = PAGE * 2,
            ),
            pagingSourceFactory = { ChannelPagingSource(db, scope) },
        ).flow

    fun pagesOf(scope: ChannelScope) = ChannelPages(db, scope)

    /** A whole list, for what needs all of it at once: checking a group. */
    suspend fun channelsOf(scope: ChannelScope): List<Channel> = withContext(Dispatchers.IO) {
        val out = ArrayList<Channel>(scope.size)
        while (out.size < scope.size) {
            val page = scope.read(db, out.size, minOf(WHOLE_LIST_STEP, scope.size - out.size))
            if (page.isEmpty()) break
            out.addAll(page)
        }
        out
    }

    suspend fun indexIn(scope: ChannelScope, ch: Channel): Int? = scope.indexOf(db, ch)

    /** Names that contain what was typed, in the whole list. */
    suspend fun search(view: ListView, typed: String): ChannelScope = withContext(Dispatchers.IO) {
        val escaped = ChannelKeys.normalize(typed.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val names = view.groups.associate { it.id to it.name }
        val found = channels.search(ListIds.base(view.listId), ListIds.last(view.listId), "%$escaped%", SEARCH_LIMIT)
            .map { it.toChannel(names[it.groupId].orEmpty()) }
        view.search(found)
    }

    suspend fun channelByNumber(listId: Long, number: Int, groupNames: Map<Long, String>): Channel? =
        channels.byId(ListIds.base(listId) + number)?.let { it.toChannel(groupNames[it.groupId].orEmpty()) }

    /** The channel last watched on this playlist, in the given list. */
    suspend fun lastChannel(p: Playlist, view: ListView): Channel? {
        val key = library.stateNow(p.id)?.lastChannelKey ?: return null
        val id = library.historyNow(p.id).firstOrNull { it.key == key }?.channelId ?: return null
        if (id <= ListIds.base(view.listId) || id > ListIds.last(view.listId)) return null
        val row = channels.byId(id) ?: return null
        val group = view.groups.firstOrNull { it.id == row.groupId }?.name.orEmpty()
        return if (view.isLocked(group)) null else row.toChannel(group)
    }

    // ---------- favourites, recently watched ----------

    /** @return true if the channel is now a favourite. */
    suspend fun toggleFavorite(p: Playlist, ch: Channel): Boolean {
        val key = keyOf(p, ch)
        if (library.removeFavorite(p.id, key) > 0) return false
        library.addFavorites(listOf(FavoriteEntity(p.id, key, ch.id.takeIf { it != 0L }, System.currentTimeMillis())))
        return true
    }

    suspend fun isFavorite(p: Playlist, ch: Channel): Boolean {
        val key = keyOf(p, ch)
        return library.favoritesNow(p.id).any { it.key == key }
    }

    suspend fun markWatched(p: Playlist, ch: Channel) {
        val key = keyOf(p, ch)
        db.withTransaction {
            library.addHistory(listOf(HistoryEntity(p.id, key, ch.id.takeIf { it != 0L }, System.currentTimeMillis())))
            library.trimHistory(p.id, HISTORY_SIZE)
            library.setLastChannel(p.id, key)
        }
    }

    private fun keyOf(p: Playlist, ch: Channel) = ChannelKeys.of(ch.url, XtreamAccount.from(p) != null)

    // ---------- bringing a list up to date ----------

    /** How old the list of a playlist that can be downloaded again is; files on the device never get old. */
    suspend fun ageMs(p: Playlist): Long {
        if (p.m3uUrl() == null) return 0
        val checked = library.stateNow(p.id)?.checkedAt ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - checked
    }

    /**
     * Makes sure the playlist has a list: from the database when there is one (nothing to do, however big it is),
     * otherwise from the copy on the device or from the server. With [force] it asks the server, and imports only
     * if the server sent something new.
     * @return the list, and whether it changed. A failed download throws and leaves the old list in place.
     */
    suspend fun ensureList(p: Playlist, force: Boolean): ListResult {
        // A list that is there needs nothing, so it does not have to wait for another playlist's import.
        if (!force) {
            val state = library.stateNow(p.id)
            val list = state?.listId
            if (list != null && state.sourceTag == sourceTag(p)) return ListResult(list, state.channelCount, changed = false)
        }
        return listLock.withLock { withContext(Dispatchers.IO) { bringUpToDate(p, force) } }
    }

    private suspend fun CoroutineScope.bringUpToDate(p: Playlist, force: Boolean): ListResult {
        migrateFromPreferences(p)
        var state = library.stateNow(p.id)!!
        // The address or the account changed: the old list is another playlist's, favourites stay.
        val tag = sourceTag(p)
        if (state.sourceTag != tag) {
            state = state.copy(listId = null, channelCount = 0, fingerprint = null, checkedAt = 0, sourceTag = tag)
            library.saveState(state)
        }
        val existing = state.listId
        if (!force && existing != null) return ListResult(existing, state.channelCount, changed = false)

        val file = repo.cacheFile(p.id)
        val url = p.m3uUrl()
        if (url != null && (force || !file.exists() || file.length() == 0L)) repo.download(url, file)
        if (!file.exists()) throw java.io.IOException("Playlist file missing")

        val now = System.currentTimeMillis()
        val fingerprint = fingerprint(file)
        if (existing != null && state.fingerprint == fingerprint) {
            library.setChecked(p.id, now)
            if (url != null) file.delete()
            return ListResult(existing, state.channelCount, changed = false)
        }

        importing.value = 0
        try {
            val result = Perf.measure("import") {
                file.inputStream().use { input ->
                    importer.import(
                        p.id, input, context.getString(R.string.group_uncategorized), XtreamAccount.from(p) != null,
                        fingerprint, tag, now,
                    ) {
                        ensureActive() // another playlist was chosen: stop here, it would only have to wait
                        importing.value = it
                    }
                }
            }
            Perf.log("import ${result.channels} channels, ${result.groups} groups")
            // A list that can be downloaded again needs no copy of the file: the database is the copy.
            if (url != null) file.delete()
            repo.deleteOldCopy(p.id)
            return ListResult(result.listId, result.channels, changed = true)
        } finally {
            importing.value = null
        }
    }

    /**
     * What an interrupted import left behind, lists a newer one replaced, and everything of playlists that no longer
     * exist. Only while no screen is reading an older list: the player and the guide keep the one they were opened with.
     */
    suspend fun cleanUp() = listLock.withLock {
        withContext(Dispatchers.IO) {
            val known = repo.playlists().mapTo(HashSet()) { it.id }
            for (id in library.playlistsWithState()) {
                if (id !in known) {
                    library.stateNow(id)?.listId?.let { importer.deleteList(it) }
                    library.deleteFavorites(id)
                    library.deleteHistory(id)
                    library.deleteState(id)
                }
            }
            importer.removeUnusedLists()
        }
    }

    /** Favourites and recents of a playlist from before there was a database, once. The old values are left alone. */
    private suspend fun migrateFromPreferences(p: Playlist) {
        if (library.stateNow(p.id) != null) return
        val xtream = XtreamAccount.from(p) != null
        val now = System.currentTimeMillis()
        val favorites = repo.favorites(p.id).map { FavoriteEntity(p.id, ChannelKeys.of(it, xtream), null, now) }
        // That list holds the newest first: earlier in the list means a later time.
        val recents = repo.recents(p.id).mapIndexed { i, url -> HistoryEntity(p.id, ChannelKeys.of(url, xtream), null, now - i) }
        val last = repo.lastChannel(p.id)?.let { ChannelKeys.of(it, xtream) }
        db.withTransaction {
            library.addFavorites(favorites.distinctBy { it.key })
            library.addHistory(recents.distinctBy { it.key })
            library.saveState(PlaylistStateEntity(p.id, null, 0, null, 0, last, sourceTag(p)))
        }
    }

    private fun sourceTag(p: Playlist) = "${p.type}|${p.source}|${p.username}|${p.password}".hashCode().toString(36)

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    class ListResult(val listId: Long, val channels: Int, val changed: Boolean)

    private companion object {
        const val PAGE = 60
        const val WHOLE_LIST_STEP = 2000
        const val SEARCH_LIMIT = 500
        const val HISTORY_SIZE = 40
    }
}
