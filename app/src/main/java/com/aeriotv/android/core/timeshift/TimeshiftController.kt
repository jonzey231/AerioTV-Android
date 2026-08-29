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

    /** Channel identity of the FOREGROUND session, so a channel flip can
     *  demote the outgoing session into [retained] instead of deleting it. */
    @Volatile private var currentChannelId: String? = null
    @Volatile private var currentChannelName: String? = null

    /**
     * Keep Recent Channels Live (iOS parity): a flipped-away channel's
     * session keeps its writer and gets its own independent filler, so
     * flipping back restores the full rewind timeline including the time
     * away. Insertion order = recency; oldest is evicted past the count.
     * Mutated only on the serial [scope].
     */
    private class RetainedSession(
        val channelId: String,
        val channelName: String,
        /** FROZEN at demotion. Never consults [currentPlayUrlProvider]:
         *  by demotion time the holder is already tuning the NEW channel,
         *  so chasing the provider would record the wrong channel. */
        val url: String,
        val headers: Map<String, String>,
        val writer: TimeshiftWriter,
    ) {
        @Volatile var fillCall: okhttp3.Call? = null
        var fillJob: kotlinx.coroutines.Job? = null
    }

    private val retained = LinkedHashMap<String, RetainedSession>()

    data class RetainedChannel(val channelId: String, val channelName: String)

    /** UI-facing list of channels being kept live (recency order, oldest
     *  first). LocalRecordingService.activeFlow shape: collect to drive
     *  the Live TV indicator. */
    private val _retainedChannels = MutableStateFlow<List<RetainedChannel>>(emptyList())
    val retainedChannels: StateFlow<List<RetainedChannel>> = _retainedChannels

    private fun publishRetained() {
        _retainedChannels.value = retained.values.map { RetainedChannel(it.channelId, it.channelName) }
    }

    /** The outgoing channel's ACTUAL play URL (post LAN/WAN failover),
     *  snapshotted by PlayerScreen at the top of a channel flip while the
     *  holder is still on the OLD channel. Demotion prefers this over the
     *  tune-time [liveUrl] for the same reason the filler does. */
    @Volatile private var lastPlayUrlSnapshot: String? = null
    fun noteChannelLeaving() {
        lastPlayUrlSnapshot = currentPlayUrlProvider?.invoke()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** The URL the player is ACTUALLY playing right now (post LAN/WAN
     *  failover), supplied by the holder via PlayerScreen. The tune-time
     *  [liveUrl] comes from the stored channel row, whose embedded base
     *  can be stale after a server move (2026-08-13 VPS migration: live
     *  video failed over to WAN but the filler kept dialing the dead LAN
     *  address, so pause/resume stalled at head forever). The filler
     *  prefers this provider's http(s) result; buffer playback leaves the
     *  holder's lastPlayUrl on the live URL, so the value is valid exactly
     *  when the filler starts. */
    @Volatile var currentPlayUrlProvider: (() -> String?)? = null
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
                val enabled = prefs.liveRewindEnabled.first()
                val keepRecent = enabled && prefs.liveRewindKeepRecent.first()
                // Settings changed underneath live retained sessions:
                // release them rather than leaving orphan fillers running.
                if (!keepRecent && retained.isNotEmpty()) {
                    retained.values.forEach { releaseRetained(it) }
                    retained.clear()
                    publishRetained()
                }
                if (!enabled) return@launch
                val depthMin = prefs.liveRewindDepthMinutes.first()
                val keepCount = prefs.liveRewindKeepCount.first()
                if (keepRecent) demoteCurrentSession(keepCount) else stopSessionInternal()
                // Flip-back: adopt the retained session so the rewind
                // timeline spans the time away. The tee is a different
                // connection than the retained filler, so mark the splice.
                val adopted = retained.remove(channelId)
                if (adopted != null) {
                    stopRetainedFill(adopted)
                    publishRetained()
                    if (!adopted.writer.closed) {
                        adopted.writer.markDiscontinuity()
                        currentChannelId = channelId
                        currentChannelName = channelName
                        activeWriter = adopted.writer
                        _state.value = State(
                            buffering = true,
                            tailWallMs = adopted.writer.tailWallMs,
                            headWallMs = adopted.writer.headWallMs,
                        )
                        Log.i(TAG, "adopted retained buffer for $channelName")
                        return@launch
                    }
                    // Writer died in the background (disk full, etc.):
                    // release the corpse and fall through to a fresh start.
                    releaseRetained(adopted)
                }
                val writer = store.startSession(
                    channelId = channelId,
                    channelName = channelName,
                    depthMs = depthMin * 60_000L,
                    retentionMs = FIXED_RETENTION_MS,
                    budgetBytes = store.freeSpaceBudgetBytes(),
                    protectedDirs = retained.values.map { it.writer.sessionDir }.toSet(),
                )
                currentChannelId = channelId
                currentChannelName = channelName
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
     * Channel flip with Keep Recent Channels Live on: instead of closing
     * and deleting the outgoing session, park it in [retained] with its
     * own independent filler so the buffer keeps rolling. Oldest retained
     * session past [maxCount] is evicted (its buffer deleted). Serial
     * scope only.
     */
    private fun demoteCurrentSession(maxCount: Int) {
        stopIndependentFill()
        val writer = activeWriter
        val chId = currentChannelId
        val chName = currentChannelName
        activeWriter = null
        currentChannelId = null
        currentChannelName = null
        if (writer == null) return
        // Freeze the URL now: the snapshot PlayerScreen took while the old
        // channel was still up beats the tune-time row URL (VPS-migration
        // rule), and the live provider lambda is already off-limits (it
        // points at the incoming channel).
        val url = lastPlayUrlSnapshot ?: liveUrl
        lastPlayUrlSnapshot = null
        if (chId == null || url == null || writer.closed) {
            // Not retainable: release exactly like stopSessionInternal.
            writer.close()
            runCatching { writer.sessionDir.deleteRecursively() }
            return
        }
        val session = RetainedSession(chId, chName ?: chId, url, liveHeaders, writer)
        retained.remove(chId)
        retained[chId] = session
        while (retained.size > maxCount.coerceIn(1, 5)) {
            val oldest = retained.entries.first()
            retained.remove(oldest.key)
            releaseRetained(oldest.value)
            Log.i(TAG, "retention evicted ${oldest.value.channelName}")
        }
        startRetainedFill(session)
        publishRetained()
        Log.i(TAG, "keeping $chName live (${retained.size} retained)")
    }

    /**
     * A retained channel's buffer is fed ONLY by its own connection: a
     * reconnecting fill loop against the frozen URL. Stops for good on
     * HTTP 4xx (auth/connection-cap refusal - retrying would hammer the
     * provider), on writer death, and on eviction/stop. Every (re)connect
     * is a splice, so the writer realigns per attempt.
     */
    private fun startRetainedFill(session: RetainedSession) {
        session.fillJob = fillScope.launch {
            var attempts = 0
            while (currentCoroutineContext().isActive && !session.writer.closed) {
                try {
                    session.writer.markDiscontinuity()
                    val req = Request.Builder().url(session.url).apply {
                        session.headers.forEach { (k, v) -> header(k, v) }
                    }.build()
                    val call = fillClient.newCall(req)
                    session.fillCall = call
                    call.execute().use { resp ->
                        if (resp.code in 400..499) {
                            Log.w(TAG, "retained fill refused http=${resp.code} for ${session.channelName}; stopping")
                            return@launch
                        }
                        if (!resp.isSuccessful) return@use
                        attempts = 0
                        val src = resp.body?.byteStream() ?: return@use
                        val buf = ByteArray(64 * 1024)
                        while (currentCoroutineContext().isActive && !session.writer.closed) {
                            val n = src.read(buf)
                            if (n < 0) break
                            if (n > 0) session.writer.appendFill(buf, 0, n)
                        }
                    }
                } catch (t: Throwable) {
                    if (session.fillJob?.isActive != true) return@launch
                    Log.w(TAG, "retained fill error for ${session.channelName}: $t")
                }
                if (++attempts > 20) {
                    Log.w(TAG, "retained fill gave up for ${session.channelName}")
                    return@launch
                }
                kotlinx.coroutines.delay(3_000)
            }
        }
        Log.i(TAG, "retained fill started for ${session.channelName}")
    }

    /** Same discipline as [stopIndependentFill]: cancel the CALL, not just
     *  the coroutine, or the blocking read holds the provider connection
     *  open until the 60s read timeout (ghost streams). */
    private fun stopRetainedFill(session: RetainedSession) {
        session.fillCall?.cancel()
        session.fillCall = null
        session.fillJob?.cancel()
        session.fillJob = null
    }

    /** Stop and delete a retained session's buffer. Caller removes it from
     *  [retained] and publishes. */
    private fun releaseRetained(session: RetainedSession) {
        stopRetainedFill(session)
        session.writer.close()
        runCatching {
            if (session.writer.sessionDir.deleteRecursively()) {
                Log.i(TAG, "released retained buffer ${session.writer.sessionDir.name}")
            }
        }.onFailure { Log.w(TAG, "retained cleanup failed: $it") }
    }

    /** Indicator dialog: stop keeping one channel live. */
    fun stopRetainedChannel(channelId: String) {
        scope.launch {
            retained.remove(channelId)?.let { releaseRetained(it) }
            publishRetained()
        }
    }

    /** Indicator dialog "Stop All", and the app-background policy: retained
     *  fillers are network connections the user cannot see, so they do not
     *  outlive the app being on screen (anti-ghost-stream rule; there is no
     *  FGS for this convenience feature). */
    fun stopAllRetained() {
        scope.launch {
            if (retained.isEmpty()) return@launch
            retained.values.forEach { releaseRetained(it) }
            retained.clear()
            publishRetained()
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
        val playing = currentPlayUrlProvider?.invoke()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val url = playing ?: liveUrl ?: return
        if (playing != null && playing != liveUrl) {
            Log.i(TAG, "fill using player's active URL (failover happened since tune)")
        }
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
        currentChannelId = null
        currentChannelName = null
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
