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
        /** Session dirs the sweeps must not touch: live retained-channel
         *  buffers (Keep Recent Channels Live). Without this, the LRU
         *  budget pass eats the oldest retained channel's tail and the
         *  retention pruner can delete a quiet-but-live buffer outright. */
        protectedDirs: Set<File> = emptySet(),
    ): TimeshiftWriter {
        pruneExpired(retentionMs, protectedDirs)
        enforceBudget(budgetBytes, protectedDirs)
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
    fun pruneExpired(retentionMs: Long, protectedDirs: Set<File> = emptySet()) {
        val cutoff = System.currentTimeMillis() - retentionMs
        rootDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir in protectedDirs) return@forEach
            val newest = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
            if (newest < cutoff) {
                Log.i(TAG, "retention prune ${dir.name}")
                dir.deleteRecursively()
            }
        }
    }

    /** Evict oldest segments across sessions until total <= [budgetBytes].
     *  [protectedDirs] segments still COUNT toward the total (they are real
     *  bytes on the volume) but are never deleted here; each live writer's
     *  own depth ring and session budget bound their growth. */
    fun enforceBudget(budgetBytes: Long, protectedDirs: Set<File> = emptySet()) {
        if (budgetBytes <= 0) return
        val segs = rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { d -> d.listFiles()?.filter { it.name.endsWith(".ts") }.orEmpty() }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = segs.sumOf { it.length() }
        for (f in segs) {
            if (total <= budgetBytes) break
            if (f.parentFile in protectedDirs) continue
            total -= f.length()
            f.delete()
        }
        // Drop session dirs that lost all their segments.
        rootDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir !in protectedDirs &&
                dir.listFiles()?.none { it.name.endsWith(".ts") } != false
            ) {
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

/**
 * GH #65: a byte position in the buffer where the written content skips
 * FORWARD in time - a splice that resynced past missing content, a
 * forced-gap splice, or a dropped-chunk resync. The bytes on either
 * side are each well formed TS, but the PTS jumps across the boundary.
 * Media3 1.4.1's TsExtractor ignores the in-band discontinuity
 * indicator (verified against the 1.4.1 sources), so the reader uses
 * these markers instead: [TimeshiftDataSource] refuses to read across
 * one, the holder catches the typed error and re-enters the buffer AT
 * the gap, and the re-open resets the extractor and flushes the
 * renderers - the jump becomes a fresh timeline instead of the
 * mid-stream AudioSink UnexpectedDiscontinuityException storm in
 * Destro706's logs.
 */
data class TimeshiftGap(
    /** Segment file the post-gap content starts in. */
    val segName: String,
    /** Byte offset within that segment of the first post-gap byte. */
    val byteOffset: Long,
    /** Wall time the post-gap content was committed; what the holder
     *  re-enters at (and reports as the new playhead). */
    val resumeWallMs: Long,
)

/** GH #51: packet count of the head fingerprint the splice trimmer hunts for. */
private const val OVERLAP_RUN = 16

/** GH #55: byte cap on the overlap hunt, used ONLY when the stream
 *  carries no usable PCR (see [TimeshiftWriter.trimDiscard]).
 *
 *  This used to be the primary bound and it was the bug. The hunt
 *  discards as it scans, so hitting the cap threw away 8 MiB of video
 *  that had already arrived. Destro706's three logs (GH #55, one per
 *  device) show the fingerprint was never once found and the cap was
 *  hit every time, always within 436-1068 ms, i.e. the bytes arrive at
 *  60-150 Mbps. That is a server backlog burst, not live video: the
 *  join replay starts BEHIND our head, so our head fingerprint is in
 *  the burst's future and can never appear, and we then binned several
 *  seconds of perfectly good video and spliced a hard hole. The hole
 *  breaks PTS continuity, which is the AudioSink discontinuity storm
 *  in the same logs. */
private const val OVERLAP_SEARCH_CAP = 8 * 1024 * 1024

/** GH #51: stop hunting after this much wall time when there is no PCR
 *  to steer by. */
private const val OVERLAP_SEARCH_MS = 3_000L

/** GH #55: seatbelt for the PCR-steered hunt. The stop condition there
 *  is "the new connection's clock passed our head", which a real replay
 *  always reaches, so this only fires if a server streams an enormous
 *  backlog or restamps its clock. Generous, because every byte the hunt
 *  discards under PCR steering genuinely IS content we already hold. */
private const val OVERLAP_PCR_SEARCH_MS = 15_000L

/** PCR is a 33-bit 90 kHz counter; it wraps roughly every 26.5 hours. */
private const val PCR_WRAP = 1L shl 33

/** GH #65: a clock-resynced splice whose forward skip is at least this
 *  large gets a [TimeshiftGap] marker. Below it the audio sink's own
 *  200 ms tolerance-plus-resync absorbs the jump more smoothly than a
 *  source re-open would. */
private const val GAP_MARK_MIN_MS = 1_000L

/** GH #65: PCRs seen on a PID other than the splice anchor (with none
 *  yet on the anchor) before concluding the incoming connection is a
 *  DIFFERENT mux and the anchor's clock will never appear. */
private const val FOREIGN_PCR_LIMIT = 3

/** GH #65: how long the live tee is allowed to wait for a writer
 *  admission permit before dropping. Bounded so live playback can never
 *  be stalled meaningfully (ExoPlayer holds seconds of buffer-ahead;
 *  network jitter routinely costs more than this), but long enough to
 *  ride out a rollSegment eviction pass on slow eMMC, which is what was
 *  actually filling the queue in Destro706's v0.4.12 logs. */
private const val LIVE_ADMIT_WAIT_MS = 500L

/** Writer queue depth, in chunks. Also the permit count of the
 *  admission semaphore that fronts it. */
private const val WRITE_SLOTS = 256

/**
 * Appends the live TS byte stream into rolling segment files.
 *
 * Threading: [append] is called from ExoPlayer's loading thread via
 * [TeeDataSource]. Writes are handed to a single-thread executor with
 * a bounded queue so a slow disk can NEVER stall live playback; the
 * tee waits at most [LIVE_ADMIT_WAIT_MS] for a slot, the filler waits
 * for as long as the writer lives, and a chunk refused even then is
 * dropped WITH a gap marker so the hole can be hopped instead of
 * crashing the audio sink (GH #65).
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

    // GH #55 PCR steering. The byte fingerprint only works when the new
    // connection replays bit-identical bytes that our head lies inside;
    // the stream's own clock says definitively whether the incoming
    // bytes are behind our head (replay, discard) or past it (fresh,
    // keep). PCR is what makes the hunt terminate for the right reason.
    /** Last PCR committed to the buffer, 90 kHz base; -1 = none seen. */
    private var headPcr = -1L
    /** PID that carried [headPcr], so a second program on the same mux
     *  cannot be mistaken for the clock we are tracking. */
    private var headPcrPid = -1
    /** [headPcr] snapshotted at the last discontinuity. */
    private var spliceAnchorPcr = -1L
    private var spliceAnchorPid = -1
    /** Set once the discard scan sees any PCR on the anchor PID; while
     *  false the byte cap is still the only available bound. */
    private var sawAnchorPcr = false
    /** GH #65: PCRs seen on some OTHER PID while [sawAnchorPcr] is still
     *  false. A few of these with zero anchor sightings means the mux
     *  changed under us and the anchor clock will never appear. */
    private var foreignPcrCount = 0

    // GH #65 gap markers. Written by the writer thread (and, for the
    // pending flag, by producer threads on a drop), read by the
    // timeshift reader thread.
    /** Positions where buffered content skips forward in time. */
    private val gapList = ArrayDeque<TimeshiftGap>()
    /** Set when the NEXT committed bytes follow a time skip: commit
     *  records a [TimeshiftGap] there and arms the in-band flag. */
    @Volatile private var pendingGap = false
    /** True while waiting for the first PCR-carrying packet after a gap
     *  to stamp its discontinuity_indicator bit (in-band correctness;
     *  the marker above is what the player actually acts on). */
    private var pendingDiscFlag = false

    /** Snapshot of gap markers, oldest first. */
    fun gaps(): List<TimeshiftGap> = synchronized(gapList) { gapList.toList() }

    /** Chunks the writer had to refuse because the queue was full.
     *  Reported once per session close: a nonzero count means the buffer
     *  has holes and is worth knowing about in a shared log. */
    private val droppedChunks = java.util.concurrent.atomic.AtomicLong(0)
    /** Wall time of the newest byte written; the "live edge" of the buffer. */
    @Volatile var headWallMs: Long = sessionStartMs
        private set
    /** Wall time of the oldest byte still on disk (ring tail). */
    @Volatile var tailWallMs: Long = sessionStartMs
        private set

    /** Admission control for [executor]. The queue is sized above the
     *  permit count so a permitted task is never rejected; the permit is
     *  what decides whether a producer is dropped or made to wait. */
    private val slots = java.util.concurrent.Semaphore(WRITE_SLOTS)

    private val executor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue(WRITE_SLOTS + 16),
    ) { r -> Thread(r, "timeshift-writer").apply { priority = Thread.NORM_PRIORITY - 1 } }
        .apply { setRejectedExecutionHandler { _, _ -> onChunkDropped() } }

    /** GH #55: a refused chunk is a hole of arbitrary length, and the
     *  bytes after it no longer line up with the packet remainder held
     *  in [carry] - without a resync EVERY later packet in the session
     *  is misaligned by (holeBytes + carry) mod 188 and the demuxer
     *  never recovers. Silently dropping was never safe. */
    private fun onChunkDropped() {
        needResync = true
        // GH #65: a hole is also a forward time skip for any reader that
        // later crosses it; mark it so the player hops it cleanly
        // instead of feeding the audio sink a PTS discontinuity.
        pendingGap = true
        val n = droppedChunks.incrementAndGet()
        if (n == 1L || n % 64L == 0L) {
            Log.w("TimeshiftBuffer", "writer queue full, dropped $n chunk(s); buffer will resync")
        }
    }

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
        // GH #65 finding 1 (part): this control message used to take a
        // queue permit like any data chunk, so the same pressure that
        // drops chunks could silently drop the SPLICE ARMING itself and
        // the join replay then landed raw (backwards PTS, no trim,
        // nothing in the log). The executor queue is sized WRITE_SLOTS
        // + 16 above the permit count precisely so permit-free control
        // tasks always fit; submit directly.
        val task = Runnable {
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
                spliceAnchorPcr = headPcr
                spliceAnchorPid = headPcrPid
                sawAnchorPcr = false
                // Either signal on its own is enough to trim: PCR alone
                // still tells us where the replay ends.
                discardActive = spliceTarget != null || spliceAnchorPcr >= 0
                matchLen = 0
                foreignPcrCount = 0
                discardedBytes = 0L
                discardStartMs = 0L
            }
        }
        runCatching { executor.execute(task) }.onFailure {
            // Executor gone or queue truly saturated: realign at minimum
            // so the fresh connection cannot misalign every later packet.
            synchronized(lock) {
                carry = ByteArray(0)
                needResync = true
            }
        }
    }

    /** Append from the live tee. Playback owns this thread, so the wait
     *  for a slot is bounded; a chunk refused even after the bounded
     *  wait is dropped (with a gap marker) rather than stalling the
     *  picture. */
    fun append(data: ByteArray, offset: Int, length: Int) {
        if (closed || length <= 0) return
        val copy = data.copyOfRange(offset, offset + length)
        submit(blocking = false) { writeChunk(copy) }
    }

    /**
     * GH #55: append from the independent filler. Nothing is rendering
     * off this thread, so it can afford to wait for the writer instead
     * of punching holes in the buffer. It matters here specifically:
     * the filler's first seconds are a server backlog burst measured at
     * 60-150 Mbps in the field logs, an order of magnitude above the
     * live rate the queue was sized for, and every chunk dropped in
     * that window also breaks the splice hunt scanning through it.
     */
    fun appendFill(data: ByteArray, offset: Int, length: Int) {
        if (closed || length <= 0) return
        val copy = data.copyOfRange(offset, offset + length)
        submit(blocking = true) { writeChunk(copy) }
    }

    /** Take an admission permit, then hand the work to the writer
     *  thread.
     *
     *  GH #65 finding 1: the old policy still dropped in both modes.
     *  The filler's "blocking" wait was capped at 2 s, which a
     *  Streamer-class eMMC busy in a rollSegment eviction pass exceeds
     *  exactly when the join burst arrives, and the live tee never
     *  waited at all, so ExoPlayer's tune-in read-ahead burst (network
     *  speed, far above realtime) shredded the queue while the writer
     *  was stuck on disk. Now the filler waits for as long as the
     *  writer is alive (nothing renders off its thread; a drop is
     *  never the better trade), and the tee gets one bounded
     *  [LIVE_ADMIT_WAIT_MS] wait, which converts the burst into brief
     *  backpressure that ExoPlayer's own buffer absorbs. A tee drop is
     *  now the last resort, and it leaves a gap marker (see
     *  [onChunkDropped]) so it cannot break playback later. */
    private fun submit(blocking: Boolean, work: () -> Unit) {
        val admitted = if (blocking) {
            var got = false
            while (!closed) {
                try {
                    if (slots.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        got = true
                        break
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            got
        } else {
            slots.tryAcquire() || runCatching {
                slots.tryAcquire(LIVE_ADMIT_WAIT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        }
        if (!admitted) {
            onChunkDropped()
            return
        }
        try {
            executor.execute {
                try {
                    work()
                } finally {
                    slots.release()
                }
            }
        } catch (t: Throwable) {
            slots.release()
            onChunkDropped()
        }
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
        // GH #65: these bytes follow a time skip. Record exactly where
        // they start so the reader can hop the boundary instead of
        // feeding the PTS jump to the demuxer mid-stream.
        if (pendingGap) {
            pendingGap = false
            pendingDiscFlag = true
            currentFile?.name?.let { name ->
                val gap = TimeshiftGap(name, currentBytes, now)
                synchronized(gapList) { gapList.addLast(gap) }
                Log.i("TimeshiftBuffer", "gap marker at $name+$currentBytes")
            }
        }
        var p = from
        val end = from + len
        while (p < end) {
            val pcr = packetPcr(buf, p)
            if (pendingDiscFlag && pcr >= 0) {
                // First clock-carrying packet after a time skip: stamp
                // the adaptation-field discontinuity_indicator so the
                // stream itself declares the clock break. Media3 1.4.1's
                // TsExtractor ignores the bit (the gap marker above is
                // what drives recovery today), but the buffer is then
                // spec-correct for any future extractor upgrade, and the
                // patch happens BEFORE hashing/writing so disk bytes and
                // fingerprint hashes stay consistent.
                buf[p + 5] = (buf[p + 5].toInt() or 0x80).toByte()
                pendingDiscFlag = false
            }
            recentHashes.addLast(packetHash(buf, p))
            while (recentHashes.size > OVERLAP_RUN) recentHashes.removeFirst()
            if (pcr >= 0) {
                headPcr = pcr
                headPcrPid = packetPid(buf, p)
            }
            p += TimeshiftBufferStore.TS_PACKET
        }
        current?.write(buf, from, len)
        currentBytes += len
        headWallMs = now
    }

    /**
     * Discard the new connection's packets for as long as they are
     * content the buffer already holds, then splice.
     *
     * GH #51 established WHY: Dispatcharr hands a joining client the
     * last new_client_behind_seconds as an instant burst, so appending
     * the new connection as-is put a backwards PTS jump in the buffer
     * that the demuxer chewed through as artifacts and A/V desync.
     *
     * GH #55 established WHEN TO STOP. Two independent signals, in
     * priority order:
     *
     *  1. The byte fingerprint of our head ([spliceTarget]). When the
     *     replay really does contain our head, this splices bit-exactly.
     *
     *  2. The stream's own clock. Every PCR says where the incoming
     *     bytes sit relative to [spliceAnchorPcr], the last clock we
     *     committed. Once a PCR passes the anchor, the connection has
     *     caught up with our head and everything from that packet on is
     *     content we do NOT have: stop discarding immediately.
     *
     * Signal 2 is what makes the common failure benign. If the server
     * joins us at or ahead of our head there is no fingerprint to find,
     * and the old byte cap responded by binning 8 MiB of arriving video
     * and splicing a hole - turning a small genuine gap into a multi
     * second one. Now the very first PCR ends the discard, so a server
     * with no useful replay costs essentially nothing.
     *
     * The anchor is the last PCR we COMMITTED, and the head may sit up
     * to one PCR interval past it (~40-100 ms), so this can re-admit
     * that much duplicate. A sub-frame overlap is a far better failure
     * than a gap: the demuxer absorbs it, whereas a hole stalls the
     * audio sink.
     *
     * The byte cap survives only for streams with no PCR on the anchor
     * PID at all.
     */
    private fun trimDiscard(merged: ByteArray, whole: Int, now: Long) {
        val target = spliceTarget
        val anchorPcr = spliceAnchorPcr
        if (target == null && anchorPcr < 0) {
            discardActive = false
            commitPackets(merged, 0, whole, now)
            return
        }
        if (discardStartMs == 0L) discardStartMs = now
        var p = 0
        while (p < whole) {
            val packetStart = p
            if (target != null) {
                val h = packetHash(merged, p)
                matchLen = when {
                    h == target[matchLen] -> matchLen + 1
                    h == target[0] -> 1
                    else -> 0
                }
            }
            p += TimeshiftBufferStore.TS_PACKET
            if (target != null && matchLen == target.size) {
                // Fingerprint completed at THIS packet: bytes before and
                // including it are the replay; the rest of the chunk is
                // fresh continuation.
                endDiscard()
                Log.i("TimeshiftBuffer", "splice overlap trimmed (${discardedBytes + p} bytes)")
                if (p < whole) commitPackets(merged, p, whole - p, now)
                return
            }
            if (anchorPcr >= 0) {
                val pcr = packetPcr(merged, packetStart)
                if (pcr >= 0 &&
                    (spliceAnchorPid < 0 || packetPid(merged, packetStart) == spliceAnchorPid)
                ) {
                    sawAnchorPcr = true
                    val aheadMs = pcrDelta(pcr, anchorPcr) / 90
                    if (aheadMs > 0) {
                        // Caught up with our head. This packet and
                        // everything after it is new material. GH #65:
                        // a nonzero skip is still MISSING content, and
                        // byte-splicing it says nothing to the player;
                        // mark the gap so the reader hops it instead of
                        // hitting the AudioSink discontinuity ~7 s later
                        // (Destro706's tablet log: clean 15520 ms resync,
                        // then the exact same crash).
                        endDiscard()
                        if (aheadMs >= GAP_MARK_MIN_MS) pendingGap = true
                        Log.i(
                            "TimeshiftBuffer",
                            "splice resynced on stream clock ${aheadMs}ms past head after " +
                                "discarding ${discardedBytes + packetStart} bytes",
                        )
                        commitPackets(merged, packetStart, whole - packetStart, now)
                        return
                    }
                } else if (pcr >= 0 && !sawAnchorPcr && spliceAnchorPid >= 0) {
                    // GH #65 finding 2: the incoming connection carries a
                    // clock, just never on the PID we anchored. That is a
                    // DIFFERENT mux (Dispatcharr failover swaps the
                    // upstream feed), so neither the fingerprint nor the
                    // anchor clock can ever appear; scanning on just binned
                    // the whole 8 MiB cap of good video on the Streamer
                    // and spliced a giant hole. Keep the new feed from the
                    // first few foreign clocks and mark the gap.
                    foreignPcrCount++
                    if (foreignPcrCount >= FOREIGN_PCR_LIMIT) {
                        endDiscard()
                        pendingGap = true
                        Log.w(
                            "TimeshiftBuffer",
                            "splice clock moved to pid ${packetPid(merged, packetStart)}; " +
                                "treating as a new feed after discarding " +
                                "${discardedBytes + packetStart} bytes",
                        )
                        commitPackets(merged, packetStart, whole - packetStart, now)
                        return
                    }
                }
            }
        }
        discardedBytes += whole
        val elapsed = now - discardStartMs
        if (sawAnchorPcr) {
            // Steering by the clock: the replay is real and every byte
            // discarded is content we hold. Only a runaway trips this.
            if (elapsed > OVERLAP_PCR_SEARCH_MS) {
                endDiscard()
                // GH #65: a forced gap must be survivable; mark it so the
                // reader hops it instead of crashing the audio sink.
                pendingGap = true
                Log.w(
                    "TimeshiftBuffer",
                    "splice clock never passed the head in ${elapsed}ms " +
                        "($discardedBytes bytes); splicing with a gap",
                )
            }
        } else if (discardedBytes > OVERLAP_SEARCH_CAP || elapsed > OVERLAP_SEARCH_MS) {
            endDiscard()
            // GH #65: same survivability marker for the no-signal cap.
            pendingGap = true
            Log.w(
                "TimeshiftBuffer",
                "splice found no head fingerprint and no stream clock within " +
                    "$discardedBytes bytes / ${elapsed}ms; splicing with a gap",
            )
        }
    }

    private fun endDiscard() {
        discardActive = false
        spliceTarget = null
        spliceAnchorPcr = -1L
        spliceAnchorPid = -1
        sawAnchorPcr = false
        foreignPcrCount = 0
    }

    /**
     * The 33-bit 90 kHz PCR base carried by this packet, or -1 if it has
     * none. Layout: byte 3 bits 5-4 are adaptation_field_control; a
     * value of 2 or 3 means an adaptation field follows at byte 4 as
     * (length, flags, ...), and flag 0x10 puts the 48-bit PCR in the
     * next 6 bytes - 33 bits of base, 6 reserved, 9 of extension. Only
     * the base is needed to order two points in the same stream.
     */
    private fun packetPcr(buf: ByteArray, offset: Int): Long {
        if (buf[offset] != 0x47.toByte()) return -1L
        val afc = (buf[offset + 3].toInt() shr 4) and 0x03
        if (afc != 2 && afc != 3) return -1L
        val afLen = buf[offset + 4].toInt() and 0xFF
        // Needs the flags byte plus 6 PCR bytes, and must stay inside
        // the packet: a malformed length must not read into the next one.
        if (afLen < 7 || 5 + afLen > TimeshiftBufferStore.TS_PACKET) return -1L
        if (buf[offset + 5].toInt() and 0x10 == 0) return -1L
        return ((buf[offset + 6].toLong() and 0xFF) shl 25) or
            ((buf[offset + 7].toLong() and 0xFF) shl 17) or
            ((buf[offset + 8].toLong() and 0xFF) shl 9) or
            ((buf[offset + 9].toLong() and 0xFF) shl 1) or
            ((buf[offset + 10].toLong() and 0x80) shr 7)
    }

    private fun packetPid(buf: ByteArray, offset: Int): Int =
        ((buf[offset + 1].toInt() and 0x1F) shl 8) or (buf[offset + 2].toInt() and 0xFF)

    /** Signed distance a - b in 90 kHz ticks, tolerating the 33-bit
     *  wrap (a stream that wraps mid-splice must not read as a 26-hour
     *  jump backwards). */
    private fun pcrDelta(a: Long, b: Long): Long {
        var d = a - b
        if (d > PCR_WRAP / 2) d -= PCR_WRAP
        if (d < -PCR_WRAP / 2) d += PCR_WRAP
        return d
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
                // GH #65: markers for evicted segments are dead weight.
                synchronized(gapList) { gapList.removeAll { it.segName == seg.name } }
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
        val dropped = droppedChunks.get()
        if (dropped > 0) {
            Log.w("TimeshiftBuffer", "session ${sessionDir.name} dropped $dropped chunk(s)")
        }
        // Flush pending writes, then close the file on the writer thread
        // so we never truncate a chunk mid-write. Submitted directly:
        // close must not be refused for want of an admission permit.
        runCatching { executor.execute { synchronized(lock) { closeLocked() } } }
        executor.shutdown()
    }

    private fun closeLocked() {
        closed = true
        current?.close()
        current = null
    }
}
