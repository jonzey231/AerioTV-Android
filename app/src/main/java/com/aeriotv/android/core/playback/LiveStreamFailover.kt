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
 * live no-data ceiling is 50 s. So the client arms its own 12 s first-byte
 * deadline per live ingest (deliberately SEPARATE from any HTTP timeout) and, on
 * a Dispatcharr Direct Connect admin account, walks the channel's member streams
 * with change_stream instead of waiting.
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
) {

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

    private var deadlineJob: Job? = null
    private var stepJob: Job? = null
    private var channelId: String? = null
    private var channelName: String = "?"
    private var firstByteSeen = false
    /** Streams already walked this tune; the walk never revisits one. */
    private val tried = mutableSetOf<Int>()
    private var activeStreamId: Int? = null
    private var steps = 0
    private var walkStartedAtMs = 0L

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
        if (userInitiated || channelId != this.channelId) resetWalk()
        this.channelId = channelId
        channelName?.takeIf { it.isNotBlank() }?.let { this.channelName = it }
        _statusText.value = null
        armDeadline()
    }

    /** First byte on the wire: disarm, and say what recovered us if we stepped. */
    fun noteFirstByte() {
        scope.launch {
            if (firstByteSeen) return@launch
            firstByteSeen = true
            deadlineJob?.cancel()
            deadlineJob = null
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

    /** Teardown / pipeline stop: cancel the deadline, KEEP the tried set. */
    fun disarm() {
        deadlineJob?.cancel()
        deadlineJob = null
        firstByteSeen = false
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
        _statusText.value = null
    }

    private fun armDeadline() {
        firstByteSeen = false
        deadlineJob?.cancel()
        deadlineJob = scope.launch {
            delay(FIRST_BYTE_DEADLINE_MS)
            if (!firstByteSeen) handleNoFirstByte()
        }
    }

    private fun handleNoFirstByte() {
        val id = channelId ?: return
        val h = hooks
        if (h == null || !h.canSwitch(id)) {
            // Nothing to fail over TO: say the player is working on it and leave
            // the existing retry ladder exactly as it is.
            _statusText.value = "Reconnecting..."
            Log.i(
                TAG,
                "[FAILOVER] channel=$channelName no first byte in ${FIRST_BYTE_DEADLINE_MS / 1000}s; " +
                    "no switchable streams, staying on the retry path",
            )
            return
        }
        if (stepJob?.isActive == true) return
        if (walkStartedAtMs == 0L) walkStartedAtMs = SystemClock.elapsedRealtime()
        stepJob = scope.launch { step(h, id) }
    }

    /**
     * One step of the walk: resolve the list (cached per channel for the
     * process), mark where we are, POST change_stream for the next untried
     * entry, re-arm the deadline.
     */
    private suspend fun step(h: Hooks, id: String) {
        val uuid = id.substringAfterLast(':')
        val cached = streamCache[id]
        val ids = cached ?: runCatching { h.listStreamIds(id) }.getOrNull()
            ?.also { if (it.isNotEmpty()) streamCache[id] = it }
        if (ids.isNullOrEmpty()) {
            _statusText.value = "Reconnecting..."
            Log.i(TAG, "[FAILOVER] channel=$channelName stream list unavailable; staying on the retry path")
            return
        }
        if (firstByteSeen || channelId != id) return
        if (ids.size < 2) {
            _statusText.value = "Reconnecting..."
            Log.i(TAG, "[FAILOVER] channel=$channelName single stream; staying on the retry path")
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
            return
        }
        steps += 1
        tried.add(target)
        val step = steps
        try {
            h.changeStream(uuid, target)
        } catch (t: Throwable) {
            Log.w(TAG, "[FAILOVER] channel=$channelName change_stream to id=$target failed: ${t.message}")
            _statusText.value = "Reconnecting..."
            return
        }
        if (firstByteSeen || channelId != id) return
        activeStreamId = target
        _statusText.value = "Trying another stream..."
        Log.i(
            TAG,
            "[FAILOVER] channel=$channelName stream $step/${ids.size} id=$target " +
                "reason=no first byte in ${FIRST_BYTE_DEADLINE_MS / 1000}s",
        )
        armDeadline()
    }

    companion object {
        private const val TAG = "AerioTrace"

        /** Seconds a live ingest may stay connected-but-silent before the walk
         *  starts. Separate from the holder's HTTP read timeout. */
        const val FIRST_BYTE_DEADLINE_MS = 12_000L
        private const val STATUS_READ_CAP_MS = 3_000L

        /** Process-lifetime cache of a channel's member-stream pks (priority
         *  order). An empty answer is never cached. */
        private val streamCache = ConcurrentHashMap<String, List<Int>>()
    }
}
