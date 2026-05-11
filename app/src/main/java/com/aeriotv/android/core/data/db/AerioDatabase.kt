package com.aeriotv.android.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity

@Database(
    entities = [PlaylistEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AerioDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
}
