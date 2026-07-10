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

    init {
        // Retention/budget reaper independent of new sessions: without
        // this, disabling the feature (or never watching live again)
        // stranded old buffers on disk until app data was cleared.
        scope.launch {
            runCatching {
                store.pruneExpired(prefs.liveRewindRetentionHours.first() * 3_600_000L)
                store.enforceBudget(prefs.liveRewindBudgetGB.first() * 1024L * 1024 * 1024)
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
                val retentionHours = prefs.liveRewindRetentionHours.first()
                val budgetGB = prefs.liveRewindBudgetGB.first()
                stopSessionInternal()
                val writer = store.startSession(
                    channelId = channelId,
                    channelName = channelName,
                    depthMs = depthMin * 60_000L,
                    retentionMs = retentionHours * 60L * 60 * 1000,
                    budgetBytes = budgetGB * 1024L * 1024 * 1024,
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
        fillJob = scope.launch {
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
                        if (n > 0) writer.append(buf, 0, n)
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
     * Cable-seamless pause: the player just pauses (no source switch),
     * its stalled live connection stops feeding the tee once the
     * internal read-ahead fills, so the filler must take over. Start it
     * DELAYED by roughly that read-ahead so the filler's join point
     * lines up with where the tee left off instead of duplicating the
     * last few seconds of stream.
     */
    fun onLivePaused() {
        if (pauseFillJob?.isActive == true || fillJob?.isActive == true) return
        pauseFillJob = scope.launch {
            kotlinx.coroutines.delay(8_000)
            // Serial scope: this cannot interleave with a Main-thread
            // rewind press starting its own filler (double-fill race).
            startIndependentFill()
        }
    }

    /** Short pause resumed on the untouched live pipeline: the tee is
     *  reading again; retire the filler (it may not have started). */
    fun onLiveResumedAtEdge() {
        scope.launch { stopIndependentFill() }
    }

    /** Playback switched onto the buffer at [atWallMs]. */
    fun onEnterTimeshift(atWallMs: Long) {
        val w = activeWriter ?: return
        scope.launch { startIndependentFill() }
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
        activeWriter?.close()
        activeWriter = null
    }
}
