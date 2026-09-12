package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity

/**
 * One row per one-day EPG grid chunk this app has already fetched for a
 * source, so a relaunch only loads what is MISSING or STALE instead of
 * refetching the whole Guide Days range (Logan 2026-09-11: the Streamer log
 * showed "grid window: 30 chunk(s)" on every single launch, 30 HTTP round
 * trips and 30 merges for data that was already on disk).
 *
 * Chunks live on a FIXED UTC day grid (`floorDiv(ms, 86_400_000) * 86_400_000`),
 * never on an anchor derived from "now": a drifting anchor would mint a brand
 * new [chunkStartMs] on every launch and no row would ever be reusable, which
 * is the whole point of the table.
 *
 * Pure derived data (like `epg_programme` itself), so it is always safe to drop
 * and rebuild. It is dropped together with the programmes whenever the
 * playlist's channel identity changes or the user hits Refresh: a coverage row
 * that outlived its programmes would claim a chunk is cached when the grid rows
 * it vouches for are gone (the cache-identity rule).
 *
 * [programCount] is bookkeeping for the All Available stop rule: a chunk that
 * came back EMPTY is still covered, so the "two consecutive empty chunks"
 * walk-outward terminator must not count it as a fresh empty and stop early.
 */
@Entity(tableName = "epg_chunk_coverage", primaryKeys = ["playlistId", "chunkStartMs"])
data class EpgChunkCoverage(
    /** [PlaylistEntity.id] this chunk was fetched for. */
    val playlistId: String,
    /** Inclusive chunk start, aligned to the UTC day grid. */
    val chunkStartMs: Long,
    /** Exclusive chunk end (normally [chunkStartMs] + 24 h). */
    val chunkEndMs: Long,
    /** Wall clock of the fetch; staleness is age against [EPG_CHUNK_TTL_MS]. */
    val fetchedAtMs: Long,
    /** Programmes the server returned for this chunk (0 is a valid answer). */
    val programCount: Int,
)

/**
 * A covered chunk is re-fetched once it is older than this. 12 h: the guide a
 * day or a week out does change (schedule corrections, late sports windows),
 * but not hourly, and the live -1h..+24h window is fetched unconditionally on
 * every load anyway, so "today" is never served from a stale chunk.
 */
const val EPG_CHUNK_TTL_MS: Long = 12L * 60L * 60L * 1000L
