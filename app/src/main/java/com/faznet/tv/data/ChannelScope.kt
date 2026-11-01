package com.faznet.tv.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.faznet.tv.data.db.ChannelEntity
import com.faznet.tv.data.db.FaznetDatabase
import com.faznet.tv.data.db.ListIds

/**
 * A list of channels the screens page through. Each kind reads a page straight from where it is in the table, so a page
 * deep in a 50 000-channel list costs the same few milliseconds as the first one.
 */
sealed class ChannelScope {
    abstract val size: Int

    abstract suspend fun read(db: FaznetDatabase, from: Int, count: Int): List<Channel>

    /** Where [ch] is in this list, or null if it is not in it. */
    abstract suspend fun indexOf(db: FaznetDatabase, ch: Channel): Int?

    protected fun List<ChannelEntity>.named(names: Map<Long, String>) = map { it.toChannel(names[it.groupId].orEmpty()) }

    /** Every channel of a list, in order. */
    class All(private val listId: Long, override val size: Int, private val names: Map<Long, String>) : ChannelScope() {
        override suspend fun read(db: FaznetDatabase, from: Int, count: Int) =
            db.channels().range(ListIds.base(listId), from + 1, from + 1 + count).named(names)

        override suspend fun indexOf(db: FaznetDatabase, ch: Channel) = (ch.number - 1).takeIf { it in 0 until size }
    }

    /** One group of a list. */
    class Group(private val groupId: Long, override val size: Int, private val names: Map<Long, String>) : ChannelScope() {
        override suspend fun read(db: FaznetDatabase, from: Int, count: Int) =
            db.channels().groupPage(groupId, from, from + count).named(names)

        override suspend fun indexOf(db: FaznetDatabase, ch: Channel) =
            db.channels().byId(ch.id)?.takeIf { it.groupId == groupId }?.position
    }

    /** Chosen channels in a given order: favourites, recently watched. */
    class Ids(private val ids: List<Long>, private val names: Map<Long, String>) : ChannelScope() {
        override val size get() = ids.size

        override suspend fun read(db: FaznetDatabase, from: Int, count: Int): List<Channel> {
            val wanted = ids.subList(from.coerceAtMost(ids.size), (from + count).coerceAtMost(ids.size))
            if (wanted.isEmpty()) return emptyList()
            val rows = db.channels().byIds(wanted).associateBy { it.id }
            return wanted.mapNotNull { rows[it] }.named(names)
        }

        override suspend fun indexOf(db: FaznetDatabase, ch: Channel) = ids.indexOf(ch.id).takeIf { it >= 0 }
    }

    /** A list that is already here: search results. */
    class Fixed(private val channels: List<Channel>) : ChannelScope() {
        override val size get() = channels.size

        override suspend fun read(db: FaznetDatabase, from: Int, count: Int) =
            channels.subList(from.coerceAtMost(size), (from + count).coerceAtMost(size))

        override suspend fun indexOf(db: FaznetDatabase, ch: Channel) = channels.indexOfFirst { it.url == ch.url }.takeIf { it >= 0 }
    }

    /** Every channel except the groups a parental lock hides; the only kind that has to count its way to a page. */
    class AllExcept(
        private val listId: Long,
        override val size: Int,
        private val names: Map<Long, String>,
        private val blocked: List<Long>,
    ) : ChannelScope() {
        override suspend fun read(db: FaznetDatabase, from: Int, count: Int) =
            db.channels().rangeExcept(ListIds.base(listId), ListIds.last(listId), blocked, count, from).named(names)

        override suspend fun indexOf(db: FaznetDatabase, ch: Channel): Int? {
            val row = db.channels().byId(ch.id) ?: return null
            return if (row.groupId in blocked) null else db.channels().countBefore(ListIds.base(listId), ch.id, blocked)
        }
    }
}

/** Pages of a [ChannelScope] for the lazy grids. Jumps straight to a position, so focus can return to a channel far down. */
class ChannelPagingSource(private val db: FaznetDatabase, private val scope: ChannelScope) : PagingSource<Int, Channel>() {

    override val jumpingSupported get() = true

    override fun getRefreshKey(state: PagingState<Int, Channel>): Int? =
        state.anchorPosition?.let { (it - state.config.initialLoadSize / 2).coerceAtLeast(0) }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Channel> {
        val key = (params.key ?: 0).coerceAtLeast(0)
        // Asked for what lies before a position: read the page that ends there.
        val start = if (params is LoadParams.Prepend) (key - params.loadSize).coerceAtLeast(0) else key
        val end = if (params is LoadParams.Prepend) key else (key + params.loadSize).coerceAtMost(scope.size)
        val page = if (start < end) scope.read(db, start, end - start) else emptyList()
        return LoadResult.Page(
            data = page,
            prevKey = start.takeIf { it > 0 },
            nextKey = (start + page.size).takeIf { it < scope.size && page.isNotEmpty() },
            itemsBefore = start,
            itemsAfter = (scope.size - start - page.size).coerceAtLeast(0),
        )
    }
}
