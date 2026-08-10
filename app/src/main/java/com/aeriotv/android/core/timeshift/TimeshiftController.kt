package com.aeriotv.android.core.timeshift

import android.util.Log
import com.aeriotv.android.core.preferences.AppPreferences
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live Rewind session arbiter (task #143).
 *
 * Owns the active [TimeshiftWriter] and the UI-facing state. The
 * shared live player's tee (see [TeeDataSource] wiring in
 * AerioExoPlayerHolder) mirrors bytes into [activeWriter] whenever one
 * exists; PlayerScreen starts/stops sessions around fullscreen live
 * playback, per the locked v1 scope: fullscreen single-stream only,
 * multiview tiles / mini-player / PiP stay pure live.
 *
 * P1 wires enable + depth prefs; retention and budget ride interim
 * defaults until the P2 settings land.
 */
@Singleton
class TimeshiftController @Inject constructor(
    private val store: TimeshiftBufferStore,
    private val prefs: AppPreferences,
) {
    companion object {
        private const val TAG = "TimeshiftController"

        /** Retention as a USER concept died in the 2026-07-11 settings
         *  rework ("we don't really care how long the files are stored
         *  ... just delete the buffered video after an hour"): buffered
         *  video is removed this long after its session goes quiet. The
         *  liveRewindRetentionHours pref is dormant. */
        const val FIXED_RETENTION_MS = 60L * 60 * 1000
    }

    // Single-threaded: session start/stop/enter/exit all mutate the same
    // fields, and callers arrive from Main (transport buttons), IO (the
    // delayed pause filler), and the player thread. Serial confinement
    // makes start/stop ordering deterministic (a fast open-then-back
    // could previously stop BEFORE the start coroutine ran, orphaning a
    // writer the mini-player's tee fed forever) and makes the filler
    // is-active check atomic.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * GH #51 ROOT CAUSE: the independent filler is a BLOCKING network read
     * loop that runs for the whole connection lifetime. It used to launch
     * on the serial [scope]; with limitedParallelism(1) every control task
     * queued after it (stop-fill, Go Live's stop, the session close on
     * channel change, even the NEXT channel's session start) starved
     * behind the blocked worker - the filler was unstoppable once started
     * ("ghost streams until manually killed in the Dispatcharr GUI"), and
     * the next channel's tee kept appending into the previous channel's
     * buffer. Long-blocking work gets its own unbounded IO scope; the
     * serial scope stays the control plane.
     */
    private val fillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Retention/budget reaper independent of new sessions: without
        // this, disabling the feature (or never watching live again)
        // stranded old buffers on disk until app data was cleared.
        scope.launch {
            runCatching {
                store.pruneExpired(FIXED_RETENTION_MS)
                // Storage Limit setting removed: depth is the knob; the
                // free-space floor is the invisible seatbelt.
                store.enforceBudget(store.freeSpaceBudgetBytes())
            }.onFailure { Log.w(TAG, "startup reaper failed: $it") }
        }
    }

    @Volatile
    var activeWriter: TimeshiftWriter? = null
        private set

    /** Live stream URL + headers of the current session, captured at
     *  start so the independent filler can reconnect on its own. */
    @Volatile private var liveUrl: String? = null
    @Volatile private var liveHeaders: Map<String, String> = emptyMap()
    private var fillJob: kotlinx.coroutines.Job? = null

    private val fillClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class State(
        /** A buffer session is rolling for the current live channel. */
        val buffering: Boolean = false,
        /** Playback is on the buffer (paused/rewound) rather than the direct stream. */
        val timeshifting: Boolean = false,
        /** Oldest rewindable wall time. */
        val tailWallMs: Long = 0,
        /** Newest buffered wall time (the live edge). */
        val headWallMs: Long = 0,
        /** When timeshifting: wall time playback entered the buffer at. */
        val baseWallMs: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * Begin buffering [channelId] if Live Rewind is enabled. Called by
     * PlayerScreen when fullscreen live playback starts (and on channel
     * change, which implicitly ends the previous session).
     */
    fun onFullscreenLiveStarted(
        channelId: String,
        channelName: String,
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        liveUrl = streamUrl
        liveHeaders = headers
        scope.launch {
            // runCatching: session setup does disk IO (mkdirs, meta
            // write) that can throw on a full or flaky disk; an uncaught
            // exception here killed the whole process on every tune.
            runCatching {
                if (!prefs.liveRewindEnabled.first()) return@launch
                val depthMin = prefs.liveRewindDepthMinutes.first()
                stopSessionInternal()
                val writer = store.startSession(
                    channelId = channelId,
                    channelName = channelName,
                    depthMs = depthMin * 60_000L,
                    retentionMs = FIXED_RETENTION_MS,
                    budgetBytes = store.freeSpaceBudgetBytes(),
                )
                activeWriter = writer
                _state.value = State(
                    buffering = true,
                    tailWallMs = writer.sessionStartMs,
                    headWallMs = writer.sessionStartMs,
                )
                Log.i(TAG, "buffering started for $channelName")
            }.onFailure {
                Log.w(TAG, "session start failed: $it")
                activeWriter = null
                _state.value = State()
            }
        }
    }

    /**
     * Stop buffering (leaving fullscreen live: channel close, minimize,
     * multiview, PiP handoff). Buffered data stays on disk until the
     * retention reaper ages it out.
     */
    fun onFullscreenLiveStopped() {
        // Through the same serial scope as start so a fast tune-then-back
        // can never stop BEFORE the pending start runs.
        scope.launch {
            stopSessionInternal()
            _state.value = State()
        }
    }

    /**
     * While playback is ON the buffer, the shared player's live
     * connection (which carries the tee) is closed, so the buffer
     * would stop growing exactly when the user needs it to keep
     * rolling. This independent filler streams the SAME live URL into
     * the writer for the duration of the timeshift. It starts when
     * playback enters the buffer and stops on Go Live, so the
     * provider sees one active stream at a time (modulo a sub-second
     * splice overlap), which matters for single-connection accounts.
     */
    private fun startIndependentFill() {
        val url = liveUrl ?: return
        val writer = activeWriter ?: return
        if (fillJob?.isActive == true) return
        // New connection joining the proxy mid-packet: realign before
        // its bytes land in the buffer.
        writer.markDiscontinuity()
        fillJob = fillScope.launch {
            try {
                val req = Request.Builder().url(url).apply {
                    liveHeaders.forEach { (k, v) -> header(k, v) }
                }.build()
                val call = fillClient.newCall(req)
                fillCall = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "fill connect failed http=${resp.code}")
                        return@use
                    }
                    val src = resp.body?.byteStream() ?: return@use
                    val buf = ByteArray(64 * 1024)
                    while (currentCoroutineContext().isActive && !writer.closed) {
                        val n = src.read(buf)
                        if (n < 0) break
                        // appendFill, not append: nothing renders off this
                        // thread, so it waits for the writer instead of
                        // punching holes in the buffer when the server's
                        // join backlog arrives far faster than live (GH #55).
                        if (n > 0) writer.appendFill(buf, 0, n)
                    }
                }
            } catch (t: Throwable) {
                if (fillJob?.isActive == true) Log.w(TAG, "fill stream error: $t")
            }
            Log.i(TAG, "independent fill ended")
        }
        Log.i(TAG, "independent fill started")
    }

    /** The filler's in-flight OkHttp call. Cancelled explicitly on stop:
     *  a coroutine cancel alone is cooperative and the blocking
     *  `src.read` otherwise held the provider connection open (alongside
     *  the fresh live one) until the 60s read timeout. */
    @Volatile private var fillCall: okhttp3.Call? = null

    private fun stopIndependentFill() {
        fillCall?.cancel()
        fillCall = null
        fillJob?.cancel()
        fillJob = null
        pauseFillJob?.cancel()
        pauseFillJob = null
    }

    private var pauseFillJob: kotlinx.coroutines.Job? = null

    /**
     * Cable-seamless pause: the player just pauses (no source switch)
     * and, because this is a LIVE stream, ExoPlayer keeps downloading at
     * 1x into its read-ahead - the tee stays at the live edge and keeps
     * feeding the buffer until maxBuffer fills (tens of seconds), or
     * until the server kicks the idle client. The old fixed 8s delay
     * started the filler while the tee was still reading, so BOTH
     * connections interleaved appends into the same buffer (GH #51
     * corruption) and the filler's server-side replay landed tens of MB
     * behind the head. Now the filler starts only when bytes actually
     * STOP arriving (head idle > 4s): no double-feed, and the splice
     * overlap shrinks to the server's small join replay, which the
     * writer's trimmer removes.
     */
    fun onLivePaused() {
        if (pauseFillJob?.isActive == true || fillJob?.isActive == true) return
        pauseFillJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                val w = activeWriter ?: return@launch
                if (w.closed || fillJob?.isActive == true) return@launch
                if (System.currentTimeMillis() - w.headWallMs > 4_000) break
            }
            // Serial scope: this cannot interleave with a Main-thread
            // rewind press starting its own filler (double-fill race).
            startIndependentFill()
        }
    }

    /** Short pause resumed on the untouched live pipeline: the tee is
     *  reading again; retire the filler (it may not have started). If the
     *  filler DID run (a media-key resume after a long pause, GH #51),
     *  the tee's next append follows the filler's bytes from a different
     *  connection - mark the splice so the writer realigns and trims. */
    fun onLiveResumedAtEdge() {
        scope.launch {
            val fillerRan = fillJob?.isActive == true
            stopIndependentFill()
            if (fillerRan) activeWriter?.markDiscontinuity()
        }
    }

    /** Playback switched onto the buffer at [atWallMs]. */
    fun onEnterTimeshift(atWallMs: Long) {
        val w = activeWriter ?: return
        scope.launch {
            // Retire a pending pause-stall watcher (its job is starting
            // the SAME filler); a filler already running keeps running.
            pauseFillJob?.cancel()
            pauseFillJob = null
            startIndependentFill()
        }
        _state.update {
            it.copy(
                timeshifting = true,
                baseWallMs = atWallMs,
                tailWallMs = w.tailWallMs,
                headWallMs = w.headWallMs,
            )
        }
    }

    /** Playback returned to the direct live stream. */
    fun onGoLive() {
        scope.launch { stopIndependentFill() }
        _state.update { it.copy(timeshifting = false, baseWallMs = 0) }
    }

    /** Poll tick from the chrome while visible: refresh window bounds. */
    fun refreshWindow() {
        val w = activeWriter ?: return
        if (w.closed) {
            // Disk-full (or any write failure) self-closed the writer;
            // stop advertising a rewind window that can no longer grow.
            _state.update { it.copy(buffering = false, timeshifting = false) }
            return
        }
        _state.update { it.copy(tailWallMs = w.tailWallMs, headWallMs = w.headWallMs) }
    }

    private fun stopSessionInternal() {
        stopIndependentFill()
        val finished = activeWriter
        finished?.close()
        activeWriter = null
        // Discord (di5cord20, Formuler Z11): app storage past 3 GB. A closed
        // session's directory is DEAD BYTES - every read path in
        // TimeshiftDataSources goes through writerProvider(), i.e. the ACTIVE
        // writer, and startSession() always mints a fresh `sess_<now>` dir, so
        // nothing in the app can open a session again once it has ended. They
        // were nevertheless kept for the full FIXED_RETENTION_MS hour and
        // measured against a "budget" of totalBytes() + (free - 2 GB), which on
        // a box with a large disk is not a budget at all. Every channel change
        // therefore stranded up to a depth's worth of transport stream - 30
        // minutes of HD runs to a gigabyte or two - for an hour, on the slow
        // eMMC of exactly the devices least able to afford it.
        //
        // Delete it here instead. No feature is lost, because no feature could
        // ever have used it. pruneExpired/enforceBudget stay as the
        // crash-recovery net for a process that dies mid-session, which is now
        // the only way a directory can be left behind.
        //
        // Deleting files another thread may still hold open is safe here: the
        // unlink leaves existing descriptors valid, so a data source racing the
        // teardown reads to its natural end instead of faulting.
        finished?.let { w ->
            runCatching {
                if (w.sessionDir.deleteRecursively()) {
                    Log.i(TAG, "released buffer ${w.sessionDir.name}")
                }
            }.onFailure { Log.w(TAG, "session cleanup failed: $it") }
        }
    }
}
