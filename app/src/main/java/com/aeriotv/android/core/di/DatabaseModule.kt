package com.aeriotv.android.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aeriotv.android.core.data.db.AerioDatabase
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.EncryptingPlaylistDao
import com.aeriotv.android.core.data.db.dao.EpgProgrammeDao
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.security.CredentialCipher
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

    /**
     * v11 -> v12: add episode metadata + the up-next queue to `watch_progress`
     * (iOS Issue #19 parity). Clean ALTERs so existing resume positions survive.
     * Defaults keep pre-v12 rows behaving as movies with no queue.
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN vodType TEXT NOT NULL DEFAULT 'movie'")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN seriesId TEXT")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN seasonNumber INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN episodeNumber INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN streamUrl TEXT")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN isFinished INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN upNextQueue TEXT")
        }
    }

    /**
     * v12 -> v13: add the `epg_programme` disk cache (iOS GuideStore parity) so a
     * relaunch can paint now-playing + the guide instantly from the last fetch.
     * A CREATE TABLE (not the destructive fallback, which would drop the user's
     * playlists / favorites / watch-progress) since those tables are untouched.
     * The table + index definitions must match what Room generates for
     * EpgProgrammeEntity, including the auto-named indices.
     */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_programme` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`playlistId` TEXT NOT NULL, " +
                    "`channelId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`startMillis` INTEGER NOT NULL, " +
                    "`endMillis` INTEGER NOT NULL, " +
                    "`category` TEXT NOT NULL, " +
                    "`dispatcharrProgramId` INTEGER, " +
                    "`fetchedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programme_playlistId` ON `epg_programme` (`playlistId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programme_playlistId_channelId` ON `epg_programme` (`playlistId`, `channelId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programme_endMillis` ON `epg_programme` (`endMillis`)")
        }
    }

    /**
     * v13 -> v14: add the `channel_snapshot` disk cache (the iOS ChannelStore
     * snapshot pattern, sister to the Phase 121 `epg_programme` cache). So a
     * cold relaunch can paint the channel rail + cells from disk instantly
     * instead of staring at an empty rail for ~10-20s while
     * PlaylistRepository.refresh() round-trips the Dispatcharr / M3U fetch.
     * Additive CREATE TABLE (not the destructive fallback) so playlists /
     * favorites / EPG cache / watch progress survive. The table + index
     * definitions must EXACTLY match what Room generates for
     * ChannelSnapshotEntity (Phase 121 GOTCHA): autogen Long PK = "INTEGER NOT
     * NULL ... PRIMARY KEY(`id`)" with no AUTOINCREMENT, nullable columns
     * declared without NOT NULL, single-column index named
     * `index_<table>_<column>`.
     */
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `channel_snapshot` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`playlistId` TEXT NOT NULL, " +
                    "`channelId` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`groupTitle` TEXT NOT NULL, " +
                    "`tvgID` TEXT NOT NULL, " +
                    "`tvgName` TEXT NOT NULL, " +
                    "`tvgLogo` TEXT NOT NULL, " +
                    "`channelNumber` TEXT, " +
                    "`dispatcharrChannelId` INTEGER, " +
                    "`fetchedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_snapshot_playlistId` ON `channel_snapshot` (`playlistId`)")
        }
    }

    /**
     * v15: capture the Dispatcharr account level on each playlist so the Record
     * affordances can be hidden for Standard / Streamer (non-admin) accounts,
     * which 403 on server-side DVR. Additive ALTER (not destructive) so
     * playlists / favorites / caches survive. Default 10 = admin keeps every
     * existing row recording-capable until its next connect re-captures the
     * real level.
     */
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `playlists` ADD COLUMN `dispatcharrUserLevel` INTEGER NOT NULL DEFAULT 10",
            )
        }
    }

    /**
     * v15 -> v16: per-playlist On Demand opt-in column. Mirrors iOS
     * `ServerConnection.vodEnabled`. Default 1 (true) so every existing
     * playlist keeps fetching VOD as before; users only toggle it off at
     * Add / Edit Playlist. Same Boolean-as-INTEGER pattern as the rest of
     * the Room schema; the `@ColumnInfo(defaultValue = "1")` on the entity
     * MUST match the SQL `DEFAULT 1` here or Room rejects the post-upgrade
     * schema on open.
     */
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `playlists` ADD COLUMN `vodEnabled` INTEGER NOT NULL DEFAULT 1",
            )
        }
    }

    /**
     * v16 -> v17: capture the connected Dispatcharr account's ASSIGNED Channel
     * Profile id(s) per playlist (/api/accounts/users/me/ `channel_profiles`,
     * comma-joined) for the FAIL-CLOSED child-safety channel filter (iOS commit
     * 3eb4ae3d8). Additive ALTER (not destructive) so playlists / favorites /
     * caches survive. TEXT NOT NULL DEFAULT '' = no account profile = show all,
     * so every existing row keeps its current behaviour until its next load
     * self-heals the real value. The `@ColumnInfo(defaultValue = "")` on
     * PlaylistEntity MUST match the SQL `DEFAULT ''` here or Room rejects the
     * post-upgrade schema on open (same rule as dispatcharrUserLevel / vodEnabled).
     *
     * Also clears the channel-snapshot cache: a pre-v17 snapshot was written
     * UNFILTERED, so without this the first post-upgrade cold launch would paint
     * the stale all-channels rail before the first whoami self-heals -- a brief
     * but real child-safety leak on a kids device. Clearing it forces one fresh
     * (filtered) load; the cache is rewritten filtered on that load.
     */
    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `playlists` ADD COLUMN `dispatcharrAccountProfileIds` TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL("DELETE FROM `channel_snapshot`")
        }
    }

    /** Catch-up (task #133): per-channel archive window + timeshift stream id
     *  on the channel snapshot so catch-up affordances survive cache-restored
     *  launches. Defaults (0 / NULL) mean "no catch-up" until the next network
     *  refresh repopulates the cache with real values. */
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `channel_snapshot` ADD COLUMN `catchupDays` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `channel_snapshot` ADD COLUMN `catchupStreamId` TEXT",
            )
        }
    }

    /** Catch-up EPG history (task #135): per-playlist retention window for
     *  already-aired guide rows. DEFAULT 7 must match the entity's
     *  @ColumnInfo(defaultValue = "7"). */
    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `playlists` ADD COLUMN `epgRetentionDays` INTEGER NOT NULL DEFAULT 7",
            )
        }
    }

    /** Task #137: dedupe the EPG cache then enforce one row per
     *  (playlistId, channelId, startMillis) so feed refreshes REPLACE cached
     *  programmes in place instead of duplicating (the history merge relies
     *  on this). Duplicates are possible in pre-v20 caches (history rows the
     *  feed re-sent), so they are cleared before the unique index lands. */
    private val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM `epg_programme` WHERE `id` NOT IN (" +
                    "SELECT MIN(`id`) FROM `epg_programme` " +
                    "GROUP BY `playlistId`, `channelId`, `startMillis`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_epg_programme_playlistId_channelId_startMillis` " +
                    "ON `epg_programme` (`playlistId`, `channelId`, `startMillis`)",
            )
        }
    }

    /** GH #27: #KODIPROP DRM columns on the channel snapshot cache. */
    private val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `channel_snapshot` ADD COLUMN `drmLicenseType` TEXT")
            db.execSQL("ALTER TABLE `channel_snapshot` ADD COLUMN `drmLicenseKey` TEXT")
        }
    }

    /** EPG program badge metadata (guide/list/info-sheet badges): sub-title,
     *  season/episode, and the new/live/premiere/finale/repeat flags on the
     *  cached programme rows. Nullable columns take no default; the NOT NULL
     *  booleans use `DEFAULT 0`, which MUST match each field's
     *  @ColumnInfo(defaultValue = "0") on EpgProgrammeEntity or Room rejects the
     *  post-upgrade schema on open. Existing cached rows keep the defaults
     *  (no badges) until the next feed refresh REPLACEs them with real values;
     *  the cache is a pure derived store so no data loss. */
    private val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `epg_programme` ADD COLUMN `subTitle` TEXT")
            db.execSQL("ALTER TABLE `epg_programme` ADD COLUMN `season` INTEGER")
            db.execSQL("ALTER TABLE `epg_programme` ADD COLUMN `episode` INTEGER")
            db.execSQL("ALTER TABLE `epg_programme` ADD COLUMN `isNew` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `epg_programme` ADD COLUMN `isLiveBroadcast` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `epg_programme` ADD COLUMN `isPremiere` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `epg_programme` ADD COLUMN `isFinale` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `epg_programme` ADD COLUMN `isRepeat` INTEGER NOT NULL DEFAULT 0")
            // Drop the pre-badge cache so the first post-upgrade launch fetches
            // fresh EPG and the new badges appear immediately. Without this, the
            // cached rows carry the column defaults (no badges) and, since the
            // cache reads "fresh", the guide would show badge-less programmes
            // until the next scheduled refresh -- and on Android TV there is no
            // pull-to-refresh to force it. Same precedent as MIGRATION_16_17
            // clearing channel_snapshot; the EPG cache is pure derived data and
            // repopulates on that first fetch.
            db.execSQL("DELETE FROM `epg_programme`")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AerioDatabase =
        Room.databaseBuilder(context, AerioDatabase::class.java, "aerio.db")
            // Preserve user data across known schema bumps where a clean ALTER
            // exists. Destructive fallback is scoped to ONLY pre-v10 dev builds
            // so an unmapped future migration can never silently wipe a real
            // user's saved servers and credentials in the field.
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7, 8, 9)
            .build()

    /**
     * Audit task #53: every consumer gets the encrypting decorator, never the
     * raw Room DAO. apiKey/username/password are encrypted at rest (Keystore
     * AES-GCM) and decrypted transparently on read, so call sites are unchanged.
     */
    @Provides
    fun providePlaylistDao(
        db: AerioDatabase,
        cipher: CredentialCipher,
    ): PlaylistDao = EncryptingPlaylistDao(db.playlistDao(), cipher)

    @Provides
    fun provideWatchProgressDao(db: AerioDatabase): WatchProgressDao = db.watchProgressDao()

    @Provides
    fun provideLocalRecordingDao(db: AerioDatabase): LocalRecordingDao = db.localRecordingDao()

    @Provides
    fun provideFavoriteChannelDao(db: AerioDatabase): FavoriteChannelDao = db.favoriteChannelDao()

    @Provides
    fun provideReminderDao(db: AerioDatabase): ReminderDao = db.reminderDao()

    @Provides
    fun provideEpgProgrammeDao(db: AerioDatabase): EpgProgrammeDao = db.epgProgrammeDao()

    @Provides
    fun provideChannelSnapshotDao(db: AerioDatabase): ChannelSnapshotDao = db.channelSnapshotDao()
}
