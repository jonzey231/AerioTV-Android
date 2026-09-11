package com.aeriotv.android.core.playback

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import android.net.Uri

/**
 * Always-on, release-safe playback tracer. The Android counterpart of the
 * Apple player's [TUNE] / STALL / feed-starvation log lines, so a Google TV
 * or Shield log can be read the same way an Apple TV log is: one tune costs
 * at most ~8 lines, steady playback one line per 15 s, a stall two lines.
 *
 * Deliberately separate from [AerioExoPlayerHolder]'s DiagnosticAnalyticsListener,
 * which stays BuildConfig.DEBUG-only and is a firehose. Everything here is
 * Log.i/Log.w under the single tag `AerioTrace`, cheap enough to ship:
 *   adb logcat -s AerioTrace
 *
 * This class NEVER changes playback behavior. It only observes.
 *
 * Threading: byte accounting arrives on ExoPlayer loader threads, everything
 * else on the main thread, so the feed window is guarded by [feedLock].
 */
@OptIn(UnstableApi::class)
class PlaybackTracer {

    // ---- current tune timeline (all SystemClock.elapsedRealtime stamps) ----
    @Volatile private var pressAtMs = 0L
    @Volatile private var channelName: String = "?"
    private var playUrlAtMs = 0L
    private var openAtMs = 0L
    @Volatile private var firstByteAtMs = 0L
    private var readyAtMs = 0L
    private var firstFrameAtMs = 0L
    private var formatLogged = false
    private var summaryLogged = false

    // ---- steady-state counters, reset per tune ----
    private var stallCount = 0
    private var stallStartedAtMs = 0L
    private var droppedTotal = 0
    private var droppedAtLastPerf = 0
    private var lastPerfAtMs = 0L
    @Volatile private var bitrateEstimateBps = 0L

    // ---- feed pacing window ----
    private val feedLock = Any()
    /** 1 s buckets of bytes read, covering [FEED_WINDOW_MS]. */
    private val feedBuckets = LongArray(FEED_WINDOW_BUCKETS)
    private var feedBucketIndex = 0
    private var feedBucketStartedAtMs = 0L
    private var lastByteAtMs = 0L
    /** (stamp, gapMs) pairs, pruned to the rolling window by [worstGapMs]. */
    private val feedGaps = ArrayDeque<Pair<Long, Long>>()
    private var lastGapWarnAtMs = 0L

    private fun now() = SystemClock.elapsedRealtime()

    /** Delta from the press that started this tune (or from playUrl when the
     *  tune had no user press, e.g. a watchdog re-prime). */
    private fun sincePress(stamp: Long): Long = if (pressAtMs == 0L) 0L else stamp - pressAtMs

    // ---- tune lifecycle ----

    /**
     * Stamp the REAL key event that starts a live channel change (D-pad
     * Up/Down zap, number entry, channel-list or recents pick, the guide's
     * select press). Called before the tune reaches the holder so
     * press->firstFrame is measured end to end, exactly like tvOS.
     */
    fun markPress(name: String?) {
        pressAtMs = now()
        channelName = name?.takeIf { it.isNotBlank() } ?: "?"
        Log.i(TAG, "[TUNE] press ch=$channelName")
    }

    /** A new stream is being primed. [kind] is live / catchup / vod / dvr. */
    fun markTuneStart(name: String?, kind: String) {
        val n = now()
        // A press older than this was not what caused this prime (a watchdog
        // reload, a follow-poller re-prime); start the clock here instead.
        if (pressAtMs == 0L || n - pressAtMs > PRESS_MAX_AGE_MS) pressAtMs = n
        name?.takeIf { it.isNotBlank() }?.let { channelName = it }
        playUrlAtMs = n
        openAtMs = 0L
        firstByteAtMs = 0L
        readyAtMs = 0L
        firstFrameAtMs = 0L
        formatLogged = false
        summaryLogged = false
        stallCount = 0
        stallStartedAtMs = 0L
        droppedTotal = 0
        droppedAtLastPerf = 0
        lastPerfAtMs = n
        synchronized(feedLock) {
            feedBuckets.fill(0L)
            feedBucketIndex = 0
            feedBucketStartedAtMs = n
            lastByteAtMs = n
            feedGaps.clear()
            lastGapWarnAtMs = 0L
        }
        Log.i(TAG, "[TUNE] playUrl ch=$channelName +${sincePress(n)}ms url-kind=$kind")
    }

    private fun onOpen() {
        if (openAtMs != 0L) return
        openAtMs = now()
        Log.i(TAG, "[TUNE] open +${sincePress(openAtMs)}ms")
    }

    /** Byte accounting. Called from loader threads (DataSource wrapper) and
     *  from onLoadCompleted for chunk-based sources. */
    fun onBytes(count: Long) {
        if (count <= 0L) return
        val n = now()
        if (firstByteAtMs == 0L) {
            firstByteAtMs = n
            Log.i(TAG, "[TUNE] firstByte +${sincePress(n)}ms")
        }
        synchronized(feedLock) {
            advanceFeedBuckets(n)
            feedBuckets[feedBucketIndex] += count
            val gap = n - lastByteAtMs
            if (gap >= FEED_GAP_RECORD_MS) {
                feedGaps.addLast(n to gap)
                if (n - lastGapWarnAtMs >= FEED_GAP_WARN_COOLDOWN_MS) {
                    lastGapWarnAtMs = n
                    Log.w(TAG, "[FEED] gap ${gap}ms without bytes")
                }
            }
            lastByteAtMs = n
        }
    }

    /** Must be called holding [feedLock]. */
    private fun advanceFeedBuckets(nowMs: Long) {
        val elapsed = nowMs - feedBucketStartedAtMs
        if (elapsed < FEED_BUCKET_MS) return
        val steps = (elapsed / FEED_BUCKET_MS).toInt()
        repeat(steps.coerceAtMost(FEED_WINDOW_BUCKETS)) {
            feedBucketIndex = (feedBucketIndex + 1) % FEED_WINDOW_BUCKETS
            feedBuckets[feedBucketIndex] = 0L
        }
        feedBucketStartedAtMs += steps * FEED_BUCKET_MS
    }

    private fun onFormat(format: Format) {
        if (formatLogged) return
        formatLogged = true
        val fps = if (format.frameRate > 0f) "${format.frameRate}" else "?"
        Log.i(TAG, "[TUNE] format ${format.width}x${format.height} $fps ${format.sampleMimeType}")
    }

    private fun onReady() {
        if (readyAtMs != 0L) return
        readyAtMs = now()
        Log.i(TAG, "[TUNE] ready +${sincePress(readyAtMs)}ms")
    }

    /** One summary line per tune, same shape as the tvOS one. */
    fun onFirstFrame() {
        if (summaryLogged) return
        summaryLogged = true
        firstFrameAtMs = now()
        val total = sincePress(firstFrameAtMs)
        fun d(stamp: Long): String = if (stamp == 0L) "n/a" else "${sincePress(stamp)}"
        Log.i(
            TAG,
            "[TUNE] press->firstFrame ${total}ms ch=$channelName | " +
                "playUrl=${d(playUrlAtMs)} open=${d(openAtMs)} firstByte=${d(firstByteAtMs)} " +
                "ready=${d(readyAtMs)} frame=${d(firstFrameAtMs)}",
        )
    }

    // ---- stalls ----

    private fun onBuffering(player: Player?) {
        // Only a rebuffer counts: everything before the first frame is the
        // cold start, already covered by the [TUNE] lines.
        if (firstFrameAtMs == 0L || stallStartedAtMs != 0L) return
        val n = now()
        stallStartedAtMs = n
        stallCount += 1
        val pos = player?.currentPosition ?: 0L
        val buffered = player?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) } ?: 0L
        Log.w(
            TAG,
            "[STALL] at +${n - firstFrameAtMs}ms pos=${pos}ms buffered=${buffered}ms " +
                "liveOffset=${liveOffsetMs(player)}ms ch=$channelName",
        )
    }

    private fun onReadyAfterStall() {
        val started = stallStartedAtMs
        if (started == 0L) return
        stallStartedAtMs = 0L
        Log.i(TAG, "[STALL] recovered after ${now() - started}ms")
    }

    private fun liveOffsetMs(player: Player?): Long {
        val off = player?.currentLiveOffset ?: C.TIME_UNSET
        return if (off == C.TIME_UNSET) -1L else off
    }

    // ---- periodic heartbeat ----

    /**
     * Drive from the holder's existing 1 s watchdog poll. Emits one [PERF] +
     * one [FEED] line per [PERF_INTERVAL_MS] while the player is actually
     * playing; silent otherwise.
     */
    fun tick(player: Player?) {
        val p = player ?: return
        if (!p.isPlaying) return
        val n = now()
        if (n - lastPerfAtMs < PERF_INTERVAL_MS) return
        lastPerfAtMs = n
        val droppedDelta = droppedTotal - droppedAtLastPerf
        droppedAtLastPerf = droppedTotal
        val buffered = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        Log.i(
            TAG,
            "[PERF] ch=$channelName stalls=$stallCount dropped=+$droppedDelta($droppedTotal) " +
                "buffered=${buffered}ms liveOffset=${liveOffsetMs(p)}ms " +
                "bw=${bitrateEstimateBps / 1000}kbps pos=${p.currentPosition}ms",
        )
        val (kbps, worstGap) = feedSnapshot(n)
        Log.i(TAG, "[FEED] ${kbps}kbps avg over 30s, worst gap ${worstGap}ms without bytes")
    }

    private fun feedSnapshot(nowMs: Long): Pair<Long, Long> = synchronized(feedLock) {
        advanceFeedBuckets(nowMs)
        val bytes = feedBuckets.sum()
        val kbps = bytes * 8L / 1000L / (FEED_WINDOW_MS / 1000L)
        while (feedGaps.isNotEmpty() && nowMs - feedGaps.first().first > FEED_WINDOW_MS) {
            feedGaps.removeFirst()
        }
        val liveGap = nowMs - lastByteAtMs
        val worst = (feedGaps.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(liveGap)
        kbps to worst
    }

    // ---- recovery visibility ----

    /** Mirror an existing watchdog / heal action into the same tag so a
     *  reload is visible next to the [TUNE] and [STALL] lines. */
    fun recover(what: String) {
        Log.w(TAG, "[RECOVER] $what ch=$channelName")
    }

    // ---- wiring ----

    /** Wrap a live [DataSource.Factory] so the progressive TS path (whose
     *  single load never "completes") still reports byte flow. */
    fun wrapDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { TracingDataSource(upstream.createDataSource(), this) }

    /** Always-on analytics listener; attach to the foreground player only. */
    val analyticsListener: AnalyticsListener = object : AnalyticsListener {
        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            onOpen()
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            onBytes(loadEventInfo.bytesLoaded)
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            onFormat(format)
        }

        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int,
        ) {
            when (state) {
                Player.STATE_READY -> { onReady(); onReadyAfterStall() }
                Player.STATE_BUFFERING -> onBuffering(tracedPlayer)
                else -> Unit
            }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            droppedTotal += droppedFrames
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            bitrateEstimateBps = bitrateEstimate
        }
    }

    /** Set by the holder so stall lines can read position / live offset.
     *  AnalyticsListener.EventTime carries a Timeline, not the player. */
    @Volatile var tracedPlayer: Player? = null

    /**
     * Pass-through [DataSource] that only counts bytes. Mirrors the shape of
     * the Live Rewind TeeDataSource so the live path keeps ONE wrapper style.
     */
    private class TracingDataSource(
        private val upstream: DataSource,
        private val tracer: PlaybackTracer,
    ) : DataSource {
        override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = upstream.read(buffer, offset, length)
            if (n > 0) tracer.onBytes(n.toLong())
            return n
        }

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()
    }

    companion object {
        private const val TAG = "AerioTrace"
        private const val PRESS_MAX_AGE_MS = 15_000L
        private const val PERF_INTERVAL_MS = 15_000L
        private const val FEED_WINDOW_MS = 30_000L
        private const val FEED_BUCKET_MS = 1_000L
        private const val FEED_WINDOW_BUCKETS = (FEED_WINDOW_MS / FEED_BUCKET_MS).toInt()
        private const val FEED_GAP_RECORD_MS = 2_000L
        private const val FEED_GAP_WARN_COOLDOWN_MS = 10_000L

        /** Classify a play URL for the [TUNE] playUrl line. Never logs the
         *  URL itself (it can embed credentials). */
        fun urlKind(url: String): String {
            val u = url.lowercase()
            return when {
                "/movie/" in u || "/vod/" in u || "/series/" in u -> "vod"
                "timeshift" in u || "catchup" in u || "streaming/timeshift" in u -> "catchup"
                "/dvr/" in u || "recording" in u -> "dvr"
                else -> "live"
            }
        }
    }
}
