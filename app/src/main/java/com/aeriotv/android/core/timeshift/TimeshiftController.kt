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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        }
    }

    /**
     * Stop buffering (leaving fullscreen live: channel close, minimize,
     * multiview, PiP handoff). Buffered data stays on disk until the
     * retention reaper ages it out.
     */
    fun onFullscreenLiveStopped() {
        stopSessionInternal()
        _state.value = State()
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
                fillClient.newCall(req).execute().use { resp ->
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

    private fun stopIndependentFill() {
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
            startIndependentFill()
        }
    }

    /** Short pause resumed on the untouched live pipeline: the tee is
     *  reading again; retire the filler (it may not have started). */
    fun onLiveResumedAtEdge() {
        stopIndependentFill()
    }

    /** Playback switched onto the buffer at [atWallMs]. */
    fun onEnterTimeshift(atWallMs: Long) {
        val w = activeWriter ?: return
        startIndependentFill()
        _state.value = _state.value.copy(
            timeshifting = true,
            baseWallMs = atWallMs,
            tailWallMs = w.tailWallMs,
            headWallMs = w.headWallMs,
        )
    }

    /** Playback returned to the direct live stream. */
    fun onGoLive() {
        stopIndependentFill()
        _state.value = _state.value.copy(timeshifting = false, baseWallMs = 0)
    }

    /** Poll tick from the chrome while visible: refresh window bounds. */
    fun refreshWindow() {
        val w = activeWriter ?: return
        _state.value = _state.value.copy(tailWallMs = w.tailWallMs, headWallMs = w.headWallMs)
    }

    private fun stopSessionInternal() {
        stopIndependentFill()
        activeWriter?.close()
        activeWriter = null
    }
}
