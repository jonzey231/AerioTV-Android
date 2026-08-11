package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aeriotv.android.core.data.db.entity.EpgProgrammeEntity

@Dao
interface EpgProgrammeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EpgProgrammeEntity>)

    @Query("SELECT * FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: String): List<EpgProgrammeEntity>

    /**
     * Time-windowed variant of [forPlaylist] (iOS GuideStore parity --
     * EPGGuideView.swift `loadFromCache` predicate). Returns only programmes
     * whose airing window overlaps [fromMillis]..[toMillis], so cold launch
     * paint is ~5-10% the size of the full cache instead of all 58K+ rows.
     *
     * Overlap predicate: `endMillis > fromMillis AND startMillis < toMillis`.
     * This is symmetric in start/end (programmes that started before the
     * window AND end inside it are kept; ones that start inside the window
     * AND end after it are kept; ones fully inside are kept).
     *
     * The `endMillis > :fromMillis` clause hits the existing index on
     * `endMillis` (declared on the entity), so the scan stays cheap even on
     * a 200K-row table.
     */
    @Query(
        "SELECT * FROM epg_programme WHERE playlistId = :playlistId " +
            "AND endMillis > :fromMillis AND startMillis < :toMillis"
    )
    suspend fun forPlaylistInWindow(
        playlistId: String,
        fromMillis: Long,
        toMillis: Long,
    ): List<EpgProgrammeEntity>

    /**
     * EPG-scope search for the global Search surface (parity task #41 / iOS
     * SearchView EPG scope). Matches title OR description, case-insensitive
     * (Room LIKE is case-insensitive for ASCII), time-windowed to now-forward
     * (endMillis > :nowMillis) so already-ended programmes don't clutter
     * results. Ordered by start time so the soonest airing surfaces first.
     * Caller (PlaylistRepository.searchEpg) injects '%'||q||'%' wildcards.
     */
    @Query(
        "SELECT * FROM epg_programme WHERE playlistId = :playlistId " +
            "AND endMillis > :nowMillis " +
            "AND (title LIKE :like OR description LIKE :like) " +
            "ORDER BY startMillis ASC LIMIT :limit"
    )
    suspend fun searchInWindow(
        playlistId: String,
        like: String,
        nowMillis: Long,
        limit: Int = 60,
    ): List<EpgProgrammeEntity>

    /** Most recent fetch time for this source, or null when nothing is cached. */
    @Query("SELECT MAX(fetchedAt) FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun newestFetchedAt(playlistId: String): Long?

    @Query("DELETE FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /** Prune one source's programmes that ended before its retention cutoff
     *  (catch-up task #135: retention is per playlist now, so the old blanket
     *  cross-source ended-1h-ago delete is gone). */
    @Query("DELETE FROM epg_programme WHERE playlistId = :playlistId AND endMillis < :before")
    suspend fun deleteEndedBeforeForPlaylist(playlistId: String, before: Long)

    /** Delete the airing-and-future region the fresh feed owns outright, for
     *  the CHANNELS it actually carries; [mergeForPlaylist]'s helper. */
    @Query(
        "DELETE FROM epg_programme WHERE playlistId = :playlistId " +
            "AND endMillis > :fromMillis AND channelId IN (:channelIds)"
    )
    suspend fun deleteCoveredWindowForChannels(
        playlistId: String,
        fromMillis: Long,
        channelIds: List<String>,
    )

    /**
     * Merge a fresh feed into one source's cached guide in a single
     * transaction so a reader never sees a half-written batch. The feed owns
     * the PRESENT AND FUTURE for THE CHANNELS IT CARRIES (that region is
     * deleted and re-inserted), while ALREADY-AIRED rows are left in place so
     * history accumulates for catch-up (task #135/#137). Any past rows the
     * feed still carries replace their cached copies via the unique
     * (playlistId, channelId, startMillis) index + REPLACE conflict strategy
     * instead of duplicating. An earlier revision deleted everything after the
     * feed's EARLIEST start; feeds trim their own history between refreshes,
     * so recently-ended programmes inside that window but absent from the new
     * feed were silently erased.
     *
     * The channel scoping is load-bearing (Logan 2026-08-10). This used to
     * delete the whole present+future for the PLAYLIST, which is correct only
     * when `rows` is the complete feed - and PlaylistRepository's upstream
     * layering deliberately calls saveEpgToCache ONCE PER SOURCE so a kill
     * mid-phase loses at most one feed. With a playlist-wide delete each of
     * those saves wiped the previous one, so a 3-source layering left only the
     * last source's rows: a 6518-programme grid collapsed to ~545, the guide
     * lost everything past "now", and because the cache was non-empty the
     * freshness check then skipped the network on every relaunch, so it never
     * healed. Scoping the delete to the incoming feed's channels lets the
     * sources compose instead of clobber.
     */
    @Transaction
    suspend fun mergeForPlaylist(
        playlistId: String,
        rows: List<EpgProgrammeEntity>,
        nowMillis: Long,
    ) {
        if (rows.isEmpty()) return
        // Chunked: SQLite caps host parameters (999 by default) and a feed can
        // carry thousands of channels, so an unchunked IN (...) would throw.
        rows.asSequence()
            .map { it.channelId }
            .distinct()
            .chunked(900)
            .forEach { deleteCoveredWindowForChannels(playlistId, nowMillis, it) }
        insertAll(rows)
    }
}
