package com.faznet.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel_group WHERE listId = :listId ORDER BY position")
    fun groups(listId: Long): Flow<List<GroupEntity>>

    @Query("SELECT * FROM channel_group WHERE listId = :listId ORDER BY position")
    suspend fun groupsNow(listId: Long): List<GroupEntity>

    /** A page of "All channels": channel numbers [fromNumber] up to, not including, [toNumber]. */
    @Query("SELECT * FROM channel WHERE id > :base + :fromNumber - 1 AND id < :base + :toNumber ORDER BY id")
    suspend fun range(base: Long, fromNumber: Int, toNumber: Int): List<ChannelEntity>

    /** A page of "All channels" with a parental lock on: the rows have to be counted, so this is slower far down. */
    @Query("SELECT * FROM channel WHERE id > :first AND id <= :last AND groupId NOT IN (:blocked) ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun rangeExcept(first: Long, last: Long, blocked: List<Long>, limit: Int, offset: Int): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channel WHERE id > :first AND id < :id AND groupId NOT IN (:blocked)")
    suspend fun countBefore(first: Long, id: Long, blocked: List<Long>): Int

    /** A page of a group: places [from] up to, not including, [to]. */
    @Query("SELECT * FROM channel WHERE groupId = :groupId AND position >= :from AND position < :to ORDER BY position")
    suspend fun groupPage(groupId: Long, from: Int, to: Int): List<ChannelEntity>

    /** [pattern] is a LIKE pattern over the normalized name. */
    @Query(
        "SELECT * FROM channel WHERE id > :first AND id <= :last AND COALESCE(searchName, name) LIKE :pattern ESCAPE '\\' " +
            "ORDER BY id LIMIT :limit"
    )
    suspend fun search(first: Long, last: Long, pattern: String, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

}

/** Used by the import only, from its own thread: everything here runs inside short transactions it controls. */
@Dao
interface ImportDao {
    @Insert
    fun insertChannels(rows: List<ChannelEntity>)

    @Insert
    fun insertGroups(rows: List<GroupEntity>)

    @Query("SELECT MAX(listId) FROM channel_group")
    fun highestGroupList(): Long?

    @Query("SELECT MAX(id) FROM channel")
    fun highestChannelId(): Long?

    @Query("SELECT key FROM favorite WHERE playlistId = :playlistId")
    fun favoriteKeys(playlistId: String): List<String>

    @Query("SELECT key FROM history WHERE playlistId = :playlistId")
    fun historyKeys(playlistId: String): List<String>

    @Query("UPDATE favorite SET channelId = NULL WHERE playlistId = :playlistId")
    fun clearFavoriteLinks(playlistId: String)

    @Query("UPDATE history SET channelId = NULL WHERE playlistId = :playlistId")
    fun clearHistoryLinks(playlistId: String)

    @Query("UPDATE favorite SET channelId = :channelId WHERE playlistId = :playlistId AND key = :key")
    fun linkFavorite(playlistId: String, key: String, channelId: Long)

    @Query("UPDATE history SET channelId = :channelId WHERE playlistId = :playlistId AND key = :key")
    fun linkHistory(playlistId: String, key: String, channelId: Long)

    @Query("SELECT * FROM playlist_state WHERE playlistId = :playlistId")
    fun stateNow(playlistId: String): PlaylistStateEntity?

    @Upsert
    fun saveState(state: PlaylistStateEntity)

    /** Part of a list, in small steps so that readers are never held up for long. @return rows removed. */
    @Query("DELETE FROM channel WHERE id > :from AND id <= :to")
    fun deleteChannels(from: Long, to: Long): Int

    @Query("DELETE FROM channel_group WHERE listId = :listId")
    fun deleteGroups(listId: Long)

    @Query("SELECT listId FROM playlist_state WHERE listId IS NOT NULL")
    fun activeLists(): List<Long>

    @Query("SELECT DISTINCT listId FROM channel_group")
    fun listsWithGroups(): List<Long>
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM playlist_state WHERE playlistId = :playlistId")
    fun state(playlistId: String): Flow<PlaylistStateEntity?>

    @Query("SELECT playlistId FROM playlist_state")
    suspend fun playlistsWithState(): List<String>

    @Query("SELECT * FROM playlist_state WHERE playlistId = :playlistId")
    suspend fun stateNow(playlistId: String): PlaylistStateEntity?

    @Upsert
    suspend fun saveState(state: PlaylistStateEntity)

    @Query("UPDATE playlist_state SET checkedAt = :at WHERE playlistId = :playlistId")
    suspend fun setChecked(playlistId: String, at: Long)

    @Query("UPDATE playlist_state SET lastChannelKey = :key WHERE playlistId = :playlistId")
    suspend fun setLastChannel(playlistId: String, key: String)

    @Query("SELECT * FROM favorite WHERE playlistId = :playlistId")
    fun favorites(playlistId: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite WHERE playlistId = :playlistId")
    suspend fun favoritesNow(playlistId: String): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorites(rows: List<FavoriteEntity>)

    @Query("DELETE FROM favorite WHERE playlistId = :playlistId AND key = :key")
    suspend fun removeFavorite(playlistId: String, key: String): Int

    @Query("SELECT * FROM history WHERE playlistId = :playlistId ORDER BY watchedAt DESC")
    fun history(playlistId: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE playlistId = :playlistId ORDER BY watchedAt DESC")
    suspend fun historyNow(playlistId: String): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addHistory(rows: List<HistoryEntity>)

    /** Keeps the [keep] most recent. */
    @Query(
        "DELETE FROM history WHERE playlistId = :playlistId AND key NOT IN " +
            "(SELECT key FROM history WHERE playlistId = :playlistId ORDER BY watchedAt DESC LIMIT :keep)"
    )
    suspend fun trimHistory(playlistId: String, keep: Int)

    @Query("DELETE FROM favorite WHERE playlistId = :playlistId")
    suspend fun deleteFavorites(playlistId: String)

    @Query("DELETE FROM history WHERE playlistId = :playlistId")
    suspend fun deleteHistory(playlistId: String)

    @Query("DELETE FROM playlist_state WHERE playlistId = :playlistId")
    suspend fun deleteState(playlistId: String)
}
