package com.aeriotv.android.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.EpgProgrammeDao
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.ChannelSnapshotEntity
import com.aeriotv.android.core.data.db.entity.EpgProgrammeEntity
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
        EpgProgrammeEntity::class,
        ChannelSnapshotEntity::class,
    ],
    version = 22,
    exportSchema = false,
)
abstract class AerioDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun localRecordingDao(): LocalRecordingDao
    abstract fun favoriteChannelDao(): FavoriteChannelDao
    abstract fun reminderDao(): ReminderDao
    abstract fun epgProgrammeDao(): EpgProgrammeDao
    abstract fun channelSnapshotDao(): ChannelSnapshotDao
}
