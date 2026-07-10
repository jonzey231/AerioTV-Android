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
     *  drop the packet-fragment carry and re-scan for TS sync. */
    fun markDiscontinuity() {
        executor.execute {
            synchronized(lock) {
                carry = ByteArray(0)
                needResync = true
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

                if (current == null || now - currentStartWallMs >= TimeshiftBufferStore.SEGMENT_MS) {
                    rollSegment(now)
                }
                current?.write(merged, 0, whole)
                currentBytes += whole
                headWallMs = now
            } catch (t: Throwable) {
                Log.w("TimeshiftBuffer", "write failed, closing buffer: $t")
                closeLocked()
            }
        }
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
