package com.aeriotv.android.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity

@Database(
    entities = [PlaylistEntity::class, WatchProgressEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AerioDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun watchProgressDao(): WatchProgressDao
}
