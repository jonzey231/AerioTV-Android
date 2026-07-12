package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Disk-cached channel snapshot. Mirrors the same pattern Phase 121 introduced
 * for the EPG (see [EpgProgrammeEntity]): the last successfully-fetched channel
 * list for each playlist is persisted with a [fetchedAt] timestamp so a cold
 * launch can repaint the Live TV rail + guide INSTANTLY from cache while the
 * network refresh runs in the background. Without this, every cold launch
 * stares at an empty rail for ~10-20s waiting on `repository.refresh(saved)` to
 * round-trip the Dispatcharr / M3U fetch + parse (Archie pointed out: with
 * caching working, loading times shouldn't be an issue for relaunches <24h).
 *
 * Rows are scoped by [playlistId] (a [PlaylistEntity.id] UUID string). The
 * cache is a pure derived-data store and can be safely dropped / rebuilt at
 * any time. [position] preserves the channel order produced by [PlaylistRepository.fetchChannelsFor]
 * (Dispatcharr: channelNumber asc then name; M3U: file order) so the rail
 * paints in the same order the live fetch would. We deliberately do NOT cache
 * `M3UChannel.rawAttributes` because nothing reads it after parsing.
 */
@Entity(
    tableName = "channel_snapshot",
    indices = [
        Index(value = ["playlistId"]),
    ],
)
data class ChannelSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    /** [com.aeriotv.android.core.data.M3UChannel.id] - stable for Dispatcharr
     *  ("disp:<uuid>") so favorites/watch-progress survive cache restore. */
    val channelId: String,
    /** Insertion order within the playlist; what the rail / list sorts by. */
    val position: Int,
    val name: String,
    val url: String,
    val groupTitle: String,
    val tvgID: String,
    val tvgName: String,
    val tvgLogo: String,
    val channelNumber: String?,
    val dispatcharrChannelId: Int?,
    /** Catch-up archive window in days (0 = none). Persisted so catch-up
     *  affordances survive a cache-restored cold launch. */
    val catchupDays: Int = 0,
    /** Timeshift stream id (Dispatcharr Channel.id / XC stream_id), or null. */
    val catchupStreamId: String? = null,
    /** GH #27: #KODIPROP DRM signalling, persisted so encrypted-DASH
     *  channels stay playable from a cache-restored cold launch. */
    val drmLicenseType: String? = null,
    val drmLicenseKey: String? = null,
    /** Wall-clock millis when this snapshot was fetched (freshness check). */
    val fetchedAt: Long,
)
