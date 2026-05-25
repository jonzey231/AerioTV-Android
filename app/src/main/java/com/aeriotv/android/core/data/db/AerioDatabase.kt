package com.aeriotv.android.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import com.aeriotv.android.core.data.db.entity.LocalRecordingEntity
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.ReminderEntity
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity

@Database(
    entities = [
        PlaylistEntity::class,
        WatchProgressEntity::class,
        LocalRecordingEntity::class,
        FavoriteChannelEntity::class,
        ReminderEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AerioDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun localRecordingDao(): LocalRecordingDao
    abstract fun favoriteChannelDao(): FavoriteChannelDao
    abstract fun reminderDao(): ReminderDao
}
