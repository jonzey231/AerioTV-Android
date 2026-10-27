package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aeriotv.android.core.data.db.entity.ChannelSnapshotEntity
import com.aeriotv.android.core.profiles.ProfileScope

@Dao
interface ChannelSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ChannelSnapshotEntity>)

    /** Cached channels for a playlist, in the same order they were saved. */
    @Query("SELECT * FROM channel_snapshot WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun forPlaylistKey(playlistId: String): List<ChannelSnapshotEntity>

    /** Most recent fetch time for this source, or null when nothing is cached. */
    @Query("SELECT MAX(fetchedAt) FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun newestFetchedAtKey(playlistId: String): Long?

    /**
     * Largest catch-up window any channel of this playlist exposes, in days
     * (null when nothing is cached, 0 when no channel has an archive). The
     * EPG catch-up reach prune and the grid history clamp both need only this
     * one number, so they ask for it instead of reading the whole snapshot.
     */
    @Query("SELECT MAX(catchupDays) FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun maxCatchupDaysKey(playlistId: String): Int?

    /**
     * GH #81: the playlist's distinct provider group names, for surfaces that
     * only need the group list (Settings > Default Group) and must not pull the
     * whole channel snapshot into memory to get it.
     */
    @Query(
        "SELECT DISTINCT groupTitle FROM channel_snapshot " +
            "WHERE playlistId = :playlistId AND groupTitle != '' ORDER BY groupTitle COLLATE NOCASE ASC",
    )
    suspend fun distinctGroupTitlesKey(playlistId: String): List<String>

    /** Mark as Radio Channel: flip one cached channel's radio flag in place. */
    @Query("UPDATE channel_snapshot SET isRadio = :isRadio WHERE playlistId = :playlistId AND channelId = :channelId")
    suspend fun setRadioKey(playlistId: String, channelId: String, isRadio: Boolean)

    @Query("DELETE FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylistKey(playlistId: String)

    /**
     * Replace the whole cached snapshot for one playlist in a single transaction
     * so a reader never sees a half-written batch (matches EpgProgrammeDao
     * replaceForPlaylist).
     */
    // Profiles (2026-10-16): the channel list is per Dispatcharr user (the
    // server filters it), so the snapshot is keyed by ProfileScope.cacheKey:
    // the bare playlistId for the default profile (every pre-profile row),
    // "<playlistId>@<profileId>" for another profile. The *Key queries take
    // the stored key; these wrappers keep the call sites unchanged.
    suspend fun forPlaylist(playlistId: String) = forPlaylistKey(ProfileScope.cacheKey(playlistId))
    suspend fun newestFetchedAt(playlistId: String) = newestFetchedAtKey(ProfileScope.cacheKey(playlistId))
    suspend fun maxCatchupDays(playlistId: String) = maxCatchupDaysKey(ProfileScope.cacheKey(playlistId))
    suspend fun distinctGroupTitles(playlistId: String) = distinctGroupTitlesKey(ProfileScope.cacheKey(playlistId))
    suspend fun setRadio(playlistId: String, channelId: String, isRadio: Boolean) =
        setRadioKey(ProfileScope.cacheKey(playlistId), channelId, isRadio)
    suspend fun deleteForPlaylist(playlistId: String) = deleteForPlaylistKey(ProfileScope.cacheKey(playlistId))

    /** Widest catch-up window across EVERY profile's snapshot of the
     *  playlist (the EPG rows it bounds are shared by all profiles). */
    @Query("SELECT MAX(catchupDays) FROM channel_snapshot WHERE playlistId = :playlistId OR playlistId LIKE :playlistId || '@%'")
    suspend fun maxCatchupDaysAllProfiles(playlistId: String): Int?

    /** Every profile's cached rows for the playlist (catch-up reach prune). */
    @Query("SELECT * FROM channel_snapshot WHERE playlistId = :playlistId OR playlistId LIKE :playlistId || '@%'")
    suspend fun forPlaylistAllProfiles(playlistId: String): List<ChannelSnapshotEntity>

    /** Playlist delete: the default snapshot and every profile's. */
    @Query("DELETE FROM channel_snapshot WHERE playlistId = :playlistId OR playlistId LIKE :playlistId || '@%'")
    suspend fun deleteAllProfilesForPlaylist(playlistId: String)

    @Transaction
    suspend fun replaceForPlaylist(playlistId: String, rows: List<ChannelSnapshotEntity>) {
        deleteForPlaylistKey(ProfileScope.cacheKey(playlistId))
        insertAll(rows)
    }
}
