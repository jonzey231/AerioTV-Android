package com.aeriotv.android.core.playback

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Client-driven stream failover for live tunes (Apple parity: TSHLSRemuxer
 * firstByteDeadline / handleNoFirstByte / stepFailover, commit dc52f2a).
 *
 * A Dispatcharr ingest can CONNECT and then stay silent: the server's own health
 * checks cannot fail a connected-but-silent stream over for roughly 75 s
 * (60 s channel_init_grace_period plus three checks at 5 s), and the holder's
 * live no-data ceiling is 50 s. So the client arms its own silent-start
 * deadline per live ingest (deliberately SEPARATE from any HTTP timeout) and, on
 * a Dispatcharr Direct Connect admin account, walks the channel's member streams
 * with change_stream instead of waiting.
 *
 * The deadline measures SILENCE, never elapsed time: while bytes keep arriving,
 * however slowly, the stream is never abandoned. The first ingest of a tune gets
 * a long budget (an over-the-air tuner has to lock before it can send anything)
 * stretched further on a channel this device has LEARNED to start slowly; every
 * later ingest of the same tune gets the short, responsive budget. A definitive
 * server answer (503 with a reason, or a hard player error) still walks at once.
 *
 * The ExoPlayer connection is KEPT OPEN across a step: Dispatcharr swaps the
 * upstream in place behind the same /proxy/ts/stream/<uuid> URL, so re-priming
 * here would only drop the one connection the channel has and cold-resolve back
 * to the channel's default stream.
 *
 * Non-admin / Xtream / M3U / single-stream channels get the "Reconnecting..."
 * status at the deadline and nothing else: their existing retry ladder
 * (AerioExoPlayerHolder's no-data net plus the Task #150 unavailable overlay) is
 * untouched.
 *
 * Owned by [AerioExoPlayerHolder]; never driven from a composable.
 */
class LiveStreamFailover(
    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    /** Names the surface in the [STATUS] log line: "player" for the live
     *  holder, "tile" for a Multiview tile (Apple TSHLSRemuxer wording). */
    private val surface: String = "player",
) {

    /** How a walk step ended, for an owner that must act on it (a Multiview
     *  tile restarts its pipeline after a 503 step, Apple stepFailover
     *  restartPipeline). The live holder leaves [onWalkOutcome] unset. */
    enum class WalkOutcome {
        /** change_stream to the next member stream succeeded. */
        SWITCHED,

        /** No step was possible (no hooks, stream list unavailable, single
         *  stream, change_stream refused, channel session gone). */
        NO_SWITCH,

        /** Every member stream has been tried. */
        EXHAUSTED,
    }

    /** Terminal outcome of a walk step. [from503] is true for the 503 entry
     *  point, where the ingest is already gone; false for the silent-start
     *  deadline, where it stays open. Not called when the walk ended because
     *  the tune went away or bytes arrived. Main thread. */
    @Volatile var onWalkOutcome: ((from503: Boolean, outcome: WalkOutcome) -> Unit)? = null

    /**
     * Server access, wired by PlayerScreen (the same shape as the holder's
     * onTerminalErrorRebuildUrl hook: the channel identity arrives as a
     * PARAMETER, never captured, so a channel flip can never point the walk at
     * the previous channel).
     */
    data class Hooks(
        /** Dispatcharr Direct Connect + admin + an integer channel pk. */
        val canSwitch: (channelId: String) -> Boolean,
        /** GET .../channels/<pk>/streams/ mapped to stream pks, priority order. */
        val listStreamIds: suspend (channelId: String) -> List<Int>,
        /** GET /proxy/ts/status/<uuid> stream_id; trusted only to seed the walk. */
        val currentStreamId: suspend (channelUuid: String) -> Int?,
        /** POST /proxy/ts/change_stream/<uuid>; throws when the server refuses. */
        val changeStream: suspend (channelUuid: String, streamId: Int) -> Unit,
    )

    @Volatile var hooks: Hooks? = null

    /** Called when every member stream has been walked without a byte; the
     *  holder hands over to its standing retry / unavailable ladder. */
    @Volatile var onExhausted: (() -> Unit)? = null

    private val _statusText = MutableStateFlow<String?>(null)

    /** Live loading status for the player overlay ("Trying another stream...",
     *  "Reconnecting...", "Channel unavailable. Retrying..."), null otherwise. */
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** This channel's learned time-to-first-byte in ms, or null when nothing is
     *  known (or the learned value has decayed). Wired by the holder from the
     *  persisted learner; the channel identity arrives as a PARAMETER so a
     *  channel flip can never read the previous channel's value. */
    @Volatile var learnedFirstByteMs: ((channelId: String) -> Int?)? = null

    /** A clean, un-stepped tune produced its first byte after this long; the
     *  holder persists it. Only successes are learned. */
    @Volatile var onLearnFirstByte: ((channelId: String, ms: Int) -> Unit)? = null

    /** True only while [channelId] is the holder's active tune AND the player
     *  is not IDLE. Checked before every status read and change_stream POST so
     *  a stopped, unavailable, or abandoned channel is never polled (Streamer
     *  2026-11-01: status + change_stream 404 every 56 s with the player IDLE
     *  and the user back in the guide). Null = no gate wired (treated active). */
    @Volatile var isTuneActive: ((channelId: String) -> Boolean)? = null

    /** True while [channelId] is still the holder's tune and has not gone
     *  Channel Unavailable, regardless of player state. A walk started by a
     *  503 checks this instead of [isTuneActive]: the 503 itself leaves the
     *  player IDLE, which is not the tune going away (Apple parity 6c5b9c2:
     *  the deliberate stop read as "tile gone" and the walk returned without
     *  a log or a change_stream). Null = fall back to [isTuneActive]. */
    @Volatile var isTuneWanted: ((channelId: String) -> Boolean)? = null

    /** Channel whose Dispatcharr session answered change_stream 404 "Channel
     *  not found". The walk stays off for it until a byte arrives (a live
     *  session exists again) or a different channel is tuned, so an auto
     *  retry of a dead channel never re-posts change_stream. */
    private var goneChannelId: String? = null

    private var deadlineJob: Job? = null
    private var stepJob: Job? = null
    private var channelId: String? = null
    private var channelName: String = "?"
    private var firstByteSeen = false

    /**
     * Last moment ANY byte was seen on this pipeline (0 = none since arm).
     * Written from loader threads, so volatile and free of any coroutine work.
     * The deadline measures SILENCE from here: a slow but live feed can never
     * trigger the walk (Glitzbr 2026-09-15, an upstream that degraded to 0.68
     * of real time was walked away from onto worse backups).
     */
    @Volatile private var lastByteAtMs = 0L

    /** When the CURRENT deadline was armed. */
    private var armedAtMs = 0L

    /** When this tune first armed a deadline, so the learned value measures the
     *  whole tap-to-first-byte, not just the last step. */
    private var tuneStartedAtMs = 0L

    /** The budget the current deadline is running with, for the log line. */
    private var budgetMs = FIRST_TUNE_FIRST_BYTE_MS
    /** Streams already walked this tune; the walk never revisits one. */
    private val tried = mutableSetOf<Int>()
    private var activeStreamId: Int? = null
    private var steps = 0
    private var walkStartedAtMs = 0L
    /** The server's verbatim 503 reason for this tune, so the walk's own status
     *  lines can keep quoting it instead of inventing a cause. */
    private var serverReason: String? = null

    /**
     * A live stream has just been primed. [userInitiated] is true for a real
     * channel change (the holder's playUrl was given a channel id), which wipes
     * the walk; an internal re-prime keeps the tried set so a fresh pipeline on
     * streams already proved silent cannot loop.
     */
    fun onLiveTune(channelId: String?, channelName: String?, userInitiated: Boolean) {
        if (channelId == null) {
            disarm()
            _statusText.value = null
            return
        }
        if (channelId != this.channelId) goneChannelId = null
        if (userInitiated || channelId != this.channelId) resetWalk()
        this.channelId = channelId
        channelName?.takeIf { it.isNotBlank() }?.let { this.channelName = it }
        _statusText.value = null
        armDeadline(firstAttempt = true)
    }

    /**
     * ANY byte arrived on the live pipeline. Called from loader threads at most
     * a few times a second (PlaybackTracer throttles), so it does exactly one
     * volatile write and nothing else. Bytes NEVER get abandoned: the deadline
     * counts silence from the last byte, so a feed that is crawling at a
     * fraction of real time keeps the stream alive instead of starting a walk.
     */
    fun noteBytes() {
        lastByteAtMs = SystemClock.elapsedRealtime()
    }

    /** First byte on the wire: disarm, and say what recovered us if we stepped. */
    fun noteFirstByte() {
        lastByteAtMs = SystemClock.elapsedRealtime()
        scope.launch {
            if (firstByteSeen) return@launch
            firstByteSeen = true
            goneChannelId = null
            deadlineJob?.cancel()
            deadlineJob = null
            // Learn only clean successes on this channel's own stream: a time
            // measured after a change_stream walk is the backup's, not the
            // channel's normal lock time.
            val id = channelId
            if (steps == 0 && id != null && tuneStartedAtMs != 0L) {
                val ttfb = (SystemClock.elapsedRealtime() - tuneStartedAtMs).toInt()
                if (ttfb > 0) {
                    Log.i(TAG, "[FAILOVER] channel=$channelName firstByte in ${ttfb}ms; learning")
                    onLearnFirstByte?.invoke(id, ttfb)
                }
            }
            if (steps > 0) {
                val ms = if (walkStartedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - walkStartedAtMs
                Log.i(
                    TAG,
                    "[FAILOVER] channel=$channelName recovered on stream " +
                        "id=${activeStreamId ?: "unknown"} after ${ms}ms",
                )
            }
            _statusText.value = null
        }
    }

    /**
     * The server answered a live tune with a 503 it already explained (see
     * [Dispatcharr503]): "No available streams for this channel", "Channel
     * resources unavailable", or a specific upstream error_reason. The server has
     * already tried this channel's streams, so there is nothing to wait for:
     * start the SAME walk the first-byte deadline uses, right now.
     *
     * [reason] is the server's own text, shown verbatim so the user never sees a
     * guessed cause.
     */
    fun onServer503(reason: String) {
        scope.launch {
            val id = channelId ?: return@launch
            val h = hooks
            serverReason = reason
            // The deadline is moot: we have a definitive answer already.
            deadlineJob?.cancel()
            deadlineJob = null
            if (h == null || !h.canSwitch(id)) {
                Log.i(TAG, "[FAILOVER] 503 reason=\"$reason\" -> standing retry")
                _statusText.value = "Channel unavailable. Retrying..."
                onWalkOutcome?.invoke(true, WalkOutcome.NO_SWITCH)
                return@launch
            }
            if (goneChannelId == id) {
                Log.i(TAG, "[FAILOVER] walker cancelled: channel session gone")
                _statusText.value = "Channel unavailable. Retrying..."
                onWalkOutcome?.invoke(true, WalkOutcome.NO_SWITCH)
                return@launch
            }
            Log.i(TAG, "[FAILOVER] 503 reason=\"$reason\" -> next stream")
            showStatus("Server: $reason. Trying another stream...")
            if (stepJob?.isActive == true) {
                Log.i(TAG, "[FAILOVER] channel=$channelName 503 during a walk step; the running step continues")
                return@launch
            }
            if (walkStartedAtMs == 0L) walkStartedAtMs = SystemClock.elapsedRealtime()
            stepJob = scope.launch { step(h, id, from503 = true) }
        }
    }

    /** Publish a status line the holder owns (for example the "Reconnecting..."
     *  shown while a "Channel is stopping" 503 is waited out). */
    fun publishServerStatus(text: String?) {
        scope.launch { _statusText.value = text }
    }

    /** Publish "Server: <reason>. <tail>" when the server explained itself,
     *  else [tail] alone (Apple showStatus(serverReason:tail:)); a line that
     *  quotes the server is logged as [STATUS]. Main thread. */
    fun showServerStatus(serverReason: String?, tail: String) {
        showStatus(serverStatusText(serverReason, tail))
    }

    /** True when a tune of [channelId] can walk member streams (Dispatcharr
     *  Direct Connect admin, integer channel pk). Such a tune keeps its ingest
     *  open across the whole walk, see [WALK_INGEST_TIMEOUT_MS]. */
    fun canWalk(channelId: String?): Boolean {
        val id = channelId ?: return false
        return runCatching { hooks?.canSwitch(id) }.getOrNull() == true
    }

    /** Teardown / pipeline stop: cancel the deadline, KEEP the tried set. */
    fun disarm() {
        deadlineJob?.cancel()
        deadlineJob = null
        firstByteSeen = false
    }

    /**
     * The player stopped for this channel (unavailable, user left, tuned
     * elsewhere, backgrounded): cancel the deadline AND any in-flight walk
     * step, so no status read or change_stream outlives the playback. The
     * tried set is kept; an internal re-prime of the same tune re-arms through
     * [onLiveTune].
     */
    fun halt(reason: String) {
        val walking = stepJob?.isActive == true || deadlineJob?.isActive == true
        disarm()
        stepJob?.cancel()
        stepJob = null
        if (walking) Log.i(TAG, "[FAILOVER] walker cancelled: $reason")
    }

    /** Channel change or full teardown: forget everything about the walk. */
    fun resetWalk() {
        disarm()
        stepJob?.cancel()
        stepJob = null
        tried.clear()
        activeStreamId = null
        steps = 0
        walkStartedAtMs = 0L
        serverReason = null
        _statusText.value = null
    }

    /**
     * Arm the silent-start deadline.
     *
     * [firstAttempt] is the first ingest of this tune: an over-the-air tuner has
     * to LOCK before it can send anything, so it gets the long budget (and a
     * longer one still on a channel this device has learned to be slow). Every
     * later attempt is a stream the server has already swapped in behind the
     * same connection, so it gets the short, responsive budget.
     *
     * The wait measures SILENCE, not wall clock: each poll restarts the budget
     * from the last byte seen.
     */
    private fun armDeadline(firstAttempt: Boolean) {
        firstByteSeen = false
        lastByteAtMs = 0L
        armedAtMs = SystemClock.elapsedRealtime()
        if (firstAttempt) tuneStartedAtMs = armedAtMs
        val id = channelId
        val learned = id?.let { cid -> runCatching { learnedFirstByteMs?.invoke(cid) }.getOrNull() }
        budgetMs = if (!firstAttempt) {
            STEP_FIRST_BYTE_MS
        } else {
            val fromLearned = learned?.let { (it * LEARNED_HEADROOM_NUM / LEARNED_HEADROOM_DEN).toLong() } ?: 0L
            maxOf(FIRST_TUNE_FIRST_BYTE_MS, fromLearned).coerceAtMost(FIRST_BYTE_BUDGET_MAX_MS)
        }
        Log.i(
            TAG,
            "[FAILOVER] channel=$channelName silent-start budget ${budgetMs}ms " +
                "(learned ${learned?.let { "${it}ms" } ?: "none"}, " +
                "${if (firstAttempt) "first attempt" else "walk step"})",
        )
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            while (true) {
                delay(SILENCE_POLL_MS)
                if (firstByteSeen) return@launch
                val now = SystemClock.elapsedRealtime()
                val since = now - maxOf(armedAtMs, lastByteAtMs)
                if (since >= budgetMs) {
                    handleNoFirstByte(since)
                    return@launch
                }
            }
        }
    }

    private fun handleNoFirstByte(silentMs: Long) {
        val id = channelId ?: return
        val h = hooks
        if (h == null || !h.canSwitch(id)) {
            // Nothing to fail over TO: say the player is working on it and leave
            // the existing retry ladder exactly as it is.
            _statusText.value = "Reconnecting..."
            Log.i(
                TAG,
                "[FAILOVER] channel=$channelName no bytes for ${silentMs}ms " +
                    "(budget ${budgetMs}ms); " +
                    "no switchable streams, staying on the retry path",
            )
            return
        }
        if (stepJob?.isActive == true) {
            Log.i(TAG, "[FAILOVER] channel=$channelName deadline hit during a walk step; the running step continues")
            return
        }
        blockReason(id)?.let {
            Log.i(TAG, "[FAILOVER] walker cancelled: $it")
            return
        }
        if (walkStartedAtMs == 0L) walkStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "[FAILOVER] channel=$channelName no bytes for ${silentMs}ms " +
                "(budget ${budgetMs}ms); walking to the next stream",
        )
        stepJob = scope.launch { step(h, id) }
    }

    /**
     * One step of the walk: resolve the list (cached per channel for the
     * process), mark where we are, POST change_stream for the next untried
     * entry, re-arm the deadline.
     */
    private suspend fun step(h: Hooks, id: String, from503: Boolean = false) {
        // Every early exit is logged; on the 503 entry point the holder's
        // generic retry ladder keeps running behind the walk, so an exit here
        // falls to that standing retry instead of leaving the tile on
        // "Trying another stream..." with nothing scheduled.
        val retryPath = if (from503) "the standing retry" else "the retry path"
        val uuid = id.substringAfterLast(':')
        val cached = streamCache[id]
        val ids = cached ?: runCatching { h.listStreamIds(id) }.getOrNull()
            ?.also { if (it.isNotEmpty()) streamCache[id] = it }
        if (ids.isNullOrEmpty()) {
            _statusText.value = "Reconnecting..."
            Log.i(TAG, "[FAILOVER] channel=$channelName stream list unavailable; staying on $retryPath")
            if (from503) onWalkOutcome?.invoke(true, WalkOutcome.NO_SWITCH)
            return
        }
        if (!walkStillWanted(id, from503, "stream list")) return
        if (ids.size < 2) {
            _statusText.value = "Reconnecting..."
            Log.i(TAG, "[FAILOVER] channel=$channelName single stream; staying on $retryPath")
            if (from503) onWalkOutcome?.invoke(true, WalkOutcome.NO_SWITCH)
            return
        }
        // Seed "where are we" once. /status is only trustworthy before any
        // in-session switch, which is exactly where the walk reads it.
        if (activeStreamId == null) {
            activeStreamId = withTimeoutOrNull(STATUS_READ_CAP_MS) {
                runCatching { h.currentStreamId(uuid) }.getOrNull()
            } ?: ids.first()
        }
        activeStreamId?.let { tried.add(it) }
        val startIndex = activeStreamId?.let { ids.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        var target: Int? = null
        for (offset in 1..ids.size) {
            val candidate = ids[(startIndex + offset) % ids.size]
            if (candidate !in tried) { target = candidate; break }
        }
        if (target == null) {
            Log.w(TAG, "[FAILOVER] channel=$channelName exhausted ${ids.size} streams")
            _statusText.value = "Channel unavailable. Retrying..."
            onExhausted?.invoke()
            onWalkOutcome?.invoke(from503, WalkOutcome.EXHAUSTED)
            return
        }
        // The status read can take seconds: re-check before the POST.
        if (!walkStillWanted(id, from503, "status read")) return
        steps += 1
        tried.add(target)
        val step = steps
        try {
            h.changeStream(uuid, target)
        } catch (t: Throwable) {
            Log.w(TAG, "[FAILOVER] channel=$channelName change_stream to id=$target failed: ${t.message}")
            if (isChannelGone(t)) {
                // The server has no session for this channel any more: nothing
                // to switch. Dispatcharr starts fresh on the next tune.
                goneChannelId = id
                deadlineJob?.cancel()
                deadlineJob = null
                Log.w(TAG, "[FAILOVER] channel session gone (404); walker stopped")
                _statusText.value = "Channel unavailable. Retrying..."
                if (from503) onWalkOutcome?.invoke(true, WalkOutcome.NO_SWITCH)
                return
            }
            Log.i(TAG, "[FAILOVER] channel=$channelName change_stream refused; staying on $retryPath")
            _statusText.value = "Reconnecting..."
            if (from503) onWalkOutcome?.invoke(true, WalkOutcome.NO_SWITCH)
            return
        }
        if (!walkStillWanted(id, from503, "change_stream")) return
        activeStreamId = target
        // Quote the server when it told us why we are moving; otherwise this was
        // our own first-byte deadline and there is no server text to show.
        showStatus(
            serverReason
                ?.let { "Server: $it. Trying another stream..." }
                ?: "Trying another stream...",
        )
        Log.i(
            TAG,
            "[FAILOVER] channel=$channelName stream $step/${ids.size} id=$target " +
                "reason=${serverReason ?: "no bytes within the ${budgetMs}ms silent-start budget"}",
        )
        armDeadline(firstAttempt = false)
        if (from503) onWalkOutcome?.invoke(true, WalkOutcome.SWITCHED)
    }

    /** Sets the status line. A line that quotes the server is logged verbatim
     *  as [STATUS], so a report of on-screen text can be matched to the log. */
    private fun showStatus(text: String) {
        if (text.startsWith("Server: ") && _statusText.value != text) {
            Log.i(TAG, "[STATUS] $surface shows \"$text\" channel=$channelName")
        }
        _statusText.value = text
    }

    /** The walk is still this tune's business. Every early exit is logged. */
    private fun walkStillWanted(id: String, from503: Boolean, at: String): Boolean {
        val why = when {
            firstByteSeen -> "bytes arrived"
            else -> blockReason(id, from503)
        } ?: return true
        Log.i(TAG, "[FAILOVER] channel=$channelName walk ended at $at: $why")
        return false
    }

    private fun blockReason(id: String, from503: Boolean = false): String? = walkBlockReason(
        tuneActive = runCatching {
            if (from503) (isTuneWanted ?: isTuneActive)?.invoke(id) else isTuneActive?.invoke(id)
        }.getOrNull() ?: true,
        channelMatches = channelId == id,
        sessionGone = goneChannelId == id,
    )

    companion object {
        private const val TAG = "AerioTrace"

        /** Apple serverStatus(_:_:): the server's sentence verbatim apart
         *  from its first letter and trailing periods, then [tail]. */
        fun serverStatusText(serverReason: String?, tail: String): String {
            val text = serverReason?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return tail
            return "Server: ${text[0].uppercaseChar()}${text.substring(1)}. $tail"
        }

        /** Pure stop conditions for a walk step; null = the step may proceed. */
        internal fun walkBlockReason(
            tuneActive: Boolean,
            channelMatches: Boolean,
            sessionGone: Boolean,
        ): String? = when {
            !channelMatches -> "tuned elsewhere"
            !tuneActive -> "player stopped"
            sessionGone -> "channel session gone"
            else -> null
        }

        /** change_stream 404 ("Channel not found"): the channel's proxy session
         *  no longer exists on the server. */
        internal fun isChannelGone(t: Throwable): Boolean =
            (t as? com.aeriotv.android.core.network.DispatcharrHttpFailure)?.status == 404 ||
                t.message?.startsWith("HTTP 404") == true

        /**
         * How long a live ingest may stay connected-and-SILENT on the FIRST
         * attempt of a tune before the walk starts. Separate from the holder's
         * HTTP read timeout.
         *
         * Was 12 s. Raised 2026-09-15 (Glitzbr, over-the-air HDHomeRun tuners
         * through Dispatcharr): a tuner has to lock before a single byte
         * exists, and 12 s walked a working channel onto much worse backups.
         */
        const val FIRST_TUNE_FIRST_BYTE_MS = 28_000L

        /**
         * Silence budget for every ingest AFTER the first of a tune. The server
         * has already swapped a stream in behind the same connection, so there
         * is no tuner lock left to wait for and failover stays responsive.
         */
        const val STEP_FIRST_BYTE_MS = 12_000L

        /**
         * Ingest read timeout and no-data ceiling for a tune that can walk
         * (Apple parity 5267c55): the first-attempt budget plus eight walk
         * steps plus 10 s, 28 + 8 x 12 + 10 = 134 s. The walk keeps the same
         * connection open while the server swaps the upstream behind it, so a
         * shorter timeout ended that connection mid-step (Apple TV 2026-11-04:
         * opened 11:15:02, timed out 11:15:32, 2 s after "stream 2/6") and the
         * later streams were never tried. The silent-start deadline owns the
         * silence; this only bounds a connection nobody is walking any more.
         */
        const val WALK_INGEST_TIMEOUT_MS = FIRST_TUNE_FIRST_BYTE_MS + 8 * STEP_FIRST_BYTE_MS + 10_000L

        /** Hard ceiling on a learned-stretched budget. */
        const val FIRST_BYTE_BUDGET_MAX_MS = 45_000L

        /** A channel learned to start slowly gets 1.5x its learned
         *  time-to-first-byte (never less than the base budget). */
        private const val LEARNED_HEADROOM_NUM = 3
        private const val LEARNED_HEADROOM_DEN = 2

        /** How often the deadline re-checks silence. */
        private const val SILENCE_POLL_MS = 500L
        private const val STATUS_READ_CAP_MS = 3_000L

        /** Process-lifetime cache of a channel's member-stream pks (priority
         *  order). An empty answer is never cached. */
        private val streamCache = ConcurrentHashMap<String, List<Int>>()
    }
}
