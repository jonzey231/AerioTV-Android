package com.aeriotv.android.core.timeshift

/**
 * Pure disk rules for Live Rewind (2026-11-08 storage fix, parity with the
 * Apple app). Measured on Apple: one user's container reached 43 GB from
 * orphaned rewind session folders, because the buffer was trimmed by
 * duration only, the "budget" was usage + free space minus a floor (so it
 * never trimmed on a large disk), and orphans were removed only at launch
 * and only when over an hour old. Android had the same budget formula.
 *
 * The rules here:
 * 1. A hard cap on all rewind bytes across sessions: the smaller of
 *    depth x [MAX_SESSIONS] channels x [BITRATE_ALLOWANCE_BYTES_PER_SEC]
 *    and a fixed ceiling (16 GB phones and tablets, 8 GB TV), and also no
 *    more than usage + free space above the floor.
 * 2. Trim order: oldest segments of the oldest non-active session first,
 *    then the active session, oldest first. A session a live writer still
 *    owns always keeps its newest (open) segment.
 * 3. Any session directory not owned by this process is an orphan,
 *    regardless of age.
 *
 * No Android or file APIs, so the unit tests exercise it directly.
 */
object RewindStoragePolicy {
    const val GB = 1024L * 1024 * 1024
    const val PHONE_CEILING_BYTES = 16L * GB
    const val TV_CEILING_BYTES = 8L * GB
    /** Keep Recent Channels Live holds at most this many sessions plus the active one. */
    const val MAX_SESSIONS = 5
    /** 12 Mbps: covers HD and most UHD channels at the configured depth. */
    const val BITRATE_ALLOWANCE_BYTES_PER_SEC = 12_000_000L / 8
    const val FREE_SPACE_FLOOR_BYTES = 2L * GB

    fun capBytes(
        depthMs: Long,
        isTv: Boolean,
        usageBytes: Long,
        usableBytes: Long,
        floorBytes: Long = FREE_SPACE_FLOOR_BYTES,
    ): Long {
        val depthSec = (depthMs / 1000L).coerceAtLeast(0L)
        val byDepth = depthSec * MAX_SESSIONS * BITRATE_ALLOWANCE_BYTES_PER_SEC
        val ceiling = if (isTv) TV_CEILING_BYTES else PHONE_CEILING_BYTES
        val byFree = usageBytes + (usableBytes - floorBytes).coerceAtLeast(0L)
        return minOf(byDepth, ceiling, byFree)
    }

    data class Segment(val name: String, val startMs: Long, val bytes: Long)

    data class Session(
        val name: String,
        val startedAtMs: Long,
        val active: Boolean,
        /** A live writer in this process still appends to it. */
        val owned: Boolean,
        val segments: List<Segment>,
    )

    data class Trim(val session: String, val segment: String, val bytes: Long)

    /** Segments to delete so the total fits in [capBytes], in deletion order. */
    fun selectTrim(sessions: List<Session>, capBytes: Long): List<Trim> {
        var total = sessions.sumOf { s -> s.segments.sumOf { it.bytes } }
        if (total <= capBytes) return emptyList()
        val out = ArrayList<Trim>()
        val ordered = sessions.filter { !it.active }.sortedBy { it.startedAtMs } +
            sessions.filter { it.active }
        for (s in ordered) {
            val segs = s.segments.sortedBy { it.startMs }
            val deletable = if (s.owned) segs.dropLast(1) else segs
            for (seg in deletable) {
                if (total <= capBytes) return out
                out += Trim(s.name, seg.name, seg.bytes)
                total -= seg.bytes
            }
        }
        return out
    }

    /** Directory names under the rewind root that no live writer owns. */
    fun selectOrphans(dirNames: Collection<String>, ownedNames: Set<String>): List<String> =
        dirNames.filter { it !in ownedNames }.sorted()

    /** Temp files in the cache dir left by an earlier process: older than
     *  this process's start, with one of our temp prefixes. */
    fun isOrphanTemp(name: String, lastModifiedMs: Long, processStartMs: Long): Boolean =
        TEMP_PREFIXES.any { name.startsWith(it) } && lastModifiedMs < processStartMs

    val TEMP_PREFIXES = listOf("aerio_dl")
}
