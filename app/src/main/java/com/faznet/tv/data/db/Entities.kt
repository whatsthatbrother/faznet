package com.faznet.tv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.faznet.tv.data.Channel

/**
 * Each import of a playlist writes a new "list" (a number); the playlist points at the finished one, so readers never
 * see a half-written list and a failed download leaves the old one in place.
 *
 * Ids are computed, not generated: a channel's id is [ListIds.base] of its list plus its number. That makes the
 * whole list one range of the primary key, so "All channels" needs no index of its own, and writing is as fast as
 * SQLite can append.
 */
object ListIds {
    /** Far more than any playlist has; the id of a list's first channel is `base(list) + 1`. */
    private const val SPAN = 4_000_000L
    private const val GROUP_SPAN = 200_000L

    fun base(listId: Long): Long = listId * SPAN

    fun last(listId: Long): Long = base(listId) + SPAN - 1

    /** The [index]th group (from 0) of a list. */
    fun group(listId: Long, index: Int): Long = listId * GROUP_SPAN + index
}

@Entity(tableName = "channel", indices = [Index(value = ["groupId", "position"], unique = true)])
data class ChannelEntity(
    @PrimaryKey val id: Long,
    val groupId: Long,
    /** Place inside its group, counted from 0 without gaps: a page of a group is a range of this. */
    val position: Int,
    val number: Int,
    val name: String,
    /** See [ChannelKeys.searchName]: null when [name] can be searched as it is. */
    val searchName: String?,
    val url: String,
    val logo: String?,
    val userAgent: String?,
    val referrer: String?,
) {
    fun toChannel(groupName: String) = Channel(number, name, url, logo, groupName, userAgent, referrer, id)
}

@Entity(tableName = "channel_group", indices = [Index(value = ["listId", "position"], unique = true)])
data class GroupEntity(
    @PrimaryKey val id: Long,
    val listId: Long,
    val position: Int,
    val name: String,
    val channelCount: Int,
)

/**
 * Favourites and recently watched survive a new import because they point at a channel by [key], which does not depend
 * on the account in the address (see [ChannelKeys]). [channelId] is that channel in the active list, filled in by each
 * import; null when the playlist no longer has it, and it comes back if the channel does.
 */
@Entity(tableName = "favorite", primaryKeys = ["playlistId", "key"])
data class FavoriteEntity(
    val playlistId: String,
    val key: String,
    val channelId: Long?,
    val addedAt: Long,
)

@Entity(tableName = "history", primaryKeys = ["playlistId", "key"])
data class HistoryEntity(
    val playlistId: String,
    val key: String,
    val channelId: Long?,
    val watchedAt: Long,
)

/** What is known about the channel list of a playlist. The playlists themselves stay with [com.faznet.tv.data.Repository]. */
@Entity(tableName = "playlist_state")
data class PlaylistStateEntity(
    @PrimaryKey val playlistId: String,
    /** The finished list readers use; null until the first import is done. */
    val listId: Long?,
    val channelCount: Int,
    /** Fingerprint of the imported file: the same content is not imported again. */
    val fingerprint: String?,
    val checkedAt: Long,
    val lastChannelKey: String?,
    /** Stands for the playlist's address and account: when these change, the list is another one. */
    val sourceTag: String?,
)
