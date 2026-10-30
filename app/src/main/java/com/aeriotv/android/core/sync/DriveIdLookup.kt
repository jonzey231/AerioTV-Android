package com.aeriotv.android.core.sync

import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.preferences.WatchlistStore
import com.aeriotv.android.core.sync.dsync.VodIdResolver
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Resolves Drive snapshot local ids to the canonical Dispatcharr ids
 * (DriveCanon) for the active Dispatcharr playlist, default profile. Reuses
 * the Dispatcharr Sync lookups: the channel snapshot's Dispatcharr channel
 * ids and [VodIdResolver]. Callers run inside ProfileScope.defaultOnly().
 */
@Singleton
class DriveIdLookup @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelSnapshotDao: ChannelSnapshotDao,
    private val vodIds: VodIdResolver,
    private val repository: Provider<com.aeriotv.android.core.data.repository.PlaylistRepository>,
) {
    /** The active playlist when it is Dispatcharr Direct Connect, else null (no canonical ids). */
    suspend fun activeDispatcharr(): PlaylistEntity? =
        playlistDao.allOnce().firstOrNull { it.isActive && it.isDispatcharrDirectConnect() }

    fun host(row: PlaylistEntity): String? = WatchlistStore.scopeFor(row)

    suspend fun moviesReady(row: PlaylistEntity): Boolean = vodIds.moviesReady(row)

    /** Default-profile channel snapshot: local channel id to Dispatcharr channel id. */
    suspend fun channels(row: PlaylistEntity): Map<String, Int> =
        channelSnapshotDao.forPlaylistKey(row.id).mapNotNull { r -> r.dispatcharrChannelId?.let { r.channelId to it } }.toMap()

    suspend fun movies(row: PlaylistEntity, uuids: Collection<String>): Map<String, Int> =
        uuids.mapNotNull { u -> vodIds.movieId(row, u)?.let { u to it } }.toMap()

    /**
     * Episode uuid to id. [network] false reads the saved map only (pushes);
     * true may load a series' episode list once (the probe and the migration),
     * the same request Dispatcharr Sync already makes.
     */
    suspend fun episodes(row: PlaylistEntity, needs: Map<String, Int?>, network: Boolean): Map<String, Int> {
        if (needs.isEmpty()) return emptyMap()
        if (!network) return needs.keys.mapNotNull { u -> vodIds.episodeIdCached(u)?.let { u to it } }.toMap()
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: return needs.keys.mapNotNull { u -> vodIds.episodeIdCached(u)?.let { u to it } }.toMap()
        val base = repository.get().effectiveBaseUrl(row)
        return needs.mapNotNull { (u, sid) -> vodIds.episodeId(base, key, u, sid)?.let { u to it } }.toMap()
    }
}
