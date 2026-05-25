package com.aeriotv.android.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aeriotv.android.core.data.db.AerioDatabase
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v10 -> v11: add the nullable `dispatcharrProfileId` column to `playlists`
     * for per-playlist Dispatcharr channel-profile scoping. A real ALTER (vs.
     * the destructive fallback) so existing saved playlists, credentials, and
     * LAN URLs survive the upgrade. SQLite ADD COLUMN with no default leaves
     * existing rows NULL = "All Channels", which is the prior behaviour.
     */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN dispatcharrProfileId INTEGER")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AerioDatabase =
        Room.databaseBuilder(context, AerioDatabase::class.java, "aerio.db")
            // Preserve user data across known schema bumps where a clean ALTER
            // exists; fall back to a destructive rebuild only for un-mapped
            // version jumps (older dev builds).
            .addMigrations(MIGRATION_10_11)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun providePlaylistDao(db: AerioDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideWatchProgressDao(db: AerioDatabase): WatchProgressDao = db.watchProgressDao()

    @Provides
    fun provideLocalRecordingDao(db: AerioDatabase): LocalRecordingDao = db.localRecordingDao()

    @Provides
    fun provideFavoriteChannelDao(db: AerioDatabase): FavoriteChannelDao = db.favoriteChannelDao()

    @Provides
    fun provideReminderDao(db: AerioDatabase): ReminderDao = db.reminderDao()
}
