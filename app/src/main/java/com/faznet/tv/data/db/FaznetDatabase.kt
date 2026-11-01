package com.faznet.tv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Channel lists, favourites and recently watched. Lists are read a page at a time, so a playlist of any size costs the
 * screens only what they show. On a TV stick a page of a 50 000-channel list takes a few milliseconds.
 */
@Database(
    entities = [ChannelEntity::class, GroupEntity::class, FavoriteEntity::class, HistoryEntity::class, PlaylistStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FaznetDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun imports(): ImportDao
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): FaznetDatabase =
            Room.databaseBuilder(context.applicationContext, FaznetDatabase::class.java, "faznet.db")
                // Readers carry on while an import writes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
