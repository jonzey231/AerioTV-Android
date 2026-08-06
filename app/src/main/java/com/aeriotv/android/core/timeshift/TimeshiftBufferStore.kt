package com.aeriotv.android.core.timeshift

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live Rewind (task #143): on-device timeshift buffer.
 *
 * While the user watches a live channel fullscreen, the player's own
 * network bytes are teed (see [TeeDataSource]) into a session directory
 * of small MPEG-TS segment files. Pause/rewind switches playback onto
 * the growing buffer (see [TimeshiftDataSource]); "Go Live" returns to
 * the direct stream.
 *
 * Storage model (user spec, 2026-07-10):
 * - PERSISTENT app storage, not cache: sessions live under
 *   `<externalFilesDir>/LiveRewind/` and OUTLIVE the viewing session;
 *   a retention reaper deletes them after the configured age
 *   (1h/6h/.../custom). USB + network targets arrive in P3.
 * - A max storage budget caps the total across sessions; oldest
 *   segments are evicted first.
 * - The rewind DEPTH (15/30/60/120 min) rings the ACTIVE session:
 *   segments older than the depth are deleted as new ones roll.
 *
 * Segments are plain slices of the original TS byte stream, cut on
 * 188-byte packet boundaries every [SEGMENT_MS]. Readers stitch them
 * back together, so cut points need no PAT/PMT/keyframe alignment:
 * the concatenation is bit-identical to the stream off the wire.
 */
@Singleton
class TimeshiftBufferStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "TimeshiftBuffer"
        const val SEGMENT_MS = 6_000L
        const val TS_PACKET = 188
        private const val META_FILE = "meta.json"

        /** Free-space seatbelt: the buffer may grow until the volume
         *  would drop below this much free space. */
        const val FREE_SPACE_FLOOR_BYTES = 2L * 1024 * 1024 * 1024

        /** Directory name pattern: sess_<startEpochMs>. */
        fun sessionDirName(startedAtMs: Long) = "sess_$startedAtMs"
    }

    val rootDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "LiveRewind")

    /**
     * Start a new buffer session for [channelId]. Prunes expired and
     * over-budget data first so a long-running box converges instead
     * of only growing.
     */
    fun startSession(
        channelId: String,
        channelName: String,
        depthMs: Long,
        retentionMs: Long,
        budgetBytes: Long,
    ): TimeshiftWriter {
        pruneExpired(retentionMs)
        enforceBudget(budgetBytes)
        val startedAt = System.currentTimeMillis()
        val dir = File(rootDir, sessionDirName(startedAt))
        dir.mkdirs()
        val meta = JSONObject()
            .put("channelId", channelId)
            .put("channelName", channelName)
            .put("startedAtMs", startedAt)
        File(dir, META_FILE).writeText(meta.toString())
        Log.i(TAG, "session start dir=${dir.name} channel=$channelName depthMs=$depthMs")
        return TimeshiftWriter(dir, startedAt, depthMs, budgetBytes)
    }

    /** Delete whole sessions whose newest data is older than [retentionMs]. */
    fun pruneExpired(retentionMs: Long) {
        val cutoff = System.currentTimeMillis() - retentionMs
        rootDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val newest = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
            if (newest < cutoff) {
                Log.i(TAG, "retention prune ${dir.name}")
                dir.deleteRecursively()
            }
        }
    }

    /** Evict oldest segments across sessions until total <= [budgetBytes]. */
    fun enforceBudget(budgetBytes: Long) {
        if (budgetBytes <= 0) return
        val segs = rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { d -> d.listFiles()?.filter { it.name.endsWith(".ts") }.orEmpty() }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = segs.sumOf { it.length() }
        for (f in segs) {
            if (total <= budgetBytes) break
            total -= f.length()
            f.delete()
        }
        // Drop session dirs that lost all their segments.
        rootDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.listFiles()?.none { it.name.endsWith(".ts") } != false) {
                dir.deleteRecursively()
            }
        }
    }

    fun totalBytes(): Long =
        rootDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * The Storage Limit SETTING was removed (user directive 2026-07-11:
     * retention is the only user-facing knob, with storage estimates
     * shown under it). This computes the internal seatbelt budget that
     * replaced it: current buffer usage plus whatever free space the
     * volume has above [FREE_SPACE_FLOOR_BYTES], so a long retention on
     * a small disk evicts oldest video instead of filling the device.
     * Recomputed at every session start and reaper pass; drift within a
     * single session is bounded and harmless (the mid-roll enforcement
     * uses the value captured at session start).
     */
    fun freeSpaceBudgetBytes(): Long {
        val usable = runCatching {
            rootDir.apply { mkdirs() }.usableSpace
        }.getOrDefault(0L)
        return totalBytes() + (usable - FREE_SPACE_FLOOR_BYTES).coerceAtLeast(0L)
    }
}

/**
 * Index of one session's segments. Kept in memory by the writer and
 * re-derivable from the filesystem by readers: segment file names embed
 * their start wall-clock ms (`seg_<startWallMs>.ts`), so a reader can
 * map a wall time to (segment, byte offset) with only a directory
 * listing plus proportional interpolation inside the segment.
 */
data class TimeshiftSegment(
    val file: File,
    val startWallMs: Long,
    /** End wall time; for the segment being written this is "now". */
    val endWallMs: Long,
    val bytes: Long,
)

/** GH #51: packet count of the head fingerprint the splice trimmer hunts for. */
private const val OVERLAP_RUN = 16

/** GH #51: stop hunting for the overlap after discarding this many bytes.
 *  The overlap is the server's client-join replay
 *  (new_client_behind_seconds, ~5s by default), which arrives as an
 *  instant burst: 8MB covers 5s at ~13Mbps. */
private const val OVERLAP_SEARCH_CAP = 8 * 1024 * 1024

/** GH #51: stop hunting after this much wall time. The replay burst
 *  lands in the first fraction of a second; a server with join replay
 *  disabled simply has no fingerprint to find, and this bound caps the
 *  resulting gap. */
private const val OVERLAP_SEARCH_MS = 3_000L

/**
 * Appends the live TS byte stream into rolling segment files.
 *
 * Threading: [append] is called from ExoPlayer's loading thread via
 * [TeeDataSource]. Writes are handed to a single-thread executor with
 * a bounded queue so a slow disk can NEVER stall live playback; on
 * overflow the incoming chunk is dropped (a small hole in the rewind
 * buffer beats a stalled live picture).
 */
class TimeshiftWriter(
    val sessionDir: File,
    val sessionStartMs: Long,
    private val depthMs: Long,
    private val budgetBytes: Long = Long.MAX_VALUE,
) {
    /** Running byte total of on-disk segments this session; maintained
     *  by [rollSegment]'s eviction pass so the budget can be enforced
     *  mid-session (a 120-min depth on a UHD feed outgrows a 10 GB
     *  budget long before the next session-start sweep runs). */
    private var sessionBytes = 0L
    private val lock = Any()
    private var current: RandomAccessFile? = null
    private var currentFile: File? = null
    private var currentStartWallMs = 0L
    private var currentBytes = 0L
    /** Carry buffer so segment cuts always land on 188-byte packet boundaries. */
    private var carry = ByteArray(0)
    /** Set whenever a NEW connection starts feeding the writer (session
     *  start, tee reopen, independent filler splice). Dispatcharr's
     *  /proxy/ts joins mid-packet, so the first bytes of every
     *  connection are NOT packet-aligned; consuming them as-is
     *  misaligns the entire buffer and the demuxer never finds stable
     *  sync (the "constant freezing" field report). While set, incoming
     *  bytes are scanned for a verified 0x47 sync pattern and everything
     *  before it is dropped. */
    @Volatile private var needResync = true
    @Volatile var closed = false
        private set

    // GH #51 overlap trimmer state (writer-thread confined, guarded by
    // [lock] like the rest of the write path). STREAMING design: packets
    // after a splice are DISCARDED until the head fingerprint run
    // completes - nothing is held back, so the buffer head never
    // freezes, and an unresolved search costs at most the bounded
    // discard (mostly duplicate content anyway) instead of dropped
    // video.
    /** Rolling hashes of the last [OVERLAP_RUN] whole packets written. */
    private val recentHashes = ArrayDeque<Int>()
    /** Fingerprint of the head at the last discontinuity; null = no trim. */
    private var spliceTarget: IntArray? = null
    /** True while incoming packets are being discarded up to the fingerprint. */
    private var discardActive = false
    /** Progress through [spliceTarget] of the incoming packet run. */
    private var matchLen = 0
    private var discardedBytes = 0L
    private var discardStartMs = 0L
    /** Wall time of the newest byte written; the "live edge" of the buffer. */
    @Volatile var headWallMs: Long = sessionStartMs
        private set
    /** Wall time of the oldest byte still on disk (ring tail). */
    @Volatile var tailWallMs: Long = sessionStartMs
        private set

    private val executor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue(64),
    ) { r -> Thread(r, "timeshift-writer").apply { priority = Thread.NORM_PRIORITY - 1 } }
        .apply { setRejectedExecutionHandler { _, _ -> /* drop chunk, never block playback */ } }

    /** Mark that the NEXT appended bytes come from a fresh connection:
     *  drop the packet-fragment carry and re-scan for TS sync. GH #51:
     *  also arm the overlap trimmer. Dispatcharr serves a joining client
     *  several seconds BEHIND the live edge (new_client_behind_seconds +
     *  the initial chunk burst), so the independent filler / a watchdog
     *  re-prime replays content the buffer already holds; appending it
     *  as-is put a backwards PTS jump in the stream and the demuxer
     *  chewed through it as visual artifacts + A/V desync whenever
     *  playback crossed the splice. The trimmer captures the last
     *  [OVERLAP_RUN] packet hashes as a fingerprint of our head and
     *  drops the new connection's bytes until that run reappears -- the
     *  splice then continues bit-exactly where the old connection left
     *  off. No match within [OVERLAP_SEARCH_CAP] bytes (a genuine gap,
     *  or a different-content connection) falls back to the old splice
     *  behavior. */
    fun markDiscontinuity() {
        executor.execute {
            synchronized(lock) {
                carry = ByteArray(0)
                needResync = true
                // Fingerprint needs a full run with some variety: a run of
                // near-identical packets (nulls, repeated PAT/PMT) would
                // false-match almost anywhere and trim to the wrong spot.
                spliceTarget = if (recentHashes.size >= OVERLAP_RUN &&
                    recentHashes.toSet().size >= 4
                ) {
                    recentHashes.toIntArray()
                } else null
                discardActive = spliceTarget != null
                matchLen = 0
                discardedBytes = 0L
                discardStartMs = 0L
            }
        }
    }

    fun append(data: ByteArray, offset: Int, length: Int) {
        if (closed || length <= 0) return
        val copy = data.copyOfRange(offset, offset + length)
        executor.execute { writeChunk(copy) }
    }

    private fun writeChunk(chunk: ByteArray) {
        synchronized(lock) {
            if (closed) return
            try {
                val now = System.currentTimeMillis()
                // Merge the carry-over remainder with this chunk, then
                // write only whole 188-byte packets; keep the tail.
                var merged = if (carry.isEmpty()) chunk else carry + chunk
                if (needResync) {
                    val sync = findSync(merged)
                    if (sync < 0) {
                        // No verified sync in this chunk; keep a tail so a
                        // pattern spanning the boundary is still found.
                        carry = merged.takeLast(TimeshiftBufferStore.TS_PACKET * 2 + 1).toByteArray()
                        return
                    }
                    merged = merged.copyOfRange(sync, merged.size)
                    needResync = false
                }
                val whole = (merged.size / TimeshiftBufferStore.TS_PACKET) * TimeshiftBufferStore.TS_PACKET
                carry = if (whole < merged.size) merged.copyOfRange(whole, merged.size) else ByteArray(0)
                if (whole == 0) return

                if (discardActive) {
                    trimDiscard(merged, whole, now)
                } else {
                    commitPackets(merged, 0, whole, now)
                }
            } catch (t: Throwable) {
                Log.w("TimeshiftBuffer", "write failed, closing buffer: $t")
                closeLocked()
            }
        }
    }

    /** Write whole packets to the current segment and keep the rolling
     *  head fingerprint fresh. The single sink for buffer bytes. */
    private fun commitPackets(buf: ByteArray, from: Int, len: Int, now: Long) {
        if (len <= 0) return
        if (current == null || now - currentStartWallMs >= TimeshiftBufferStore.SEGMENT_MS) {
            rollSegment(now)
        }
        current?.write(buf, from, len)
        currentBytes += len
        headWallMs = now
        var p = from
        val end = from + len
        while (p < end) {
            recentHashes.addLast(packetHash(buf, p))
            while (recentHashes.size > OVERLAP_RUN) recentHashes.removeFirst()
            p += TimeshiftBufferStore.TS_PACKET
        }
    }

    /**
     * GH #51: discard the new connection's packets until the pre-splice
     * head fingerprint run completes. Everything up to and including the
     * fingerprint is the server's replay of content the buffer already
     * holds (Dispatcharr sends a joining client the last
     * new_client_behind_seconds as an instant burst); dropping it makes
     * the splice bit-exact. Streaming, nothing held back: the search is
     * bounded by [OVERLAP_SEARCH_CAP] bytes and [OVERLAP_SEARCH_MS] wall
     * time - a server with no join replay costs at most that small
     * packet-aligned gap, which beats appending a duplicate region the
     * demuxer chews through as artifacts.
     */
    private fun trimDiscard(merged: ByteArray, whole: Int, now: Long) {
        val target = spliceTarget
        if (target == null) {
            discardActive = false
            commitPackets(merged, 0, whole, now)
            return
        }
        if (discardStartMs == 0L) discardStartMs = now
        var p = 0
        while (p < whole) {
            val h = packetHash(merged, p)
            matchLen = when {
                h == target[matchLen] -> matchLen + 1
                h == target[0] -> 1
                else -> 0
            }
            p += TimeshiftBufferStore.TS_PACKET
            if (matchLen == target.size) {
                // Fingerprint completed at THIS packet: bytes before and
                // including it are the replay; the rest of the chunk is
                // fresh continuation.
                discardActive = false
                spliceTarget = null
                val dropped = discardedBytes + p
                Log.i("TimeshiftBuffer", "splice overlap trimmed ($dropped bytes)")
                if (p < whole) commitPackets(merged, p, whole - p, now)
                return
            }
        }
        discardedBytes += whole
        if (discardedBytes > OVERLAP_SEARCH_CAP || now - discardStartMs > OVERLAP_SEARCH_MS) {
            discardActive = false
            spliceTarget = null
            Log.i(
                "TimeshiftBuffer",
                "splice fingerprint not found; spliced with a gap after " +
                    "discarding $discardedBytes bytes",
            )
        }
    }

    /** FNV-1a over one 188-byte packet. */
    private fun packetHash(buf: ByteArray, offset: Int): Int {
        var h = -2128831035
        var i = offset
        val end = offset + TimeshiftBufferStore.TS_PACKET
        while (i < end) {
            h = h xor (buf[i].toInt() and 0xFF)
            h *= 16777619
            i++
        }
        return h
    }

    /** First index with 0x47 at i, i+188, and i+376 (three-packet
     *  verification so a stray 0x47 inside a payload can't fool us). */
    private fun findSync(buf: ByteArray): Int {
        val p = TimeshiftBufferStore.TS_PACKET
        var i = 0
        val limit = buf.size - 2 * p - 1
        while (i <= limit) {
            if (buf[i] == 0x47.toByte() && buf[i + p] == 0x47.toByte() && buf[i + 2 * p] == 0x47.toByte()) {
                return i
            }
            i++
        }
        return -1
    }

    private fun rollSegment(now: Long) {
        current?.close()
        val f = File(sessionDir, "seg_$now.ts")
        current = RandomAccessFile(f, "rw")
        currentFile = f
        currentStartWallMs = now
        currentBytes = 0
        // Ring: drop segments older than the rewind depth, then keep
        // evicting oldest-first while the session exceeds the storage
        // budget (depth x bitrate can outgrow the budget mid-session).
        val cutoff = now - depthMs
        val segs = sessionDir.listFiles()
            ?.filter { it.name.startsWith("seg_") && it.name.endsWith(".ts") }
            ?.sortedBy { it.name.removePrefix("seg_").removeSuffix(".ts").toLongOrNull() ?: 0L }
            .orEmpty()
        var total = segs.sumOf { it.length() }
        var dropped = 0
        for (seg in segs) {
            val start = seg.name.removePrefix("seg_").removeSuffix(".ts").toLongOrNull() ?: continue
            // A segment covers [start, start+SEGMENT_MS); depth-evict only
            // when its END is past the cutoff so the window never shrinks
            // below the configured depth. Budget-evict regardless of age.
            val pastDepth = start + TimeshiftBufferStore.SEGMENT_MS < cutoff
            val overBudget = total > budgetBytes && dropped < segs.size - 1
            if (pastDepth || overBudget) {
                total -= seg.length()
                dropped++
                seg.delete()
            } else if (!pastDepth && total <= budgetBytes) {
                break
            }
        }
        sessionBytes = total
        tailWallMs = segments().firstOrNull()?.startWallMs ?: now
    }

    /** Snapshot of on-disk segments, oldest first. */
    fun segments(): List<TimeshiftSegment> {
        val files = sessionDir.listFiles()
            ?.filter { it.name.startsWith("seg_") && it.name.endsWith(".ts") }
            ?.sortedBy { it.name.removePrefix("seg_").removeSuffix(".ts").toLongOrNull() ?: 0L }
            ?: emptyList()
        return files.mapIndexed { i, f ->
            val start = f.name.removePrefix("seg_").removeSuffix(".ts").toLongOrNull() ?: 0L
            val end = files.getOrNull(i + 1)
                ?.name?.removePrefix("seg_")?.removeSuffix(".ts")?.toLongOrNull()
                ?: headWallMs
            TimeshiftSegment(f, start, end, f.length())
        }
    }

    fun close() {
        // Flush pending writes, then close the file on the writer thread
        // so we never truncate a chunk mid-write.
        executor.execute { synchronized(lock) { closeLocked() } }
        executor.shutdown()
    }

    private fun closeLocked() {
        closed = true
        current?.close()
        current = null
    }
}
