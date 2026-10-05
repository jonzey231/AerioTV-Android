package com.aeriotv.android.core.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.aeriotv.android.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Media3 ExoPlayer holder mirroring [MPVPlayerHolder]'s lifetime contract.
 * Hoists ONE [ExoPlayer] instance out of any single composable lifecycle so
 * the underlying codec + audio renderer survives PlayerScreen <-> mini
 * transitions and channel switches. Without this, every nav transition
 * tears the player down and the next open costs a fresh MediaCodec
 * allocation + DataSource warm-up.
 *
 * Pattern (intentionally identical to MPVPlayerHolder so PlayerScreen
 * doesn't need to know which player is mounted):
 *   - PersistentExoWindow.factory calls [acquireOrCreate] on first
 *     composition. Subsequent calls (after back-out + resume) return
 *     the same ExoPlayer reference; the caller just rebinds the
 *     PlayerView's surface to it.
 *   - AndroidView's onRelease calls [detach] instead of release(),
 *     leaving ExoPlayer alive while surface is unparented.
 *   - X-close goes through [destroy] which releases the player.
 *
 * Live TV scaffold for the Media3 migration. VOD, MediaSession, and
 * multiview are subsequent tasks (#62, #63, #64).
 *
 * Threading: all entry points expect main thread (ExoPlayer's
 * `Looper.getMainLooper()` requirement).
 */
@OptIn(UnstableApi::class)
@Singleton
class AerioExoPlayerHolder @Inject constructor(
    private val timeshift: dagger.Lazy<com.aeriotv.android.core.timeshift.TimeshiftController>,
    private val appPreferences: com.aeriotv.android.core.preferences.AppPreferences,
    private val firstByteLearner: com.aeriotv.android.core.preferences.LiveFirstByteLearner,
) {

    var player: ExoPlayer? = null
        private set

    /**
     * Always-on release playback tracer (tag `AerioTrace`): the Android
     * counterpart of the Apple player's [TUNE] / [STALL] / feed lines. Purely
     * observational, never changes playback.
     */
    val tracer = PlaybackTracer()

    /**
     * Client-driven no-first-byte stream failover for live tunes (Apple parity
     * commit dc52f2a). Owned here, never driven from a composable; PlayerScreen
     * only supplies [LiveStreamFailover.Hooks] for the Dispatcharr calls.
     */
    val liveFailover = LiveStreamFailover()

    /** Live loading status published by the failover walk ("Trying another
     *  stream...", "Reconnecting...", "Channel unavailable. Retrying..."). */
    val liveStatusText: StateFlow<String?> get() = liveFailover.statusText

    /**
     * Stamp the REAL key event that starts a live channel change so
     * press->firstFrame is measured end to end (D-pad zap, number entry,
     * channel-list / recents pick, the guide's select press).
     */
    fun markTunePress(channelName: String?) = tracer.markPress(channelName)

    /**
     * Observable mirror of [player] so the persistent PlayerView can REBIND
     * when the instance is recreated. The view's AndroidView factory runs
     * once per process and bound the original instance; after a destroy()
     * (X-close) plus a re-create (next playUrl, the media service, or a
     * passthrough-pref rebuild) the view kept pointing at the RELEASED
     * player, so Media3 configured the codec against a placeholder surface:
     * audio played, the screen stayed black, and only an app restart
     * recovered (GitHub report, Pixel 9 Pro XL log).
     */
    private val _playerInstance = MutableStateFlow<ExoPlayer?>(null)
    val playerInstance: StateFlow<ExoPlayer?> = _playerInstance.asStateFlow()

    /**
     * Short technical label for the pipeline the CURRENT source runs on, for
     * the player chrome's bottom-left readout (Apple "AVPlayer · Remux TS"
     * parity, Logan 2026-10-04): "ExoPlayer · TS" (raw MPEG-TS through the
     * forced TsExtractor), "ExoPlayer · HLS", "ExoPlayer · DASH",
     * "ExoPlayer · MP4" / "MKV" (progressive by container), "ExoPlayer ·
     * Rewind TS" (Live Rewind buffer) and "ExoPlayer · Catch-up TS". Set
     * where the media source is built, so it always names what is actually
     * loaded; null after stop().
     */
    private val _pipelineLabel = MutableStateFlow<String?>(null)
    val pipelineLabel: StateFlow<String?> = _pipelineLabel.asStateFlow()

    private fun pipelineLabelFor(url: String, drm: Boolean): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val kind = when {
            isRawTsUrl(url) -> "TS"
            ext == "m3u8" || ForceHls.isForcedHlsUrl(url) -> "HLS"
            ext == "mpd" -> "DASH"
            ext in setOf("mp4", "m4v", "mov") -> "MP4"
            ext == "mkv" -> "MKV"
            ext == "webm" -> "WebM"
            ext.isNotEmpty() && ext.length <= 4 -> ext.uppercase()
            else -> "Auto"
        }
        return "ExoPlayer · $kind" + if (drm) " · DRM" else ""
    }

    /** Application context captured at first acquire so playUrl can
     *  self-heal when called before/after the player exists. */
    private var appContext: android.content.Context? = null

    /** Passthrough state the current player was built with; a pref flip
     *  forces a rebuild because sink capabilities are fixed at build. */
    private var builtWithPassthrough: Boolean? = null
    /** Buffer floor (ms) the current player was built with; a pref flip forces
     *  a rebuild because the LoadControl is fixed at build time. */
    private var builtWithBufferFloorMs: Int? = null
    /** Start gate (bufferForPlaybackMs) the current player's load control is
     *  set to. A changed learned hold-back switches [gateLoadControl] in place
     *  (no rebuild), which [playUrl] does BEFORE priming so it never lands
     *  mid-playback. */
    private var builtWithStartGateMs: Int? = null
    /** Load control of the current player; switches the start gate in place. */
    private var gateLoadControl: GateSwitchingLoadControl? = null

    /** min = gate + 4 s keeps 4 s of real cushion above a raised start gate. */
    private fun minBufferFor(bufferFloorMs: Int, gateMs: Int): Int =
        maxOf(4_000, bufferFloorMs, gateMs + 4_000)

    /** At least LIVE_MAX_BUFFER_FLOOR_MS so the post-stall resume gate is reachable. */
    private fun liveMaxBufferFor(bufferFloorMs: Int, gateMs: Int): Int =
        maxOf(minBufferFor(bufferFloorMs, gateMs) * 2, LIVE_MAX_BUFFER_FLOOR_MS)

    /** Developer Force HLS: the load control is keyed by gate PLUS an HLS
     *  flag so a forced HLS tune gets its own delegate (bigger min buffer and
     *  rebuffer resume) through the same gate-switch path. */
    private fun gateKeyFor(gateMs: Int, hls: Boolean, rebufferMs: Int = 0): Int =
        // Forced HLS: the rebuffer resume gate rides in the key (100 ms units,
        // at least 1) so a changed live offset target gets its own delegate.
        if (hls) gateMs + HLS_GATE_KEY_FLAG * (rebufferMs / 100).coerceAtLeast(1) else gateMs
    private fun isHlsGateKey(key: Int): Boolean = key >= HLS_GATE_KEY_FLAG
    private fun gateOfKey(key: Int): Int = key % HLS_GATE_KEY_FLAG
    /** Forced HLS resume-after-rebuffer gate carried by [key]. */
    private fun hlsRebufferOfKey(key: Int): Int = (key / HLS_GATE_KEY_FLAG) * 100
    private fun minBufferForKey(bufferFloorMs: Int, key: Int): Int {
        val base = minBufferFor(bufferFloorMs, gateOfKey(key))
        // DefaultLoadControl requires minBufferMs >= the rebuffer gate.
        return if (isHlsGateKey(key)) maxOf(base, FORCE_HLS_MIN_BUFFER_MS, hlsRebufferOfKey(key)) else base
    }
    private fun maxBufferForKey(bufferFloorMs: Int, key: Int): Int {
        val base = liveMaxBufferFor(bufferFloorMs, gateOfKey(key))
        return if (isHlsGateKey(key)) maxOf(base, minBufferForKey(bufferFloorMs, key) * 2) else base
    }
    /** Start gate kind the next [acquireOrCreate] builds with: forced HLS or not. */
    @Volatile private var desiredForcedHls: Boolean = false
    /** True when the most recent live tune was a forced HLS session. Survives
     *  stop() so the close path can decide Keep Live after the X. */
    @Volatile var lastTuneWasForcedHls: Boolean = false
        private set
    /** Start gate the next [acquireOrCreate] must build with, stamped by
     *  [playUrl] from the learned per-channel hold-back. */
    @Volatile private var desiredStartGateMs: Int = LIVE_START_GATE_DEFAULT_MS
    /** In-memory mirror of AppPreferences.liveStartBufferMs so [playUrl] can
     *  read the learned hold-back without blocking the channel-tap path. */
    @Volatile private var cachedLiveStartBuffers:
        Map<String, com.aeriotv.android.core.preferences.LearnedStartBuffer> = emptyMap()
    /** iOS #37 kill-switch, cached at build/tune time. When false the stall +
     *  black-screen reload nets no-op; the cold-start no-data net stays armed. */
    @Volatile private var watchdogReloadEnabled: Boolean = true
    // GH #8: some devices (Chromecast w/ Google TV report) output NO audio
    // on the forced-PCM no-context sink the lip-sync fix uses when
    // passthrough is off. When the sink raises an AudioTrack init/write
    // error we rebuild THIS PROCESS with the stock context sink so audio
    // always comes out; the user's passthrough pref is untouched. Sticky once
    // tripped: re-trying the forced-PCM sink on every channel switch would
    // just re-fail and glitch audio on the affected device.
    private var audioSinkFallback = false

    /** Most-recent channel id played, so a resuming PlayerScreen knows
     *  whether to skip the setMediaItem re-init. */
    var currentChannelId: String? = null

    /** The URL the player is currently primed on (last [playUrl]); read by the
     *  LAN/WAN re-tune effect to skip a flip that resolves to the same base. */
    val currentPlayUrl: String? get() = lastPlayUrl

    /** Optional failover hook: on a terminal player error the holder asks this
     *  to re-probe LAN/WAN and return a fresh URL to reload instead of replaying
     *  the (possibly dead-host) lastPlayUrl. Set by PlayerScreen on mount; null
     *  elsewhere (Auto / background). iOS analog: PlayerSession.failoverRetryCurrent.
     *
     *  The channel id is passed AS A PARAMETER at call time, from the holder's own
     *  [currentChannelIdForRebuild]. Before 2026-09-11 the hook closed over a
     *  composable value captured on mount, so a flip to another channel left the
     *  lambda pointing at the PREVIOUS one: session2.txt 19:56:49.064 re-primed a
     *  UHD failure onto ESPN HD's URL and played the wrong channel for a minute. */
    @Volatile var onTerminalErrorRebuildUrl: (suspend (channelId: String) -> String?)? = null

    /** Live channel id the rebuild hook must be asked about, stamped by [playUrl]
     *  from the CALLER's channel id so it is always the channel actually primed
     *  (never a value captured earlier by a composable). */
    @Volatile var currentChannelIdForRebuild: String? = null

    /** Currently-applied custom HTTP headers, replayed onto the
     *  DataSource.Factory each time we build a MediaSource. Dispatcharr
     *  API-key auth lives here. */
    var httpHeaders: Map<String, String> = emptyMap()

    // ---- live stall watchdog ----
    // Port of the iOS MPVPlayerView reload-watchdog (commits 331f0bf / a6cf4b4
    // / 0c83124 / 53752ad). A live stream can wedge mid-play (server/proxy
    // hiccup, audio-device reconfig) with the network healthy but no frames
    // advancing. We poll currentPosition; if it stops advancing for too long
    // while we expect playback, re-prime the SAME url -- the Media3 analog of
    // mpv `loadfile <url> replace`.
    // Eagerly-cached DataStore prefs for the hot player path.
    // Collecting them once at singleton creation eliminates every runBlocking
    // on Main that would otherwise block the channel-tap and player-build paths.
    private val prefScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var cachedAudioPassthrough: Boolean = false
    @Volatile private var cachedBufferFloorMs: Int = com.aeriotv.android.feature.settings.bufferMillisFor("default")

    init {
        prefScope.launch {
            appPreferences.audioPassthroughEnabled.collect { cachedAudioPassthrough = it }
        }
        prefScope.launch {
            appPreferences.streamBufferSize.collect { size ->
                cachedBufferFloorMs = com.aeriotv.android.feature.settings.bufferMillisFor(size)
            }
        }
        prefScope.launch {
            appPreferences.autoRecoverFrozenStreams.collect { watchdogReloadEnabled = it }
        }
        prefScope.launch {
            appPreferences.liveStartBufferMs.collect { cachedLiveStartBuffers = it }
        }
        // Developer Force HLS: mirrored into ForceHls so the next tune (main
        // player and Multiview tiles) picks a flip up with no restart.
        prefScope.launch {
            appPreferences.developerForceHls.collect {
                ForceHls.enabled = it
                Log.i(ForceHls.TAG, "[FORCE-HLS] developer switch ${if (it) "ON" else "OFF"}")
            }
        }
        // Learned live start buffer: the tracer reports the feed shape at every
        // stall, this decides whether the feed was bursty-but-real-time.
        tracer.onStall = { snapshot -> learnStartBuffer(snapshot) }
        // No-first-byte failover: the tracer already knows when the first byte
        // lands, so the deadline is cancelled from there rather than by a second
        // counter.
        tracer.onFirstByte = { liveFailover.noteFirstByte() }
        // Bytes still arriving, however slowly: push the silent-start deadline
        // out instead of walking off a working-but-slow upstream (Glitzbr
        // 2026-09-15, an OTA HDHomeRun feed that degraded to 0.68 of real time).
        tracer.onByteActivity = { liveFailover.noteBytes() }
        // Per-channel learned time-to-first-byte, persisted exactly like the
        // learned hold-back: an OTA tuner that always takes 20 s to lock earns a
        // longer silent-start budget on later tunes.
        prefScope.launch { cachedFirstByteMs = firstByteLearner.allOnce() }
        liveFailover.learnedFirstByteMs = { id -> learnedFirstByteFor(id) }
        liveFailover.onLearnFirstByte = { id, ms ->
            prefScope.launch { cachedFirstByteMs = firstByteLearner.record(id, ms) }
        }
        // Every member stream answered and none delivered: hand over to the
        // standing Task #150 retry / unavailable ladder.
        liveFailover.onExhausted = { markStreamUnavailable() }
    }

    /** Learned time-to-first-byte per channel, mirrored in memory so the
     *  failover can read it without blocking the tune path. */
    @Volatile
    private var cachedFirstByteMs:
        Map<String, com.aeriotv.android.core.preferences.LearnedFirstByte> = emptyMap()

    /** This channel's learned time-to-first-byte, or null when unknown or
     *  decayed past the learner's TTL (a one-off slow start must not stretch
     *  the budget forever). */
    private fun learnedFirstByteFor(channelId: String): Int? {
        val entry = cachedFirstByteMs[channelId] ?: return null
        val age = System.currentTimeMillis() - entry.learnedAtMs
        if (age > com.aeriotv.android.core.preferences.LiveFirstByteLearner.LEARNED_TTL_MS) {
            return null
        }
        return entry.ms.takeIf { it > 0 }
    }

    /**
     * Android analog of the Apple per-channel learned hold-back (Apple commit
     * 8679aa8). A stall on a feed that is still arriving at real time over the
     * trailing 30 s means the bytes came in BURSTS and one gap outran the start
     * cushion (Google TV Streamer, Sky Sports Main Event UHD through a
     * Dispatcharr progressive TS: 6.9-7.5 s gaps every ~40 s at a steady
     * 10-15 Mbps average, 3 stalls in 3 min). Raise THIS channel's start gate
     * so the NEXT tune begins with enough buffered media to ride the gap out.
     * A feed that is gaining media time slower than the wall clock is simply
     * starved upstream, which a deeper start buffer cannot fix, so that case is
     * only logged.
     *
     * Never touches the running playback.
     */
    /** Developer Force HLS: a stall raises the NEXT tune's live offset target
     *  for this channel by the measured gap (capped, same 30 minute TTL as the
     *  TS hold-back). Stored in the start-buffer map under a distinct key. */
    private fun learnHlsLiveOffset(snapshot: PlaybackTracer.FeedStallSnapshot) {
        val channelId = currentChannelIdForRebuild ?: return
        learnHlsLiveOffsetFor(channelId, snapshot.worstGapMs)
    }

    /** Shared with Multiview tiles via [ForceHls.learnLiveOffset]. */
    private fun learnHlsLiveOffsetFor(channelId: String, worstGapMs: Long) {
        val key = channelId + FORCE_HLS_OFFSET_KEY_SUFFIX
        val now = System.currentTimeMillis()
        val prior = cachedLiveStartBuffers[key]
            ?.takeIf { now - it.learnedAtMs <= HOLDBACK_LEARNED_TTL_MS }?.ms ?: 0
        val base = maxOf(prior, FORCE_HLS_TARGET_OFFSET_MS)
        val next = (base + worstGapMs.toInt())
            .coerceAtMost(FORCE_HLS_LEARNED_OFFSET_MAX_MS)
            .coerceAtLeast(prior)
        Log.i(ForceHls.TAG, "[FORCE-HLS] learned live offset $next ms for ch=$channelId (gap $worstGapMs ms)")
        cachedLiveStartBuffers = cachedLiveStartBuffers +
            (key to com.aeriotv.android.core.preferences.LearnedStartBuffer(next, now))
        prefScope.launch {
            cachedLiveStartBuffers = appPreferences.setLiveStartBufferMs(key, next, now)
        }
    }

    /** Live offset target for a forced HLS tune of [channelId]. */
    private fun hlsLiveOffsetFor(channelId: String?): Int {
        val entry = channelId?.let { cachedLiveStartBuffers[it + FORCE_HLS_OFFSET_KEY_SUFFIX] }
            ?: return FORCE_HLS_TARGET_OFFSET_MS
        if (System.currentTimeMillis() - entry.learnedAtMs > HOLDBACK_LEARNED_TTL_MS) return FORCE_HLS_TARGET_OFFSET_MS
        restampLearnedOnUse(channelId + FORCE_HLS_OFFSET_KEY_SUFFIX, entry)
        return maxOf(FORCE_HLS_TARGET_OFFSET_MS, entry.ms)
    }

    /** A tune that applies a learned value rolls its 30 minute TTL forward,
     *  so expiry counts from the last tune that used it (never forever: an
     *  unused value still lapses 30 minutes after its last use). */
    private val lastRestampAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun restampLearnedOnUse(
        key: String,
        entry: com.aeriotv.android.core.preferences.LearnedStartBuffer,
    ) {
        val now = System.currentTimeMillis()
        // 20 s debounce (Apple parity): one tune can read the value more than
        // once (tune path plus rebuffer gate), so re-stamp and log only once.
        val last = lastRestampAtMs[key]
        if (last != null && now - last < RESTAMP_DEBOUNCE_MS) return
        lastRestampAtMs[key] = now
        Log.i(TAG, "[HOLDBACK] learned value re-stamped on use")
        cachedLiveStartBuffers = cachedLiveStartBuffers +
            (key to com.aeriotv.android.core.preferences.LearnedStartBuffer(entry.ms, now))
        prefScope.launch {
            cachedLiveStartBuffers = appPreferences.setLiveStartBufferMs(key, entry.ms, now)
        }
    }
    init {
        // Multiview tiles read the same per-channel target/learned offset.
        ForceHls.liveOffsetLookup = { id -> hlsLiveOffsetFor(id) }
        ForceHls.learnLiveOffset = { id, gapMs -> learnHlsLiveOffsetFor(id, gapMs) }
    }

    /** Offset target for the in-flight forced HLS tune, stamped by playUrl. */
    @Volatile private var forceHlsTargetOffsetMs: Int = FORCE_HLS_TARGET_OFFSET_MS

    private fun learnStartBuffer(snapshot: PlaybackTracer.FeedStallSnapshot) {
        if (!snapshot.isLive) return
        // Developer Force HLS: the bursty-feed learning is a TS-path tool; an
        // HLS session is buffered by Media3 from the playlist.
        if (lastPlayUrl?.let(ForceHls::isForcedHlsUrl) == true) {
            learnHlsLiveOffset(snapshot)
            return
        }
        val channelId = currentChannelIdForRebuild ?: return
        // A stream switch (its window, a skip, or the watch) and a same-channel
        // re-prime both stall by construction; that is not the feed's shape.
        // Learning from them pinned channels at the 10 s gate (2026-09-15).
        val now = SystemClock.elapsedRealtime()
        if (inSwitchWindow() || switchWatchJob?.isActive == true ||
            (sameChannelReopenAtMs != 0L && now - sameChannelReopenAtMs < SAME_CHANNEL_LEARN_QUIET_MS)
        ) {
            Log.i(TAG, "[HOLDBACK] ch=${snapshot.channelName} stall ignored: stream switch or same-channel re-prime")
            return
        }
        // Real time is a MEDIA-time question, not a bitrate one: a live
        // picture's bitrate swings with its content, so comparing the trailing
        // byte rate against the tune's own byte rate ignored two genuinely
        // bursty ESPNU HD stalls as "feed below real time" (session11
        // 22:24:19 and 22:25:33). The tracer's ring measures how much buffered
        // media the feed gained per unit of wall clock instead.
        val ratio = snapshot.feedMediaRatio
        if (ratio != null && ratio < HOLDBACK_MEDIA_RATIO_MIN) {
            Log.i(
                TAG,
                "[HOLDBACK] ch=${snapshot.channelName} stall ignored: feed below real time " +
                    "(media ratio ${"%.2f".format(ratio)})",
            )
            return
        }
        val learned = cachedLiveStartBuffers[channelId]?.ms ?: 0
        val next = (snapshot.worstGapMs + 1_000L)
            .coerceAtMost(LIVE_START_GATE_MAX_MS.toLong())
            .toInt()
            .coerceAtLeast(learned)
        // A stall that does not raise the value still RE-CONFIRMS it, which
        // re-arms its 30 minute life (Logan 2026-09-12); only the timestamp
        // moves in that case.
        val learnedAtMs = System.currentTimeMillis()
        val ratioText = if (ratio == null) "n/a" else "%.2f".format(ratio)
        Log.i(
            TAG,
            "[HOLDBACK] ch=${snapshot.channelName} bursty feed " +
                "(media ratio $ratioText, worst gap ${snapshot.worstGapMs} ms): " +
                "start buffer $learned -> $next ms for the NEXT tune " +
                "(learned at $learnedAtMs)",
        )
        // Update the cache immediately so a tune that beats the DataStore write
        // still applies the new gate; the write returns the stored map.
        cachedLiveStartBuffers = cachedLiveStartBuffers +
            (channelId to com.aeriotv.android.core.preferences.LearnedStartBuffer(next, learnedAtMs))
        prefScope.launch {
            cachedLiveStartBuffers =
                appPreferences.setLiveStartBufferMs(channelId, next, learnedAtMs)
        }
        // The learned hold-back applies on the NEXT tune (it is a start gate,
        // rebuilt into the LoadControl), never mid-stream: a live feed only
        // delivers at about real time, so holding for an 18 s learned value
        // just buys an 18 s pause (Apple device result 2026-09-13).
    }

    /**
     * How much media must be buffered AHEAD of the playhead before the resume
     * gate lets playback run again:
     *
     *   target = min(
     *       worst observed delivery gap * 1.5 + 2 s,
     *       12 s cap,
     *       maxBufferMs - 1 s          // what the LoadControl will actually hold
     *   )
     *
     * The 1.5x multiplier and the 12 s cap come from the Apple measurement of
     * the same design (2026-09-13): a feed with 7-8 s silent gaps pinned the old
     * gap+2 s / 8 s cap gate, so every hold bought exactly one burst and the
     * next gap drained it again (9 stalls in 90 s). Clearing the gap with real
     * margin is what stops the loop.
     *
     * The worst gap comes from the tracer's rolling 30 s feed ring, so it is the
     * measured shape of THIS feed, not a guess. The learned hold-back is
     * deliberately NOT part of this: a live feed delivers at about real time, so
     * waiting for a large learned value is simply a pause of that length.
     */
    private fun resumeGateTargetMs(): Long {
        val worstGapMs = tracer.worstGapMs().coerceAtLeast(0L)
        val want = (worstGapMs * 3L / 2L + RESUME_GATE_HEADROOM_MS)
            .coerceAtMost(RESUME_GATE_CAP_MS)
        val holdable = (builtMaxBufferMs - 1_000L).coerceAtLeast(0L)
        return if (holdable > 0L) minOf(want, holdable) else want
    }

    /** True when a resume gate may hold this playback: a live direct stream that
     *  had already reached steady playback (a cold start is the tune path's job,
     *  and timeshift / catch-up read a local buffer that cannot burst). */
    private fun resumeGateEligible(): Boolean {
        val p = player ?: return false
        val url = lastPlayUrl ?: return false
        if (isTimeshifting || isCatchup) return false
        if (PlaybackTracer.urlKind(url) != "live") return false
        // Developer Force HLS: Media3's HLS buffering (target duration,
        // EXT-X-START) governs; the TS resume gate and rejoin stay out.
        if (ForceHls.isForcedHlsUrl(url)) return false
        if (!hasReachedPlaybackRestart || !videoFrameRendered) return false
        return p.playWhenReady || resumeGateActive
    }

    // ---- repeat-stall rejoin ----
    // Elapsed-realtime of the last live underrun that reached the gate, and of
    // the last rejoin. Both are reset by a fresh tune.
    private var lastLiveUnderrunAtMs = 0L
    private var lastRejoinAtMs = 0L

    /** This channel's learned hold-back, honouring the same 30 minute TTL the
     *  tune path applies, or 0 when there is none. */
    private fun learnedHoldBackMs(): Int {
        val id = currentChannelIdForRebuild ?: currentChannelId ?: return 0
        val entry = cachedLiveStartBuffers[id] ?: return 0
        val age = System.currentTimeMillis() - entry.learnedAtMs
        return if (age > HOLDBACK_LEARNED_TTL_MS) 0 else entry.ms
    }

    /**
     * Every live buffer underrun lands here. The FIRST one in a minute is the
     * resume gate's job: hold until the cushion covers the measured gap. A
     * SECOND one inside [REPEAT_STALL_WINDOW_MS] means the gate is not winning
     * on this feed (Apple measured this exact shape: 7-8 s gaps, gate pinned at
     * its cap, one burst bought per hold, 9 stalls in 90 s), so instead of
     * holding again we rejoin BEHIND the live edge by the learned hold-back and
     * resume immediately, which puts a real cushion between the playhead and
     * the feed's arrival point rather than waiting for one to accumulate.
     */
    private fun onLiveUnderrun(reason: String) {
        if (!resumeGateEligible()) return
        if (SystemClock.elapsedRealtime() < switchJumpGraceUntilMs) {
            Log.i(TAG, "[HOLDBACK] resume gate skipped: just jumped to a switched stream ($reason)")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastLiveUnderrunAtMs
        val repeat = lastLiveUnderrunAtMs != 0L && sinceLast <= REPEAT_STALL_WINDOW_MS
        val cooled = lastRejoinAtMs == 0L || now - lastRejoinAtMs >= REJOIN_COOLDOWN_MS
        lastLiveUnderrunAtMs = now
        if (repeat && cooled && inSwitchWindow()) {
            Log.i(TAG, "[RECOVER] suppressed reload during switch window (reason=rejoin $reason)")
        } else if (repeat && cooled && tryRejoin(now)) return
        armResumeGate(reason)
    }

    /**
     * Rejoin the feed behind the live edge.
     *
     * Dispatcharr live is a PROGRESSIVE MPEG-TS source: ExoPlayer keeps no back
     * buffer on it (DefaultLoadControl's backBufferDurationMs is 0 and nothing
     * here raises it), so there is NOTHING behind the playhead in the player
     * itself and a plain seek back is impossible. The only real local window is
     * the Live Rewind timeshift buffer, which the live tee mirrors whenever a
     * rewind session is rolling on a raw-TS channel. When that window exists we
     * enter it [seekBack] behind its head; when it does not (no rewind session,
     * or an HLS/DASH live channel the tee cannot mirror) the rejoin degrades to
     * a re-tune through the normal tune path, which applies the learned
     * hold-back as the start gate immediately.
     *
     * Returns true when a rejoin was performed.
     */
    private fun tryRejoin(now: Long): Boolean {
        val url = lastPlayUrl ?: return false
        // A gate armed by an earlier underrun must not keep holding through the
        // rejoin; the point of the rejoin is to resume immediately.
        releaseResumeGate("rejoin")
        val learned = learnedHoldBackMs().toLong()
        val want = maxOf(learned, REJOIN_MIN_BACK_MS)
        val window = rewindWindow()
        val headWallMs = window?.get(1) ?: 0L
        val windowMs = if (window == null) 0L else (window[1] - window[0]).coerceAtLeast(0L)
        val usable = (windowMs - REJOIN_WINDOW_MARGIN_MS).coerceAtLeast(0L)
        val back = minOf(want, usable, REJOIN_MAX_BACK_MS)
        if (back >= REJOIN_MIN_BACK_MS && headWallMs > 0L) {
            // Never outside the window: back is already capped at the window
            // minus its 1 s margin, so the target is at or after the tail.
            val target = headWallMs - back
            Log.i(
                TAG,
                "[HOLDBACK] rejoin: seeking back $back ms into the local window " +
                    "(learned $learned ms, window $windowMs ms)",
            )
            if (playTimeshift(target)) {
                lastRejoinAtMs = now
                lastLiveUnderrunAtMs = 0L
                return true
            }
            Log.i(TAG, "[HOLDBACK] rejoin: local window entry failed, re-tuning instead")
        }
        // No window behind the playhead: re-tune, which rebuilds the LoadControl
        // with the learned hold-back as the start gate before the first frame.
        Log.i(
            TAG,
            "[HOLDBACK] rejoin: no local window behind the playhead " +
                "(window $windowMs ms), re-tuning with hold-back $learned ms",
        )
        lastRejoinAtMs = now
        lastLiveUnderrunAtMs = 0L
        playUrl(url, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri)
        return true
    }

    /** Arm (or re-arm) the gate. Safe to call repeatedly: an already-held gate
     *  simply keeps holding, and its target is re-read every watchdog tick so a
     *  hold-back learned during the hold raises the bar it must clear. */
    private fun armResumeGate(reason: String) {
        if (resumeGateActive) return
        if (!resumeGateEligible()) return
        val p = player ?: return
        // A feed gaining media time slower than the wall clock is starved
        // upstream, not bursty: there is no burst coming to refill the cushion,
        // so a gate would just sit there until the fuse blows.
        val ratio = tracer.feedMediaRatio()
        if (ratio != null && ratio < HOLDBACK_MEDIA_RATIO_MIN) {
            Log.i(TAG, "[HOLDBACK] resume gate skipped: upstream rate ${"%.2f".format(ratio)}")
            return
        }
        val target = resumeGateTargetMs()
        val ahead = bufferedAheadMs(p)
        if (target <= 0L || ahead >= target) return
        resumeGateActive = true
        resumeGateArmedAtMs = SystemClock.elapsedRealtime()
        resumeGateLoggedTargetMs = target
        Log.i(
            TAG,
            "[HOLDBACK] resume gate armed ch=$currentChannelId reason=$reason: " +
                "hold until ${target}ms buffered ahead (have ${ahead}ms, " +
                "worst gap ${tracer.worstGapMs()}ms, upstream rate " +
                "${ratio?.let { "%.2f".format(it) } ?: "n/a"}, " +
                "timeout ${RESUME_GATE_TIMEOUT_MS}ms)",
        )
        p.playWhenReady = false
    }

    /** Release the gate and let playback run. */
    private fun releaseResumeGate(reason: String) {
        if (!resumeGateActive) return
        val p = player
        Log.i(TAG, "[HOLDBACK] resume gate released ch=$currentChannelId: $reason")
        // Flag stays true across the assignment so onPlayWhenReadyChanged still
        // treats this as gate traffic and not a user resume.
        p?.playWhenReady = true
        resumeGateActive = false
        resumeGateArmedAtMs = 0L
        resumeGateLoggedTargetMs = 0L
    }

    /** Buffered media ahead of the playhead, the quantity the gate measures. */
    private fun bufferedAheadMs(p: ExoPlayer): Long {
        val buffered = p.bufferedPosition
        if (buffered == C.TIME_UNSET) return 0L
        return (buffered - p.currentPosition).coerceAtLeast(0L)
    }

    /** Drive the armed gate from the 1 s watchdog poll. */
    private fun tickResumeGate(p: ExoPlayer, now: Long) {
        if (!resumeGateActive) return
        if (!resumeGateEligible()) {
            releaseResumeGate("session changed")
            return
        }
        val target = resumeGateTargetMs()
        if (target > resumeGateLoggedTargetMs) {
            Log.i(
                TAG,
                "[HOLDBACK] resume gate target raised ${resumeGateLoggedTargetMs}ms -> ${target}ms " +
                    "ch=$currentChannelId (worst gap ${tracer.worstGapMs()}ms)",
            )
            resumeGateLoggedTargetMs = target
        }
        val ahead = bufferedAheadMs(p)
        val heldMs = now - resumeGateArmedAtMs
        when {
            ahead >= target -> releaseResumeGate("buffered ${ahead}ms >= target ${target}ms after ${heldMs}ms")
            heldMs >= RESUME_GATE_TIMEOUT_MS ->
                releaseResumeGate(
                    "hard timeout ${RESUME_GATE_TIMEOUT_MS}ms reached with only ${ahead}ms " +
                        "of ${target}ms buffered; resuming anyway",
                )
        }
    }

    private val watchdogScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var watchdogJob: Job? = null
    private var lastPositionAdvanceAtMs = 0L
    private var lastKnownPositionMs = 0L
    // Byte-ingest progress, tracked separately from render position. A wedged
    // proxy read stalls BOTH; an honest re-buffer (slow network moment, weak
    // device starved by UI work) stalls position while bufferedPosition keeps
    // advancing -- and reloading an honestly-buffering stream only makes it
    // worse (Chromecast field report 2026-07-18: EPG-scroll jank -> 3 stale
    // reloads in 18s -> player wedged -> terminal NO-DATA give-up).
    private var lastKnownBufferedPositionMs = 0L
    private var lastBufferAdvanceAtMs = 0L

    // ---- stall resume gate (live) ----
    // Apple parity (2026-09-13): a bursty live feed (worst delivery gap 4.8-7 s)
    // stalled six times in two minutes because the player resumed with ~2 s
    // buffered and then crept back toward the live edge, so the very next
    // delivery gap starved it again. After a buffer-underrun rebuffer we now
    // HOLD playback (playWhenReady=false, which keeps the LoadControl filling)
    // until there is enough media ahead to ride the worst observed gap out.
    // Deliberately a gate and not a bigger global buffer: the steady bounds in
    // [acquireOrCreate] govern every channel, this only costs the channels that
    // actually burst, and only right after they stall.
    /** True while the gate is holding playback. Also suppresses the Live Rewind
     *  pause/resume plumbing in [onPlayWhenReadyChanged]: this is not a user pause. */
    private var resumeGateActive = false
    /** elapsedRealtime the current gate was armed at (hard-timeout clock). */
    private var resumeGateArmedAtMs = 0L
    /** Last target logged, so a rising target logs once instead of every tick. */
    private var resumeGateLoggedTargetMs = 0L
    /** maxBufferMs the live LoadControl was built with. The gate can never wait
     *  for more media than the load control is willing to hold, so the target is
     *  clamped to just under it (a raised hold-back past this bound only takes
     *  full effect on the next tune, which rebuilds the player). */
    private var builtMaxBufferMs = 0

    /** True while this holder owns the "Reconnecting..." status published for a
     *  live ingest stall, so it only ever clears a line it set itself. */
    private var ingestStallStatusShown = false
    /** True while the internal starving signal ([isLiveIngestStalled] shape) is
     *  up. Logged only; it never drives UI on its own. */
    private var liveStarving = false
    /** Stall overlay position probe: the last playhead seen by the stall block
     *  and when it last moved. Separate from the heal clocks below so the
     *  overlay never depends on their early-outs. */
    private var stallProbePositionMs = -1L
    private var stallProbeAdvanceAtMs = 0L

    /** Milliseconds since the live buffer last grew (ingest freshness).
     *  GH #82: the Dispatcharr status follower must not declare a session
     *  dead while bytes are still arriving. */
    fun ingestAgeMs(): Long =
        if (lastBufferAdvanceAtMs == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastBufferAdvanceAtMs

    /** Media the player still has ahead of the playhead, in ms. */
    fun bufferAheadMs(): Long {
        val p = player ?: return 0L
        return (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
    }

    /**
     * The internal live "starving" predicate, used by the Dispatcharr
     * follow-poller's dead-session rule (and logged by the watchdog). It does
     * NOT drive the "Reconnecting" overlay, which waits for a real playback
     * stall (rebuffer, frozen playhead, or empty buffer). The PLAYER
     * is starving, not merely the socket quiet. True when there is under
     * [STALL_BUFFER_AHEAD_MS] of media ahead AND either ingest has been silent
     * for [INGEST_STALL_STATUS_MS] or the player is sitting in BUFFERING while
     * it wants to play. Must be called on the main thread.
     */
    fun isLiveIngestStalled(): Boolean {
        val p = player ?: return false
        if (bufferAheadMs() >= STALL_BUFFER_AHEAD_MS) return false
        return ingestAgeMs() >= INGEST_STALL_STATUS_MS ||
            (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
    }
    // When the watchdog tick itself arrives late, the MAIN THREAD was blocked
    // (the scope is Main.immediate) -- staleness accrued during that hang is
    // evidence of UI jank, not of a dead stream.
    private var lastWatchdogTickAtMs = 0L
    private var lastForcedReloadAtMs = 0L
    // Armed only once the stream reaches steady playback (iOS
    // hasReachedPlaybackRestartForStream) so a slow cold-start probe is never
    // mistaken for a wedge. Backed by an observable flow so the live
    // follow-poller (PlayerScreen) can gate itself ON only while steady,
    // keeping it mutually exclusive with the cold-start no-data watchdog.
    private val _reachedSteadyPlayback = MutableStateFlow(false)
    val reachedSteadyPlayback: StateFlow<Boolean> = _reachedSteadyPlayback.asStateFlow()
    private var hasReachedPlaybackRestart: Boolean
        get() = _reachedSteadyPlayback.value
        set(value) { _reachedSteadyPlayback.value = value }
    private var consecutiveReloads = 0
    // Last foreground play() args, replayed by the watchdog to reload the same url.
    private var lastPlayUrl: String? = null
    private var lastPlayTitle: String? = null
    private var lastPlaySubtitle: String? = null
    private var lastPlayArtworkUri: android.net.Uri? = null
    // GH #27: DRM args ride along so watchdog re-primes keep the keys.
    private var lastPlayDrmType: String? = null
    private var lastPlayDrmKey: String? = null
    // Thresholds carried over from the iOS watchdog (6s stale / 5s cooldown).
    private val staleReloadThresholdMs = 6_000L
    private val reloadCooldownMs = 5_000L
    private val watchdogPollMs = 1_000L
    private val maxConsecutiveReloads = 3

    // ---- stream switch follow + single re-prime (manual Switch Stream, follow-poller, LAN/WAN) ----
    private val reprimeMutex = Mutex()
    @Volatile private var reprimeInFlight = false
    /** True while a switch follow or re-prime is mid-flight; the follow-poller parks on it. */
    val isReprimeInFlight: Boolean get() = reprimeInFlight

    /** Bumped by every [playUrl]. A switch follow that sees it move knows some
     *  other path (watchdog, error reload, 503 backoff) already re-primed. */
    @Volatile private var primeGeneration = 0L

    /** Read-only view of [primeGeneration] for the follow-poller: a deferred
     *  follow that sees it move knows the connection was reopened meanwhile. */
    val currentPrimeGeneration: Long get() = primeGeneration

    /**
     * GH #117 (FrankieBBBB, Shield): a kept-connection follow that lands while
     * a 503 quick retry is pending, or right after its re-prime reopened the
     * source (IDLE, or BUFFERING with nothing buffered), crashed
     * ExoPlayer:Playback with IllegalStateException. True while following now
     * is unsafe; the follow-poller defers and re-arms once this clears. Main thread.
     */
    fun isFollowUnsafe(): Boolean {
        if (stoppingRetryJob?.isActive == true) return true
        val p = player ?: return true
        return when (p.playbackState) {
            Player.STATE_IDLE -> true
            Player.STATE_BUFFERING -> bufferedAheadMs(p) <= 0L
            else -> false
        }
    }

    /** The live raw-TS source currently primed, wrapped so a stream switch can
     *  hard-switch onto the new stream on the same connection. Main thread. */
    private var switchSkipSource: SwitchSkipMediaSource? = null

    /** Until this elapsedRealtime, an underrun right after a switch jump does
     *  not arm the resume gate: the jump leaves a thin cushion by design and
     *  the next burst is already loading. */
    private var switchJumpGraceUntilMs = 0L

    /** Until this elapsedRealtime a stream switch is settling: Dispatcharr may
     *  still be connecting the new upstream (JayK: ~20 s of silence while the
     *  provider retried). A same-channel reopen in that window hits
     *  stream_limit before the server notices the old socket close, stops the
     *  channel and restarts it on the default stream. So only a player error
     *  (or the follow-poller's confirmed dead session) may reopen in it. */
    @Volatile private var switchWindowUntilMs = 0L

    private fun inSwitchWindow(): Boolean = SystemClock.elapsedRealtime() < switchWindowUntilMs

    /** Wrap a freshly built live source for [followStreamSwitch]. Non raw-TS
     *  sources pass through untouched. */
    private fun wrapForSwitchSkip(url: String, source: MediaSource): MediaSource {
        releaseAudioHold("source rebuilt")
        if (!isRawTsUrl(url)) {
            switchSkipSource = null
            return source
        }
        return SwitchSkipMediaSource(source).also { switchSkipSource = it }
    }

    // ---- same-channel reopen 503 quick retry ----
    // JayK (Dispatcharr log): on a same-channel reconnect the server may still
    // count the old client (it has not noticed the close yet), so at
    // stream_limit 1 it terminates that one, the channel stops (shutdown delay
    // 0) and our new request is answered 503 ("became unavailable during
    // setup" / "Channel is stopping"). One quick retry once the server has
    // settled usually lands; only then does the normal 503 handling run.
    private var lastReopenUrl: String? = null
    private var lastReopenChannelId: String? = null
    private var sameChannelReopenAtMs = 0L
    private var sameChannelQuickRetryUsed = false

    /** Record a source (re)open. Same channel (id or url) stamps the reopen
     *  time; a different channel clears it and the quick retry budget. */
    private fun noteSourceOpen(url: String, channelId: String?) {
        val same = url == lastReopenUrl || (channelId != null && channelId == lastReopenChannelId)
        if (same) {
            sameChannelReopenAtMs = SystemClock.elapsedRealtime()
        } else {
            sameChannelReopenAtMs = 0L
            sameChannelQuickRetryUsed = false
        }
        lastReopenUrl = url
        if (channelId != null) lastReopenChannelId = channelId
    }

    /**
     * Re-prime [url] on ONE connection: [playUrl] retires the old source's
     * Calls on the playback looper before the new source's loader opens, so
     * the two sockets never overlap. Dispatcharr counts every live GET
     * against user.stream_limit, and a second concurrent GET (the old
     * keepalive) made it terminate the player's connection at limit 1, stop
     * the channel, and restart it on the default stream, undoing the switch.
     *
     * Serialised via [reprimeMutex] and gated on the shared [reloadCooldownMs]
     * (same window the stall watchdog uses) UNLESS [bypassCooldown].
     * Returns true if the re-prime ran.
     */
    suspend fun reprime(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
        bypassCooldown: Boolean = false,
        reason: String = "re-prime",
    ): Boolean = reprimeMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        // A notice owns the screen (limit refusal or repeated clean end): only
        // the user's Retry or a channel change may reopen the connection.
        if (_connectionLimit.value != null) {
            Log.i(TAG, "[RECOVER] re-prime skipped (reason=$reason): notice showing, waiting for Retry")
            return@withLock false
        }
        // The dead session that follows a clean end IS that end: let the
        // clean-end backoff own the reconnect instead of re-priming now.
        if (reason.startsWith("dead session") &&
            withContext(Dispatchers.Main) { onLiveCleanEnd(reason) }
        ) {
            return@withLock false
        }
        if (!bypassCooldown && now - lastForcedReloadAtMs < reloadCooldownMs) {
            Log.i(TAG, "[FOLLOW] re-prime skipped (within ${reloadCooldownMs}ms cooldown)")
            return@withLock false
        }
        reprimeInFlight = true
        try {
            singleReprimeLocked(url, title, subtitle, artworkUri, reason)
            true
        } finally {
            reprimeInFlight = false
        }
    }

    private suspend fun singleReprimeLocked(
        url: String,
        title: String?,
        subtitle: String?,
        artworkUri: android.net.Uri?,
        reason: String,
    ) {
        lastForcedReloadAtMs = SystemClock.elapsedRealtime()
        tracer.recover(reason)
        withContext(Dispatchers.Main) { playUrl(url, title, subtitle, artworkUri) }
    }

    /** The running post-switch watch; a newer switch replaces it. */
    private var switchWatchJob: Job? = null

    // The upstream (Dispatcharr status url) the latest follow targeted, for the
    // proxy url it ran on. A manual switch followed by the follow-poller seeing
    // the same status change used to follow twice, and the second skip dropped
    // NEW-stream media (Nothing Phone 2026-09-15).
    @Volatile private var followTarget: String? = null
    @Volatile private var followTargetProxyUrl: String? = null
    @Volatile private var followTargetAtMs = 0L

    /** True when [target] on [proxyUrl] is the stream a follow is already
     *  watching, or was followed within the switch window. The follow-poller
     *  adopts such a status change as its baseline without following again. */
    fun isFollowingTarget(proxyUrl: String, target: String): Boolean =
        target == followTarget && proxyUrl == followTargetProxyUrl &&
            (switchWatchJob?.isActive == true ||
                SystemClock.elapsedRealtime() - followTargetAtMs < SWITCH_WINDOW_MS)

    /**
     * Follow a Dispatcharr upstream switch (manual change_stream, WebUI switch,
     * or server failover) WITHOUT touching the connection. Dispatcharr swaps
     * the upstream in place on the same socket and resets its buffer, so the
     * new stream's bytes arrive on the player's existing GET. The old stream's
     * buffered samples are dropped once new data is queued (see
     * [SwitchSkipMediaSource]); otherwise they simply play out.
     *
     * NEVER reopens the connection (JayK 2026-09-15: a provider that took 30 s
     * to connect the new upstream turned the old no-progress re-prime into a
     * stream_limit teardown and a restart on the default stream). While the
     * new upstream is silent the switch window stays open, so the watchdog
     * shows Reconnecting and waits. The only reopen triggers left are a fatal
     * player error and the follow-poller's confirmed dead session; neither is
     * blocked here, because the watch runs detached and does not mark a
     * re-prime in flight.
     *
     * [title], [subtitle], [artworkUri] and [bypassCooldown] are kept for the
     * call sites; nothing here re-primes any more. Returns true when the watch
     * started, false when there is no live player to follow.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun followStreamSwitch(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
        bypassCooldown: Boolean = false,
        /** The Dispatcharr status url being switched to; repeats for it are ignored. */
        targetStreamUrl: String? = null,
    ): Boolean = withContext(Dispatchers.Main) {
        if (targetStreamUrl != null && isFollowingTarget(url, targetStreamUrl)) {
            Log.i(TAG, "[SWITCH] already following this stream; ignoring repeat follow")
            return@withContext true
        }
        if (targetStreamUrl != null) {
            followTarget = targetStreamUrl
            followTargetProxyUrl = url
            followTargetAtMs = SystemClock.elapsedRealtime()
        }
        val p = player
        if (p == null || isTimeshifting) {
            Log.i(TAG, "[SWITCH] no live player to follow; nothing to do")
            return@withContext false
        }
        // GH #117: never request a skip on a source that a 503 quick retry is
        // about to replace or has just reopened. The reopen already carries the
        // new upstream, so there is no old buffer to drop.
        if (isFollowUnsafe()) {
            Log.i(TAG, "[SWITCH] player recovering (state=${p.playbackState}, quick retry pending=${stoppingRetryJob?.isActive == true}); no kept-connection skip")
            switchWindowUntilMs = SystemClock.elapsedRealtime() + SWITCH_WINDOW_MS
            return@withContext true
        }
        val startAt = SystemClock.elapsedRealtime()
        val startGen = primeGeneration
        val startPos = p.currentPosition
        val startBuffered = p.bufferedPosition
        Log.i(TAG, "[SWITCH] kept connection (buffered ahead ${startBuffered - startPos}ms at switch)")
        switchWindowUntilMs = startAt + SWITCH_WINDOW_MS
        // Drop the old stream's buffered samples as soon as new data is queued,
        // instead of playing them out. Any failure leaves the old buffer to play.
        val src = switchSkipSource
        val skipRequested = src != null && src.requestSkip(
            android.os.Handler(p.playbackLooper),
            android.os.Handler(android.os.Looper.getMainLooper()),
            minNewDataMs = SWITCH_SKIP_MIN_NEW_DATA_MS,
            timeoutMs = SWITCH_SKIP_TIMEOUT_MS,
        ) { result -> onSwitchSkipResult(result) }
        if (!skipRequested) Log.i(TAG, "[SWITCH] old buffer skip unavailable; playing it out")
        switchWatchJob?.cancel()
        switchWatchJob = watchdogScope.launch {
            var lastPos = startPos
            var lastMoveAt = startAt
            var lastWaitLogAt = 0L
            while (isActive) {
                delay(SWITCH_POLL_MS)
                val now = SystemClock.elapsedRealtime()
                val pl = player
                if (pl == null || primeGeneration != startGen || isTimeshifting || lastPlayUrl != url) {
                    Log.i(TAG, "[SWITCH] another path took over the player; ending watch")
                    return@launch
                }
                val pos = pl.currentPosition
                // A user pause is not a stuck switch.
                if (pos != lastPos || !pl.playWhenReady) { lastPos = pos; lastMoveAt = now }
                if (pos > startBuffered + SWITCH_PAST_BUFFER_MS && now - lastMoveAt < SWITCH_POLL_MS * 4) {
                    Log.i(TAG, "[SWITCH] playback resumed on new stream after ${now - startAt}ms")
                    return@launch
                }
                if (now - lastMoveAt >= SWITCH_NO_PROGRESS_MS) {
                    // Dispatcharr is still connecting the new upstream and fails
                    // over (or ends the session) on its own; keep the window open
                    // so no silence heal reopens the channel meanwhile.
                    switchWindowUntilMs = now + SWITCH_WINDOW_MS
                    if (lastWaitLogAt == 0L) {
                        Log.i(TAG, "[SWITCH] waiting for new upstream (no reload) ${(now - startAt) / 1000}s")
                        lastWaitLogAt = now
                    } else if (now - lastWaitLogAt >= SWITCH_WAIT_LOG_MS) {
                        Log.i(TAG, "[SWITCH] still waiting for new upstream ${(now - startAt) / 1000}s")
                        lastWaitLogAt = now
                    }
                }
            }
        }
        true
    }

    // ---- post-jump audio hold ----
    // AMD 2026-09-16: on a jump the audio renderer plays the new stream from
    // any point, while the video decoder needs its first keyframe to arrive and
    // decode. That gap is exactly the reported "audio switched, picture frozen".
    // Mute (never pause: the picture holds on the last frame and the buffer
    // keeps draining normally) until the video renderer actually renders a
    // frame, bounded by SWITCH_AUDIO_HOLD_MS.
    private var switchAudioHoldVolume: Float? = null
    private var switchAudioHoldJob: Job? = null
    private var switchAudioHoldAtMs = 0L

    private fun renderedVideoFrames(): Long =
        player?.videoDecoderCounters?.let { it.renderedOutputBufferCount.toLong() } ?: -1L

    private fun holdAudioForFirstVideoFrame() {
        val p = player ?: return
        if (switchAudioHoldVolume != null) return
        val baseline = renderedVideoFrames()
        if (baseline < 0L) return // no video renderer: nothing to wait for
        switchAudioHoldVolume = p.volume
        switchAudioHoldAtMs = SystemClock.elapsedRealtime()
        p.volume = 0f
        switchAudioHoldJob?.cancel()
        switchAudioHoldJob = watchdogScope.launch {
            while (isActive) {
                delay(SWITCH_AUDIO_HOLD_POLL_MS)
                val now = SystemClock.elapsedRealtime()
                if (player == null || renderedVideoFrames() > baseline) {
                    releaseAudioHold("first video frame")
                    return@launch
                }
                if (now - switchAudioHoldAtMs >= SWITCH_AUDIO_HOLD_MS) {
                    releaseAudioHold("timeout, leaving it to stall recovery")
                    return@launch
                }
            }
        }
    }

    /** Main thread. Restores the volume the hold muted; a no-op otherwise. */
    private fun releaseAudioHold(reason: String) {
        val v = switchAudioHoldVolume ?: return
        switchAudioHoldVolume = null
        switchAudioHoldJob?.cancel()
        switchAudioHoldJob = null
        player?.volume = v
        Log.i(
            TAG,
            "[SWITCH] audio hold released after " +
                "${SystemClock.elapsedRealtime() - switchAudioHoldAtMs}ms ($reason)",
        )
    }

    /** Main thread: outcome of a [SwitchSkipMediaSource] skip. */
    private fun onSwitchSkipResult(result: SwitchSkipMediaSource.Result) {
        when (result) {
            is SwitchSkipMediaSource.Result.Jumped -> {
                Log.i(
                    TAG,
                    "[SWITCH] jumped to new stream at boundary +${result.boundaryOffsetMs}ms " +
                        "(dropped ${result.droppedMs}ms old buffer)",
                )
                switchJumpGraceUntilMs = SystemClock.elapsedRealtime() + SWITCH_JUMP_GRACE_MS
                holdAudioForFirstVideoFrame()
                // Resume on what is loaded; never hold for a full gate here.
                releaseResumeGate("stream switch jump")
                lastPositionAdvanceAtMs = SystemClock.elapsedRealtime()
            }
            SwitchSkipMediaSource.Result.AlreadyPast ->
                Log.i(TAG, "[SWITCH] playhead already past the boundary; nothing to drop")
            is SwitchSkipMediaSource.Result.Abandoned -> {
                Log.i(TAG, "[SWITCH] old buffer skip abandoned (${result.reason}); playing it out")
                releaseAudioHold("skip abandoned")
            }
        }
    }

    // ---- black-screen (no-video-frame) net ----
    // The position poll above cannot see the field-reported black screen:
    // audio keeps currentPosition advancing while the video renderer never
    // draws a frame (Stream Info shows an active MediaCodec decoder and
    // state: playing over pure black; survives channel switches). Track the
    // FIRST rendered frame per primed stream; if steady playback runs this
    // long without one, heal: re-prime the url, then recreate the player
    // (fresh codec + fresh surface binding via the playerInstance flow).
    private var videoFrameRendered = false
    private var noFrameHealAttempts = 0
    private var streamPrimedAtMs = 0L
    // A healthy stream renders its first frame well under 1s after READY, and
    // field logs show users abandon a black screen in seconds (one closed the
    // player 7.8s in, 200ms before the original 8s trigger). 5s keeps a wide
    // margin over normal startup while healing before the user gives up.
    private val noVideoFrameThresholdMs = 5_000L
    // ---- cold-start no-data net (never-started stream) ----
    // A dead Dispatcharr upstream / proxy locked on a dead stream delivers ZERO
    // bytes, so the player never leaves STATE_BUFFERING, never reaches READY,
    // and every heal above (all gated on hasReachedPlaybackRestart) stays
    // disarmed -> black screen forever (field: 57s+ and counting). This is the
    // Android analog of iOS's libmpv network-timeout=30. After this long with no
    // bytes since prime we reconnect ONCE (a fresh GET to the same proxy url,
    // which also lets Dispatcharr re-select a live stream), then surface
    // "unavailable" instead of hanging.
    private val noDataStartupThresholdMs = 15_000L
    // Issue #17: for a LIVE Dispatcharr channel whose top source is dead, the
    // proxy fails that source over to a working one SERVER-SIDE on the same open
    // connection (~20-40s: MAX_RETRIES x CONNECTION_TIMEOUT + health monitor).
    // libmpv survives the silent gap on iOS via network-timeout=30 + deep cache;
    // ExoPlayer must be given the same patience or its 30s read timeout tears the
    // connection down and shows "Channel unavailable" before the waterfall
    // completes. So live gets a longer read timeout (kept ABOVE the ceiling) and
    // a longer no-data ceiling; and for live we DON'T reconnect at the ceiling (a
    // fresh GET would only abandon the connection the proxy is still advancing
    // on) -- if nothing arrived by then the whole channel is dead.
    private val liveNoDataStartupThresholdMs = 50_000L
    private val liveReadTimeoutMs = 55_000
    private var noDataHealAttempts = 0
    /** Same-url retries already spent on a "Channel is stopping" 503 this tune.
     *  Reset by [resetWatchdogStateForNewStream] like every other heal budget. */
    private var stoppingRetries = 0
    /** When the CURRENT run of "Channel is stopping" 503s began, so the wait is
     *  capped by [Dispatcharr503.STOPPING_BUDGET_MS] of wall clock rather than
     *  by a retry count. 0 = no run in progress. */
    private var stoppingFirstAtMs = 0L
    /** The url whose 503 [handleLive503] most recently acted on, with when. A
     *  single 503 reaches us twice (the load error, then the terminal player
     *  error it becomes); the second arrival must not start a second retry or
     *  a second failover step. */
    private var live503HandledKey: String? = null
    private var live503HandledAtMs = 0L
    /** Whether that 503 left [handleLive503] owning recovery (a scheduled
     *  same-url retry). False means the generic ladder must still run. */
    private var live503OwnedRecovery = false
    /** Job holding the pending Retry-After wait, so a channel flip or teardown
     *  can cancel a retry that is no longer wanted. */
    private var stoppingRetryJob: Job? = null
    /** The server's verbatim 503 reason for the current tune, published into the
     *  unavailable overlay instead of a guessed cause. */
    @Volatile private var serverReason: String? = null
    private val _streamUnavailable = MutableStateFlow(false)
    /** True when a freshly-tuned live stream produced no data even after a
     *  reconnect, so the player UI can show "Channel unavailable" instead of an
     *  endless black screen. Cleared on the next [playUrl]. */
    val streamUnavailable: StateFlow<Boolean> = _streamUnavailable.asStateFlow()
    private val _lastErrorText = MutableStateFlow<String?>(null)
    /** Task #150: the most recent playback failure in user-showable form
     *  (error code name + cause message, or the no-data description). The
     *  unavailable overlay shows it so "Channel unavailable" stops hiding
     *  what actually went wrong. Cleared on the next [playUrl]. */
    val lastErrorText: StateFlow<String?> = _lastErrorText.asStateFlow()
    private val _connectionLimit = MutableStateFlow<DispatcharrConnectionLimit.Notice?>(null)
    /** Set when Dispatcharr refused the live / catch-up request with an exact
     *  connection-limit signal (see [DispatcharrConnectionLimit]). The player
     *  shows the notice with Retry; nothing retries automatically. Cleared by
     *  the next tune or [retryConnectionLimit]. */
    val connectionLimit: StateFlow<DispatcharrConnectionLimit.Notice?> = _connectionLimit.asStateFlow()
    /** Whether the tune the limit notice refused was a catch-up replay, so
     *  Retry re-tunes the archive and not the live channel. */
    private var connectionLimitWasCatchup = false
    /** The URL to replay when the unavailable overlay retries. Preserved by
     *  [markStreamUnavailable] BEFORE it calls [stop] (which nulls
     *  [lastPlayUrl]) - without this every [retryUnavailable] hit the
     *  `lastPlayUrl ?: return` guard and no-op'd, so the countdown cycled
     *  forever but never re-tuned and a returning server never recovered
     *  (Streamer field test 2026-07-12: Dispatcharr container killed then
     *  restarted, retry never reconnected). Cleared on a fresh [playUrl]. */
    private var reconnectUrl: String? = null

    /** Channel id of the stream [markStreamUnavailable] gave up on, preserved
     *  for the same reason as [reconnectUrl]: its [stop] nulls both channel
     *  ids, so the overlay retry used to re-prime with no id, the rebuild hook
     *  returned null, and every [STALL] line after the recovery read ch=null
     *  (phone log 2026-09-14 15:06:42 / 15:07:10, ESPN2 HD). */
    private var reconnectChannelId: String? = null

    /** Task #150: manual/auto retry for the unavailable overlay. Clears the
     *  flag, resets the no-data heal budget, and re-primes the last URL --
     *  through the LAN/WAN re-probe hook when the screen wired one, so a
     *  network flip since the failure is picked up. */
    fun retryUnavailable() {
        // lastPlayUrl is null here (markStreamUnavailable -> stop() cleared it),
        // so fall back to the URL preserved at markStreamUnavailable time.
        val url = lastPlayUrl ?: reconnectUrl ?: return
        // Restore the id stop() cleared BEFORE the rebuild hook reads it.
        val channelId = currentChannelIdForRebuild ?: currentChannelId ?: reconnectChannelId
        channelId?.let { currentChannelIdForRebuild = it }
        _streamUnavailable.value = false
        _lastErrorText.value = null
        noDataHealAttempts = 0
        if (onTerminalErrorRebuildUrl != null) {
            watchdogScope.launch {
                val fresh = rebuildUrlForCurrentChannel()
                withContext(Dispatchers.Main) {
                    playUrl(
                        if (!fresh.isNullOrBlank()) fresh else url,
                        lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri,
                        channelId = channelId,
                    )
                }
            }
        } else {
            playUrl(url, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri, channelId = channelId)
        }
    }

    /**
     * GH #107 (TwistdSpokes, Amazon Fire TV AFTKM / MediaTek, Android 11).
     *
     * Bumped whenever the holder needs the persistent window to throw away its
     * SurfaceView and build a NEW one. PersistentExoWindow collects this and
     * bumps its own surfaceEpoch, which re-runs the AndroidView factory.
     *
     * The failure this exists for: an in-place reload flushes the video
     * renderer, MediaCodec.flush() throws CodecException 0xffffff92, ACodec
     * goes "State machine stuck" and force-releases
     * OMX.MTK.VIDEO.DECODER.AVC. From that point every tune logs "Could not
     * find corresponding native window for surface": audio plays, the picture
     * is frozen, and only a device reboot recovered. Neither a re-prime nor a
     * player rebuild alone cures it, because the SURFACE the dead codec was
     * bound to is what the platform lost; it has to be recreated too.
     */
    private val _surfaceRebuildRequest = MutableStateFlow(0)
    val surfaceRebuildRequest: StateFlow<Int> = _surfaceRebuildRequest.asStateFlow()

    /** Full player + surface rebuilds attempted inside the current window. */
    private var fullRebuildAttempts = 0
    /** elapsedRealtime of the first rebuild in the current window. */
    private var fullRebuildWindowStartMs = 0L
    /** elapsedRealtime of the most recent rebuild (backoff floor). */
    private var lastFullRebuildAtMs = 0L

    /**
     * A decoder-level runtime failure: the MediaCodec instance itself died
     * (flush / init / queue threw CodecException), not the stream. Media3
     * reports the Fire TV flush death as ERROR_CODE_FAILED_RUNTIME_CHECK with
     * a MediaCodec.CodecException cause, because it is raised inside renderer
     * disable (onDisabled -> flush) rather than on a sample path.
     *
     * Deliberately NOT the same predicate as [isTransientDecoderError]: that
     * one covers the tune-time codec handover race a same-url re-prime cures.
     * This one means the codec is gone and anything short of a fresh player
     * AND a fresh surface will render into a dead native window.
     */
    private fun isDecoderDeath(error: PlaybackException, causeChain: String): Boolean {
        val codecFault = generateSequence(error as Throwable?) { it.cause }
            .any { it is android.media.MediaCodec.CodecException }
        if (!codecFault) {
            // Some OEM stacks surface the same death with no CodecException in
            // the chain, only the AOSP wording.
            if (!causeChain.contains("native_flush", ignoreCase = true) &&
                !causeChain.contains("MediaCodec.flush", ignoreCase = true) &&
                !causeChain.contains("native window for surface", ignoreCase = true)
            ) {
                return false
            }
        }
        return error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            causeChain.contains("native_flush", ignoreCase = true) ||
            causeChain.contains("native window for surface", ignoreCase = true)
    }

    /**
     * Full teardown of the player AND the output surface, then re-tune the same
     * channel. The user-visible experience is the ordinary "Reconnecting"
     * recovery; only the depth of the teardown differs.
     *
     * Bounded: at most [MAX_FULL_REBUILDS] inside [FULL_REBUILD_WINDOW_MS],
     * with an escalating backoff. Past the cap the existing unavailable card
     * takes over instead of rebuilding forever. Returns true when a rebuild was
     * actually scheduled.
     */
    private fun rebuildPlayerAndSurface(reason: String): Boolean {
        if (isTimeshifting || isCatchup) return false
        val url = lastPlayUrl ?: reconnectUrl ?: return false
        val ctx = appContext ?: return false
        val now = SystemClock.elapsedRealtime()
        if (fullRebuildWindowStartMs != 0L && now - fullRebuildWindowStartMs > FULL_REBUILD_WINDOW_MS) {
            fullRebuildAttempts = 0
            fullRebuildWindowStartMs = 0L
        }
        if (fullRebuildAttempts >= MAX_FULL_REBUILDS) {
            Log.w(
                TAG,
                "[RECOVER] rebuild budget spent ($fullRebuildAttempts in " +
                    "${now - fullRebuildWindowStartMs}ms) reason=$reason; surfacing unavailable",
            )
            tracer.recover("rebuild budget spent; surfacing unavailable")
            markStreamUnavailable()
            return false
        }
        if (fullRebuildWindowStartMs == 0L) fullRebuildWindowStartMs = now
        fullRebuildAttempts++
        lastFullRebuildAtMs = now
        // First rebuild goes as fast as the surface swap allows; a second one
        // waits longer so a device that needs time to reclaim the codec gets it.
        val settleMs = if (fullRebuildAttempts <= 1) FULL_REBUILD_SETTLE_MS
        else FULL_REBUILD_SETTLE_MS * 3
        Log.w(
            TAG,
            "[RECOVER] $reason; rebuilding player and surface ch=$currentChannelId " +
                "attempt=$fullRebuildAttempts settle=${settleMs}ms",
        )
        tracer.recover("$reason; rebuilding player and surface attempt=$fullRebuildAttempts")
        liveFailover.publishServerStatus("Reconnecting...")
        val title = lastPlayTitle
        val subtitle = lastPlaySubtitle
        val art = lastPlayArtworkUri
        val chan = currentChannelId ?: currentChannelIdForRebuild ?: reconnectChannelId
        // Release the player (and with it the dead codec) BEFORE the window
        // drops the SurfaceView, so nothing is still bound to the old surface
        // when it is destroyed.
        destroy()
        _surfaceRebuildRequest.value = _surfaceRebuildRequest.value + 1
        watchdogScope.launch {
            // Let the composition swap in a brand-new SurfaceView and let the
            // platform finish tearing the old native window down.
            delay(settleMs)
            withContext(Dispatchers.Main) {
                if (isTimeshifting || isCatchup) return@withContext
                acquireOrCreate(ctx)
                playUrl(url, title, subtitle, art, channelId = chan)
                currentChannelId = chan
            }
        }
        return true
    }

    /** One transient-decoder retry per tune (session2.txt 19:56:48 codec handover).
     *  Reset by [resetWatchdogStateForNewStream] on a genuinely new tune; the retry
     *  itself re-sets it so only ONE retry runs per failure. */
    private var decoderRetryUsed = false

    /** Codec-handover shaped failure: a reclaimed / dead MediaCodec rather than a
     *  dead stream. Matches by error code, and by the cause chain for the
     *  DEAD_OBJECT / CodecException wording MediaTek boxes report. */
    private fun isTransientDecoderError(error: PlaybackException, causeChain: String): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ||
            causeChain.contains("DEAD_OBJECT", ignoreCase = true) ||
            generateSequence(error as Throwable?) { it.cause }
                .any { it is android.media.MediaCodec.CodecException }

    /**
     * Ask the LAN/WAN rebuild hook for a fresh URL for the channel that is
     * ACTUALLY primed, and refuse an answer that belongs to a different one.
     *
     * session2.txt 19:56:49.064: the hook (then closing over a stale composable
     * value) handed back ESPN HD's /proxy/ts/stream/e022bf3d... while the holder
     * was primed on the Sky Sports UHD uuid d02863e4..., so the app played the
     * wrong channel under the UHD title and polled a 404 status endpoint for a
     * minute. Returning null here makes the caller fall through to a plain
     * forceReload of lastPlayUrl instead.
     */
    private suspend fun rebuildUrlForCurrentChannel(): String? {
        val hook = onTerminalErrorRebuildUrl ?: return null
        val id = currentChannelIdForRebuild ?: return null
        val fresh = runCatching { hook(id) }.getOrNull()
        if (fresh.isNullOrBlank()) return null
        val uuid = id.substringAfterLast(':')
        if (uuid.isNotBlank() && !fresh.contains(uuid)) {
            Log.w(TAG, "[RECOVER] rebuild url rejected: belongs to another channel")
            tracer.recover("rebuild url rejected: belongs to another channel")
            return null
        }
        return fresh
    }

    /** Developer Force HLS warm join + trough ramp (shared with Multiview tiles). */
    private val forceHlsCushion = ForceHlsCushion("main")

    private fun resetForceHlsCushion(reason: String) = forceHlsCushion.reset(player, reason)

    private fun tickForceHlsCushion(p: ExoPlayer, now: Long) {
        val url = lastPlayUrl
        val forced = url != null && ForceHls.isForcedHlsUrl(url) && !isTimeshifting && !isCatchup
        forceHlsCushion.tick(p, forced, forceHlsTargetOffsetMs.toLong(), now)
    }

    /** Arms the watchdog on first steady playback + recovers on a hard error. */
    private val watchdogListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Reaching READY means the failure (if any) is behind us, so a later
            // playWhenReady=false is a real user pause again.
            if (playbackState == Player.STATE_READY) errorPending = false
            if (playbackState == Player.STATE_READY && player?.isPlaying == true) armWatchdog()
            // Buffer-underrun rebuffer on a live stream that was already
            // playing: hold the resume until the cushion is deep enough to
            // survive the worst delivery gap this feed has shown.
            if (playbackState == Player.STATE_BUFFERING) onLiveUnderrun("rebuffer")
            if (playbackState == Player.STATE_ENDED) onLiveCleanEnd("ENDED")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && player?.playbackState == Player.STATE_READY) armWatchdog()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Live Rewind filler lifecycle (GH #51): the chrome transport was
            // the ONLY caller of onLivePaused/onLiveResumedAtEdge, so a pause
            // or resume that arrived through the MediaSession instead (remote
            // media key, notification, QS media card, Bluetooth) bypassed the
            // controller entirely. Worst case, chrome-pause + media-key-resume
            // left the independent filler streaming the full live feed
            // alongside the player's own tee for the rest of the channel
            // session: a genuine second Dispatcharr client (the reported
            // "ghost stream") AND two connections interleaving appends into
            // the same buffer. Driving the controller off the player's actual
            // playWhenReady makes every pause path arm the delayed filler and
            // every direct-live resume retire it. Checked at CALLBACK time,
            // not enqueue: the chrome's long-pause resume flips
            // playWhenReady=true then immediately enters timeshift, and by the
            // time this runs isTimeshifting is already true, so the filler
            // (which timeshift playback needs) is left alone.
            // The resume gate drives playWhenReady itself; it is neither a user
            // pause nor a live-edge resume, so the Live Rewind filler lifecycle
            // must not see it.
            if (resumeGateActive) return
            if (isTimeshifting || isCatchup) return
            val ts = timeshift.get()
            if (ts.activeWriter == null) return
            if (playWhenReady) {
                // GH #62: a MediaSession resume (remote play/pause key, QS
                // card, notification, Bluetooth) after a LONG pause used to
                // resume the DIRECT pipeline at the edge of the player's own
                // read-ahead. The provider had usually killed that idle
                // connection during the pause, so the runway ran dry within
                // seconds and the terminal-error re-prime jumped to LIVE,
                // discarding the pause buffer the recorder kept for exactly
                // this moment. Mirror the chrome transport's long-pause
                // branch: past the same 6s threshold, switch onto the buffer
                // at the pause point (clamped into the ring). Short pauses
                // keep the untouched-pipeline resume. The chrome long-pause
                // path is unaffected: it enters timeshift BEFORE this
                // callback runs, so isTimeshifting already returned above.
                val pausedAt = mediaPauseWallMs
                mediaPauseWallMs = 0L
                val w = ts.activeWriter
                if (pausedAt > 0 &&
                    System.currentTimeMillis() - pausedAt > 6_000 &&
                    w != null && !w.closed &&
                    playTimeshift((pausedAt - 1_000).coerceAtLeast(w.tailWallMs))
                ) {
                    Log.i(TAG, "[REWIND] media-key long-pause resume -> buffer at pause point")
                } else {
                    ts.onLiveResumedAtEdge()
                }
            } else {
                // session2.txt 19:57:48.711: a "media-key long-pause resume"
                // fired with NO user pause. playWhenReady=false also arrives on
                // a terminal error and on every stop / re-prime, so the stamp
                // was set by the failure itself and the next resume read it as
                // a 29 s user pause and entered timeshift. Only a genuine pause
                // of a READY player with no error pending counts.
                if (player?.playbackState == Player.STATE_READY && !errorPending) {
                    mediaPauseWallMs = System.currentTimeMillis()
                }
                ts.onLivePaused()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            clearPauseStamp("player error")
            errorPending = true
            // Task #150: remember the failure in user-showable form for the
            // unavailable overlay (self-heals below may still recover; the
            // text only surfaces if the stream ends up flagged unavailable).
            // OTA/ATSC channels are MPEG-2 video, which many phones/tablets
            // have no decoder for (TV boxes generally do). Name the real
            // constraint and point at the documented server-side fix instead
            // of a raw decoder-init code (2026-09-01, mirrors the iOS card).
            val causeChain = generateSequence(error as Throwable?) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
            _lastErrorText.value = if (
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                causeChain.contains("mpeg2", ignoreCase = true)
            ) {
                "This channel broadcasts MPEG-2 video (over-the-air TV), which " +
                    "this device can't decode. See the OTA / HDHomeRun section of " +
                    "the AerioTV GitHub README for a Dispatcharr Stream Profile " +
                    "that fixes this."
            } else {
                error.cause?.message
                    ?.let { "${error.errorCodeName}: $it" } ?: error.errorCodeName
            }
            // GH #8: the forced-PCM sink produced no audio / failed to init on
            // some devices. Rebuild once with the stock context sink (which is
            // the path that works everywhere) and replay. A plain forceReload
            // would just hit the same dead sink.
            if (!audioSinkFallback &&
                (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
            ) {
                Log.w(TAG, "[AUDIO-HEAL] sink failed (${error.errorCodeName}); rebuilding with stock context sink")
            tracer.recover("audio sink fallback (${error.errorCodeName})")
                audioSinkFallback = true
                rebuildWithStockAudioAndReplay()
                return
            }
            // Dispatcharr connection limit (exact server signal, Direct Connect
            // only): show the notice and stop. No reload ladder, no walk.
            if (handleConnectionLimit(error)) return
            // Android companion to the frame-stall path: a terminal source/HTTP
            // error. Re-prime under the same cooldown + reload cap. If a LAN/WAN
            // failover hook is set (PlayerScreen mount), ask it to re-probe and
            // hand back a fresh URL so we don't just replay a dead-host
            // lastPlayUrl (iOS PlayerSession.failoverRetryCurrent).
            // Live Rewind: an error while playing the buffer is a local
            // condition (typically ring eviction after a very long pause),
            // never a network failover case. Recover INSIDE the buffer by
            // re-entering at the current tail; if the session is gone,
            // fall back to the live stream.
            if (isTimeshifting) {
                val ts = timeshift.get()
                val w = ts.activeWriter
                // GH #65: a recorded splice gap is a KNOWN, positioned
                // time skip, not a failure. The reader refuses to feed
                // across it (TimeshiftDiscontinuityException) precisely
                // so we can re-open the buffer AT the gap: the re-open
                // resets TsExtractor and flushes the renderers, so the
                // forward PTS jump becomes a fresh timeline instead of
                // the mid-stream AudioSink discontinuity that storms the
                // audio renderer. Bounded per gap so a pathological
                // marker cannot loop; repeats fall through to the
                // ordinary tail/live recovery below.
                var cause: Throwable? = error
                var gapEx: com.aeriotv.android.core.timeshift.TimeshiftDiscontinuityException? = null
                while (cause != null && gapEx == null) {
                    gapEx = cause as? com.aeriotv.android.core.timeshift.TimeshiftDiscontinuityException
                    cause = cause.cause
                }
                if (gapEx != null && w != null && !w.closed) {
                    val key = "${gapEx.segName}+${gapEx.byteOffset}"
                    gapHops = if (key == lastGapKey) gapHops + 1 else 1
                    lastGapKey = key
                    if (gapHops <= 2) {
                        Log.i(TAG, "[REWIND] crossing recorded splice gap at $key; re-entering past it")
                        isTimeshifting = false
                        if (playTimeshiftAt(gapEx.segName, gapEx.byteOffset, gapEx.resumeWallMs)) return
                    }
                }
                timeshiftErrorRetries += 1
                // Triage: "evicted behind me" (position fell off the ring)
                // recovers at the tail; "stalled at the frozen head" (the
                // filler died, head stopped advancing) must go LIVE, or
                // the tail bump replays the whole buffer into the same
                // stall. Cap tail retries so an empty/dead buffer cannot
                // loop error->tail->error forever on a frozen frame.
                val posWall = ts.state.value.baseWallMs + (player?.contentPosition ?: 0L)
                val nearHead = w != null && w.headWallMs - posWall < 10_000
                if (w != null && !w.closed && !nearHead && timeshiftErrorRetries <= 2) {
                    Log.w(TAG, "[REWIND] buffer error ${error.errorCodeName}; re-entering at tail (retry $timeshiftErrorRetries)")
                    isTimeshifting = false
                    playTimeshift(w.tailWallMs + 2_000)
                } else {
                    Log.w(TAG, "[REWIND] buffer error ${error.errorCodeName}; returning to live (nearHead=$nearHead retries=$timeshiftErrorRetries)")
                    goLive()
                }
                return
            }
            // Catch-up (task #148): a terminal error on an archive replay
            // must NOT re-prime lastPlayUrl - that would yank playback to
            // the LIVE channel mid-replay. Leave the player in its error
            // state; the unified player surface owns recovery/exit.
            if (isCatchup) {
                // Codec init at catch-up tune-in can transiently fail while
                // the previous stream's decoder (mini-player, a 4K live
                // channel) is still being released - MediaTek boxes report
                // ERROR_CODE_DECODING_RESOURCES_RECLAIMED. The live path
                // survives this via forceReload; give the archive replay the
                // same courtesy with a bounded re-tune before surfacing.
                val transientDecode =
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                val cu = lastCatchupUrl
                if (transientDecode && cu != null && catchupDecodeRetries < 2) {
                    catchupDecodeRetries += 1
                    Log.w(TAG, "[CATCHUP] ${error.errorCodeName} at tune-in; re-tuning (retry $catchupDecodeRetries)")
                    watchdogScope.launch {
                        delay(600)
                        withContext(Dispatchers.Main) {
                            if (isCatchup) {
                                catchupRetryPass = true
                                playCatchup(cu, lastCatchupTitle, lastCatchupSubtitle, lastCatchupArtworkUri)
                                catchupRetryPass = false
                            }
                        }
                    }
                    return
                }
                Log.w(TAG, "[CATCHUP] terminal error ${error.errorCodeName}; staying (no live re-prime)")
                // Task #148 milestone B: surface it - the unified TV player
                // renders its catch-up error overlay off these (a provider
                // that flags tv_archive but serves no archive 404s here).
                _streamUnavailable.value = true
                return
            }
            // Transient decoder death right at tune-in on live. session2.txt
            // 19:56:48.5: flipping from an AVC HD channel to a HEVC UHD one had
            // the MediaTek box reclaim c2.mtk.avc.decoder while c2.mtk.hevc.decoder
            // was being created, and 300 ms later the codec reported DEAD_OBJECT
            // in state STARTED - 200 ms after the 3840x2160 HEVC format landed.
            // That is a codec-handover race, not a dead stream, so re-priming the
            // SAME url once (the shape the catch-up path above already uses) gets
            // the channel instead of the terminal path's failover. Only inside the
            // first 3 s of the tune, and only once per tune.
            // GH #107: decoder DEATH, not a handover race. The MediaCodec
            // instance threw inside flush/init; an in-place reload here is what
            // pushed the Fire TV's ACodec into "State machine stuck" and cost
            // the whole session (every later tune: audio only, "Could not find
            // corresponding native window for surface", reboot to recover). Go
            // straight to the deepest teardown: release the player AND the
            // surface, then tune the same channel.
            if (isDecoderDeath(error, causeChain)) {
                val kind = if (causeChain.contains("flush", ignoreCase = true)) {
                    "decoder death (MediaCodec flush)"
                } else {
                    "decoder death (${error.errorCodeName})"
                }
                if (rebuildPlayerAndSurface(kind)) return
                // Budget spent: markStreamUnavailable already ran inside.
                return
            }
            val liveUrl = lastPlayUrl
            if (liveUrl != null && !decoderRetryUsed && isTransientDecoderError(error, causeChain)) {
                val sinceTune = SystemClock.elapsedRealtime() - streamPrimedAtMs
                if (sinceTune in 0..DECODER_RETRY_WINDOW_MS) {
                    decoderRetryUsed = true
                    Log.w(TAG, "[RECOVER] decoder ${error.errorCodeName} at +${sinceTune}ms after tune; retrying same url once")
                    tracer.recover("decoder ${error.errorCodeName} at +${sinceTune}ms after tune; retrying same url once")
                    watchdogScope.launch {
                        delay(600)
                        withContext(Dispatchers.Main) {
                            if (!isTimeshifting && !isCatchup) {
                                playUrl(
                                    liveUrl, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri,
                                    drmLicenseType = lastPlayDrmType, drmLicenseKey = lastPlayDrmKey,
                                )
                                // playUrl resets the per-tune state; keep the
                                // retry spent so a second failure runs the
                                // existing terminal path.
                                decoderRetryUsed = true
                            }
                        }
                    }
                    return
                }
            }
            // A Dispatcharr 503 the server already explained is owned by
            // handleLive503 (wait out a "Channel is stopping" teardown, or walk
            // to the next member stream at once). It normally decides on the
            // load error a moment earlier and this call is the de-duplicated
            // second sighting; when the load error never arrives (a 503 on a
            // path that reports only the terminal error) this is where it is
            // decided. Either way the generic reload ladder must stand down:
            // re-GETting the same url 15 ms later is what produced the instant
            // "Channel Unavailable ... Retrying in 4s" card.
            if (handleLive503(error)) return
            if (lastPlayUrl != null) {
                if (onTerminalErrorRebuildUrl != null) {
                    watchdogScope.launch {
                        val fresh = rebuildUrlForCurrentChannel()
                        if (!fresh.isNullOrBlank() && fresh != lastPlayUrl) {
                            Log.w(TAG, "[RETUNE] terminal error; re-priming onto reprobed url $fresh")
                            tracer.recover("terminal error; re-priming onto reprobed url")
                            withContext(Dispatchers.Main) {
                                playUrl(fresh, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri)
                            }
                        } else {
                            // Task #150: a terminal error with no reload slot
                            // (cooldown / attempt cap) used to strand the
                            // player IDLE on a silent black screen (repro:
                            // drop the network mid-stream - the second error
                            // lands inside the 5s cooldown and nothing ever
                            // fires again). Surface the unavailable card so
                            // its escalating auto-retry owns recovery.
                            withContext(Dispatchers.Main) {
                                if (!forceReload("error:${error.errorCodeName}")) markStreamUnavailable()
                            }
                        }
                    }
                } else {
                    if (!forceReload("error:${error.errorCodeName}")) markStreamUnavailable()
                }
            }
        }

        override fun onRenderedFirstFrame() {
            videoFrameRendered = true
            noFrameHealAttempts = 0
            // GH #107: video that has been painting steadily for a while means
            // the surface and codec are genuinely healthy again, so the rebuild
            // budget earns a fresh window. Guarded by elapsed time since the
            // last rebuild so a rebuild that paints one frame and re-freezes
            // cannot refill its own budget and loop.
            if (lastFullRebuildAtMs != 0L &&
                SystemClock.elapsedRealtime() - lastFullRebuildAtMs > FULL_REBUILD_WINDOW_MS / 3
            ) {
                fullRebuildAttempts = 0
                fullRebuildWindowStartMs = 0L
                lastFullRebuildAtMs = 0L
            }
            // The one line that lets a user log definitively separate "video
            // rendered" from "decoded but never painted". Once per prime, so
            // it's cheap enough for release builds.
            Log.i(TAG, "first video frame rendered ch=$currentChannelId (+${SystemClock.elapsedRealtime() - streamPrimedAtMs}ms)")
            if (isAdoptedLive && adoptedLiveTuneAtMs != 0L) {
                Log.i(
                    "AerioTV",
                    "[RETAIN] adopted live, no refetch ch=$currentChannelId " +
                        "firstFrame ${tracer.msSincePress()}ms after press " +
                        "(${SystemClock.elapsedRealtime() - adoptedLiveTuneAtMs}ms after start)",
                )
                adoptedLiveTuneAtMs = 0L
            }
            if (forceHlsTuneAtMs != 0L) {
                Log.i(
                    ForceHls.TAG,
                    "[FORCE-HLS] first frame ch=$currentChannelId " +
                        "${SystemClock.elapsedRealtime() - forceHlsTuneAtMs}ms after tune",
                )
                forceHlsTuneAtMs = 0L
            }
            tracer.onFirstFrame()
        }
    }

    /** elapsedRealtime of the last forced-HLS tune, 0 once its first frame logged. */
    @Volatile private var forceHlsTuneAtMs = 0L
    /** The resolved server session behind the playing token playlist. */
    @Volatile private var forceHlsSession: ForceHls.Session? = null
    /** Entry URL whose redirect could not be resolved; played directly once. */
    private var forceHlsResolveSkipUrl: String? = null
    private var forceHlsResolveGeneration = 0

    /** Logs the redirect target of a forced-HLS request (302 to /proxy/hls/...). */
    private val forceHlsRedirectListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            val requested = loadEventInfo.dataSpec.uri.toString()
            if (!ForceHls.isEntryUrl(requested)) return
            val landed = loadEventInfo.uri.toString()
            if (landed != requested) {
                Log.i(ForceHls.TAG, "[FORCE-HLS] 302 Location: $landed")
            } else {
                Log.i(ForceHls.TAG, "[FORCE-HLS] no redirect observed, served at $requested")
            }
        }
    }

    /**
     * Dynamic HTTP DataSource.Factory used ONLY by the player's MediaSource
     * factory, i.e. the Android Auto path where a MediaController calls
     * setMediaItems(uri) and the player resolves the source itself. It reads
     * [httpHeaders] fresh on every createDataSource so the active source's
     * Dispatcharr key rides along. The foreground path bypasses this entirely
     * (it calls player.setMediaSource(buildMediaSource(...)) directly), so this
     * factory never affects PlayerScreen playback.
     *
     * Parity with the foreground live fix (GH #32): Android Auto live TV is a
     * separate, thinner path that historically used [DefaultHttpDataSource] --
     * the same HttpURLConnection stack that can open the Dispatcharr raw-TS
     * proxy on Android 16 yet never deliver bytes (permanent BUFFERING, silent
     * car). The Auto browse tree is live-channels-only, so route it through the
     * same OkHttp client the foreground live path uses (consistent across OS
     * versions), and always send a real player User-Agent -- falling back to
     * [DEFAULT_PLAYBACK_USER_AGENT] when the active source supplies none, so
     * anti-restream WAFs don't drop the connection on the platform Dalvik UA.
     * Headers are still read fresh per createDataSource for server switches.
     * (DHU-verified 2026-07-14: Auto playback did not itself reproduce the
     * stall on an Android 16 Z Fold, but this closes the same latent gap the
     * foreground fix already covers.)
     */
    private val autoDataSourceFactory = DataSource.Factory {
        val h = httpHeaders
        val headerUa = h.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
        val f = OkHttpDataSource.Factory(liveHttpClient)
            .setUserAgent(okHttpSafeUserAgent(headerUa ?: DEFAULT_PLAYBACK_USER_AGENT))
        val nonUaHeaders = okHttpSafeHeaders(
            h.filterKeys { !it.equals("User-Agent", ignoreCase = true) },
        )
        if (nonUaHeaders.isNotEmpty()) f.setDefaultRequestProperties(nonUaHeaders)
        f.createDataSource()
    }

    /**
     * Return the active ExoPlayer, creating it once on first call.
     * The caller is expected to bind it to a PlayerView via
     * `playerView.player = holder.acquireOrCreate(...)`.
     */
    fun acquireOrCreate(
        context: Context,
    ): ExoPlayer {
        appContext = context.applicationContext
        val audioPassthrough = cachedAudioPassthrough || audioSinkFallback
        // Buffer floor comes from the pref-cache updated by the collector in init{}.
        val bufferFloorMs = cachedBufferFloorMs
        // Learned live start gate for THIS tune, stamped by playUrl. Clamped so a
        // corrupt stored value can never push the start gate past the ceiling.
        val startGateMs = gateKeyFor(
            desiredStartGateMs.coerceIn(LIVE_START_GATE_DEFAULT_MS, LIVE_START_GATE_MAX_MS),
            desiredForcedHls,
            forceHlsTargetOffsetMs,
        )
        // watchdogReloadEnabled is kept current by the autoRecoverFrozenStreams
        // collector launched in init{}; no blocking read needed here.
        player?.let { existing ->
            if (builtWithPassthrough == audioPassthrough &&
                builtWithBufferFloorMs == bufferFloorMs
            ) {
                // A changed learned start gate no longer rebuilds: the
                // release + build ran on the main thread before the new
                // stream was requested (Streamer guide tune with the mini
                // running: playUrl=3738). The running player's load control
                // switches gates instead, before the new source is primed.
                if (builtWithStartGateMs != startGateMs) {
                    Log.i(TAG, "[HOLDBACK] start gate $builtWithStartGateMs -> $startGateMs ms on the running player")
                    gateLoadControl?.requestGate(startGateMs)
                    builtWithStartGateMs = startGateMs
                    builtMaxBufferMs = maxBufferForKey(bufferFloorMs, startGateMs)
                }
                return existing
            }
            Log.i(TAG, "Player build pref changed (passthrough/buffer); rebuilding player")
            destroy()
        }
        Log.i(TAG, "Creating fresh ExoPlayer in holder")

        // RenderersFactory: enable SW fallback (Media3 equivalent of
        // mpv's hwdec-software-fallback). On the rare codec that fails
        // HW init the renderer transparently retries SW. The QTI HEVC-
        // in-TS bug we hit on libmpv is fixed at this layer: Media3's
        // MediaCodecRenderer pulls SPS/VPS/PPS out of in-band Annex-B
        // NALs before MediaCodec.configure, so we don't even need the
        // fallback for that case -- HW just works.
        // forceVideoCodecReinit: some Codec2 decoders (Exynos C2 h264 in a
        // GitHub user report) go video-dead when Media3 flushes and reuses
        // the codec across a channel switch: audio plays, screen stays
        // black. Re-initialising the video codec per switch is the path
        // that works everywhere.
        val renderersFactory = com.aeriotv.android.core.playback.aerioRenderersFactory(
            context,
            audioPassthrough,
            forceVideoCodecReinit = true,
        )

        // LoadControl: live-stream buffer durations. The ExoPlayer defaults
        // (50s) over-buffer for live and delay channel-tap response, but the
        // original tuning here was the OPPOSITE extreme and was the dominant
        // cause of the freezing/skipping on the Streamer:
        //   - bufferForPlaybackMs=500 started playback on ~0.5s of media, i.e.
        //     on a PARTIAL initial GOP of a freshly-joined Dispatcharr MPEG-TS
        //     stream. That surfaced as either a cold-start starve->reload (ch103)
        //     or a MediaTek HW H.264 CodecException on the truncated GOP (ch107),
        //     each recovered only by the 6s reload-watchdog = a visible freeze+skip.
        //   - min=2500/max=5000 kept too shallow a steady-state cushion, so the
        //     jittery ~realtime TS feed drained it to empty ~once a minute, the
        //     recurring mid-stream micro-stutter seen in a 5-min on-device watch.
        // This is the Android analog of the iOS v1.7.0 live-startup tuning
        // (demuxer-lavf-analyzeduration 1.5s / probesize 1MB). The two levers
        // are deliberately decoupled:
        //   - The STEADY cushion (min 4s / max 8s) is what suppresses the
        //     recurring mid-stream micro-stutter: it gives the jittery ~realtime
        //     TS feed real headroom instead of the old 2.5s that drained to empty
        //     ~once a minute. A 5-min on-device watch went from ~6 rebuffers to 1.
        //   - The START gate (bufferForPlaybackMs) governs only tap-to-motion
        //     latency. 500ms was far too eager (started on a partial GOP -> the
        //     cold-start starve->reload and the MediaTek decoder CodecException);
        //     2000ms locked a clean start but cost ~6-7s tap-to-motion on a slow
        //     Dispatcharr cold-upstream ramp. 1200ms is the chosen balance: ~2x
        //     the data of a half-GOP start, well past the 500ms failure point,
        //     while keeping cold channel-taps responsive. afterRebuffer 2000ms
        //     keeps mid-stream rebuffer recovery snappy.
        // (Multiview + VOD have their own LoadControls; this governs only the
        // single live player.)
        // The 4s floor stays: it is the measured minimum that suppresses the
        // micro-stutter described above. The Buffer Size ladder no longer
        // offers anything below it - Small, Default and Large all used to
        // collapse onto this same value, which is why a user switching
        // between them measured no difference at all (see BUFFER_OPTIONS).
        // The learned hold-back raises the START gate, so the steady bounds have
        // to stay above it: a bufferForPlaybackMs at or past minBufferMs leaves
        // the load control nothing to work with. min = gate + 4s keeps the same
        // 4 s of real cushion the tuning above describes.
        // (Bounds computed per gate by minBufferFor / liveMaxBufferFor.)
        val liveMaxBufferMs = maxBufferForKey(bufferFloorMs, startGateMs)
        // The post-stall resume gate can only hold for what the LoadControl will
        // actually keep buffered ahead (target is clamped to maxBufferMs - 1 s),
        // and minBufferMs * 2 is only ~10.4 s at the base start gate. A live
        // player therefore gets at least 14 s of max buffer so a 12 s gate is
        // reachable; the MIN bound is untouched, so steady-state behaviour and
        // the Buffer Size ladder are unchanged.
        val loadControl = GateSwitchingLoadControl(startGateMs) { gateKey, allocator ->
            val gateMs = gateOfKey(gateKey)
            val hls = isHlsGateKey(gateKey)
            if (hls) {
                Log.i(
                    ForceHls.TAG,
                    "[FORCE-HLS] load control min ${minBufferForKey(bufferFloorMs, gateKey)} ms, " +
                        "max ${maxBufferForKey(bufferFloorMs, gateKey)} ms, rebuffer resume ${hlsRebufferOfKey(gateKey)} ms",
                )
            }
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    /* minBufferMs = */ minBufferForKey(bufferFloorMs, gateKey),
                    // Real headroom between the bounds at EVERY rung. The old
                    // maxOf(8_000, minBufferMs) degenerated to min == max once the
                    // floor reached 8s, leaving the load control nothing to work
                    // with on precisely the setting chosen for poor networks.
                    /* maxBufferMs = */ maxBufferForKey(bufferFloorMs, gateKey),
                    /* bufferForPlaybackMs = */ gateMs,
                    /* bufferForPlaybackAfterRebufferMs = */ if (hls) hlsRebufferOfKey(gateKey) else 2_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
        gateLoadControl = loadControl

        val fresh = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            // Skip Intervals at build time. The media session wraps this
            // player in SkipIntervalsPlayer, which reads the live setting, so
            // a change between builds still reaches lock screen and Bluetooth
            // seek commands.
            .setSeekBackIncrementMs(com.aeriotv.android.core.ui.SkipIntervals.backMs)
            .setSeekForwardIncrementMs(com.aeriotv.android.core.ui.SkipIntervals.forwardMs)
            // Header-aware + TS-aware MediaSource factory for the Android Auto
            // path (a controller's setMediaItems(uri) -> the player resolves the
            // source itself). The foreground path bypasses this with
            // setMediaSource(buildMediaSource(...)), so this only governs Auto.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    autoDataSourceFactory,
                    DefaultExtractorsFactory().setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT),
                ),
            )
            // Request audio focus + declare media-usage attributes. WITHOUT
            // this, Android Auto shows the stream "playing" (the head-unit
            // timeline advances) but routes NO audio to the car: the player
            // decodes but never holds audio focus, so the car's audio system
            // won't play it (car report -- silent in Auto, and the audio
            // resumed on the phone the instant it was unplugged from Auto).
            // handleAudioFocus=true also ducks/pauses correctly on phone-side
            // interruptions. handleAudioBecomingNoisy (headphone unplug pause)
            // is a SEPARATE concern, not audio focus.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                PlaybackActivityTracker.playerCreated()
                addListener(LoggingPlayerListener)
                addListener(watchdogListener)
                // Always-on: network LOAD errors into the shareable log (GH #32).
                addAnalyticsListener(LoadErrorDiagnosticsListener())
                // Always-on tune/stall/feed tracer (tag AerioTrace).
                addAnalyticsListener(tracer.analyticsListener)
                // Developer Force HLS: log the 302 Location Media3 followed.
                addAnalyticsListener(forceHlsRedirectListener)
                // Always-on frame-pacing timer ([JUDDER] / [PERF] render=).
                // The single video-frame-metadata slot is shared with
                // DisplayFrameRateMatcher: PersistentExoWindow re-registers
                // this listener CHAINED in front of the matcher's when it
                // attaches, so whichever registers last carries both.
                setVideoFrameMetadataListener(tracer.frameMetadataListener())
                // Debug-only rich diagnostics firehose (codec / hwdec path,
                // input format changes, dropped frames, audio underruns) -- the
                // Android analog of iOS's libmpv log bridge. Read with
                // `adb logcat -s AerioPlayerDiag`.
                if (BuildConfig.DEBUG) addAnalyticsListener(DiagnosticAnalyticsListener)
                // Repeat off for live; setRepeatMode(REPEAT_MODE_ONE) is
                // a VOD concern.
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                // DisplayFrameRateMatcher is the SOLE owner of the surface
                // frame-rate vote. Media3's own MediaCodecVideoRenderer also
                // calls Surface.setFrameRate from the container-signaled
                // Format.frameRate (seamless-only), which would race and
                // overwrite the matcher's measured CHANGE_FRAME_RATE_ALWAYS
                // request. Dispatcharr TS rarely signals fps, but when it does
                // the two owners fight -- so turn Media3's off here.
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            }

        player = fresh
        _playerInstance.value = fresh
        tracer.tracedPlayer = fresh
        builtWithPassthrough = audioPassthrough
        builtWithBufferFloorMs = bufferFloorMs
        builtWithStartGateMs = startGateMs
        builtMaxBufferMs = liveMaxBufferMs
        startWatchdog()
        return fresh
    }

    /**
     * Build a MediaSource appropriate to the URL + apply the current
     * HTTP headers. The factory is rebuilt each time so the latest
     * headers (Dispatcharr API key, custom User-Agent) ride along.
     *
     * Optional metadata (channel name / program / logo) is attached
     * to the MediaItem so MediaSessionService can render its
     * notification + lock-screen art automatically. We mirror the
     * iOS NowPlayingManager fields here.
     */
    fun buildMediaSource(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
        drmLicenseType: String? = null,
        drmLicenseKey: String? = null,
    ): MediaSource {
        // Live Rewind: mirror the player's own bytes into the active
        // timeshift buffer (nil-safe; inert when no session is rolling).
        // Wrapping here means the tee survives LAN/WAN failover and the
        // stall-watchdog re-prime, both of which come back through
        // buildMediaSource with a fresh connection.
        val rawTs = isRawTsUrl(url)
        var dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
            httpDataSourceFactory(rawTs)
        if (rawTs) {
            dataSourceFactory = com.aeriotv.android.core.timeshift.TeeDataSource.Factory(
                dataSourceFactory,
            ) { timeshift.get().activeWriter }
        }
        // Byte-flow accounting for [TUNE] firstByte and the [FEED] lines. The
        // progressive TS load never "completes", so onLoadCompleted alone
        // would never see a byte on the live path.
        dataSourceFactory = tracer.wrapDataSourceFactory(dataSourceFactory)

        // Force-route raw .ts URLs through ProgressiveMediaSource +
        // TsExtractor. Without this, DefaultMediaSourceFactory looks at
        // the file extension and might mis-identify or fall through to
        // a generic path that doesn't know how to extract HEVC SPS/VPS/
        // PPS from MPEG-TS in-band NAL units.
        //
        // Dispatcharr serves channels as
        //   http://<host>:<port>/proxy/ts/stream/<uuid>
        // which has no extension. We detect raw TS by URL shape AND let
        // DefaultMediaSourceFactory handle .m3u8 (HLS) / .mpd (DASH) /
        // .mp4 (progressive) on its own.
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUri)
            .build()
        // GH #27: encrypted-DASH channels signal keys via #KODIPROP. A
        // license-SERVER URL rides the MediaItem's DrmConfiguration (every
        // media source factory's default DrmSessionManagerProvider honors
        // it); a local ClearKey "kid:key" hex pair instead needs an explicit
        // session manager fed the JWK JSON via LocalMediaDrmCallback.
        val drmUuid: java.util.UUID? = drmLicenseType?.lowercase()?.let { t ->
            when {
                "clearkey" in t -> C.CLEARKEY_UUID
                "widevine" in t -> C.WIDEVINE_UUID
                "playready" in t -> C.PLAYREADY_UUID
                else -> null
            }
        }
        val licenseIsUrl = drmLicenseKey?.startsWith("http", ignoreCase = true) == true
        val drmConfiguration: MediaItem.DrmConfiguration? =
            if (drmUuid != null && drmLicenseKey != null && licenseIsUrl) {
                MediaItem.DrmConfiguration.Builder(drmUuid)
                    .setLicenseUri(drmLicenseKey)
                    .setMultiSession(true)
                    .build()
            } else null
        val localClearKeyJwk: String? =
            if (drmUuid == C.CLEARKEY_UUID && drmLicenseKey != null && !licenseIsUrl) {
                clearKeyJwk(drmLicenseKey)
            } else null
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(title.orEmpty().ifBlank { url })
            .setMediaMetadata(mediaMetadata)
        if (drmConfiguration != null) mediaItemBuilder.setDrmConfiguration(drmConfiguration)
        if (ForceHls.isForcedHlsUrl(url)) {
            // Overrides EXT-X-START (-5 s): the proxy delivers in 5 to 6 s
            // bursts, so a 5 s join drains to nothing. No rate nudging here.
            val target = forceHlsTargetOffsetMs
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(target.toLong())
                    .setMinOffsetMs(FORCE_HLS_MIN_OFFSET_MS)
                    .setMaxOffsetMs(FORCE_HLS_MAX_OFFSET_MS)
                    .setMinPlaybackSpeed(1.0f)
                    .setMaxPlaybackSpeed(1.0f)
                    .build(),
            )
            Log.i(ForceHls.TAG, "[FORCE-HLS] live offset target $target ms")
        }
        val mediaItem = mediaItemBuilder.build()
        _pipelineLabel.value = pipelineLabelFor(url, drm = drmUuid != null && drmLicenseKey != null)
        return when {
            isRawTsUrl(url) -> {
                // SINGLE_PMT is what HlsMediaSource uses internally and
                // what nearly every IPTV provider delivers: one program,
                // one PMT, one video PID, one or more audio PIDs.
                // MULTI_PMT is for mux'd transports with sibling programs
                // (BBC HD vs SD on the same TS) which Dispatcharr / Xtream
                // proxies never deliver.
                //
                // No additional FLAG_* on Media3 1.4 -- the only one
                // available is FLAG_EMIT_RAW_SUBTITLE_DATA which we leave
                // off (subtitle handling is task #66 and the parser
                // factory route is cleaner anyway).
                // TS-ONLY extractor factory (no container sniff). ProgressiveMediaSource's
                // BundledExtractorsAdapter skips the sniff entirely when exactly one
                // extractor is supplied. Sniffing the default 21 extractors against the
                // first bytes of /proxy/ts/stream intermittently fails when the proxy
                // starts mid-packet (not 0x47-aligned) -> UnrecognizedInputFormatException
                // -> forceReload -> the cold start is doubled. TsExtractor scans for the
                // sync byte itself, so a single forced TsExtractor handles the unaligned
                // join with no sniff and no reload. We still source it from
                // DefaultExtractorsFactory(MODE_SINGLE_PMT) so its TsExtractor config is
                // identical to before; we just hand ProgressiveMediaSource that one extractor.
                ProgressiveMediaSource.Factory(dataSourceFactory, tsOnlyExtractorsFactory())
                    // A Dispatcharr 503 must reach our own handler on the FIRST
                    // answer instead of being re-GET by Media3's retry ladder.
                    .setLoadErrorHandlingPolicy(Live503LoadErrorPolicy())
                    .createMediaSource(mediaItem)
            }
            url.endsWith(".m3u8", ignoreCase = true) || ForceHls.isForcedHlsUrl(url) -> {
                if (ForceHls.isForcedHlsUrl(url)) {
                    Log.i(ForceHls.TAG, "[FORCE-HLS] media source: HlsMediaSource (TS extractor, tee, switch skip skipped)")
                }
                HlsMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(Live503LoadErrorPolicy())
                    .createMediaSource(mediaItem)
            }
            else -> {
                val factory = DefaultMediaSourceFactory(dataSourceFactory)
                if (localClearKeyJwk != null) {
                    val manager = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(
                            C.CLEARKEY_UUID,
                            androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER,
                        )
                        .setMultiSession(true)
                        .build(
                            androidx.media3.exoplayer.drm.LocalMediaDrmCallback(
                                localClearKeyJwk.toByteArray(Charsets.UTF_8),
                            ),
                        )
                    factory.setDrmSessionManagerProvider { manager }
                }
                factory.createMediaSource(mediaItem)
            }
        }
    }

    /** GH #27: ClearKey "kid:key" hex pair -> the JSON Web Key response the
     *  framework ClearKey CDM accepts (base64url, no padding). Null when the
     *  value doesn't parse as a hex pair (the caller then plays without DRM
     *  and the decoder surfaces the real error). */
    private fun clearKeyJwk(pair: String): String? {
        val parts = pair.split(":", limit = 2)
        if (parts.size != 2) return null
        fun hexToB64Url(hex: String): String? {
            val clean = hex.trim()
            if (clean.isEmpty() || clean.length % 2 != 0) return null
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(clean[i * 2], 16)
                val lo = Character.digit(clean[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return android.util.Base64.encodeToString(
                out,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
        }
        val kid = hexToB64Url(parts[0]) ?: return null
        val k = hexToB64Url(parts[1]) ?: return null
        return """{"keys":[{"kty":"oct","kid":"$kid","k":"$k"}],"type":"temporary"}"""
    }

    /** Live extractor factory shared by the raw-stream path and the
     *  timeshift buffer reader. Two candidates, in order:
     *
     *  1. FragmentedMp4Extractor with its normal (strict, reliable) sniff:
     *     Dispatcharr's proxy can be set to fMP4 OUTPUT, and the old TS-only
     *     factory force-fed those bytes to TsExtractor, which died in
     *     SectionReader with an IllegalArgumentException (Logan 2026-08-06,
     *     "why doesn't Android support fMP4").
     *  2. A TsExtractor whose sniff ALWAYS accepts - the terminal fallback.
     *     This preserves the original no-sniff TS guarantee: a proxy join
     *     that starts mid-packet (not 0x47-aligned) used to fail the sniff
     *     and double the cold start; TsExtractor.read scans for the sync
     *     byte itself, so accepting unconditionally handles the unaligned
     *     join with no reload, exactly as the old single-extractor path did.
     *
     *  Both are sourced from DefaultExtractorsFactory so their configuration
     *  matches what the default pipeline would build.
     *
     *  The TsExtractor is additionally wrapped in ContinuousTsExtractor, which
     *  rebases sample timestamps when Dispatcharr swaps the channel's SOURCE
     *  stream on this same connection: MODE_SINGLE_PMT only unwraps the 33-bit
     *  roll, so the backup provider's unrelated PTS used to leave audio and
     *  queued video on different timelines (video held as "too early", or a
     *  31 min jump, plus the AudioSink discontinuity error). */
    private fun tsOnlyExtractorsFactory(): ExtractorsFactory = ExtractorsFactory {
        val all: Array<Extractor> = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            .createExtractors()
        val ts: Extractor? = all.firstOrNull { it is TsExtractor }
        val fmp4: Extractor? = all.firstOrNull {
            it is androidx.media3.extractor.mp4.FragmentedMp4Extractor
        }
        if (ts == null) return@ExtractorsFactory all
        val continuous: Extractor = ContinuousTsExtractor(ts)
        buildList {
            fmp4?.let { add(it) }
            add(object : Extractor by continuous {
                override fun sniff(
                    input: androidx.media3.extractor.ExtractorInput,
                ): Boolean = true
            })
        }.toTypedArray()
    }

    // MARK Live Rewind (task #143)

    /** True while playback runs from the local timeshift buffer instead of
     *  the direct live stream. Gates the stall watchdog and the LAN/WAN
     *  terminal-error rebuild, both of which would otherwise yank playback
     *  back to the live URL mid-rewind. */
    @Volatile
    var isTimeshifting = false
        private set

    /**
     * Switch the shared player onto the local timeshift buffer starting at
     * [fromWallMs] (wall-clock). The live tee keeps rolling: the buffer
     * continues to grow while the user is paused or rewound, exactly like
     * a cable DVR. Returns false when no buffer session is active.
     */
    /** Consecutive timeshift-error recoveries this rewind stint; reset on
     *  every fresh direct tune. Caps the error->tail retry loop. */
    private var timeshiftErrorRetries = 0

    /** GH #65: last splice-gap marker hopped and how many consecutive
     *  times, so a pathological marker cannot loop the gap re-open. */
    private var lastGapKey: String? = null
    private var gapHops = 0

    /** Wall-clock of the last MediaSession pause on direct live (GH #62).
     *  The watchdog listener's resume branch uses it to mirror the chrome
     *  transport's long-pause switch onto the rewind buffer. Cleared on
     *  every resume; a chrome pause ALSO stamps it harmlessly (the chrome
     *  resume enters timeshift first, so the callback never consumes it). */
    @Volatile private var mediaPauseWallMs = 0L

    /** True between a player error and the next prime, so the playWhenReady=false
     *  that rides along with the failure is not mistaken for a user pause
     *  (session2.txt 19:57:48 phantom long-pause resume). */
    @Volatile private var errorPending = false

    /** Clear the pause stamp whenever playback is (re-)primed or fails; both
     *  paths drive playWhenReady themselves and neither is a user pause. */
    private fun clearPauseStamp(reason: String) {
        errorPending = false
        if (mediaPauseWallMs == 0L) return
        mediaPauseWallMs = 0L
        Log.d(TAG, "[REWIND] pause stamp cleared ($reason)")
    }

    /** Live Rewind can only buffer what the tee mirrors: raw MPEG-TS.
     *  PlayerScreen gates session start on this so HLS/DASH live channels
     *  never show a transport over a permanently empty buffer. */
    fun canBufferLiveRewind(url: String): Boolean = isRawTsUrl(url)

    fun playTimeshift(fromWallMs: Long): Boolean {
        val p = player ?: return false
        val ts = timeshift.get()
        if (ts.activeWriter == null) return false
        isTimeshifting = true
        isAdoptedLive = false
        val factory = com.aeriotv.android.core.timeshift.TimeshiftDataSource.Factory { ts.activeWriter }
        val item = MediaItem.Builder()
            .setUri(com.aeriotv.android.core.timeshift.TimeshiftDataSource.uri(fromWallMs))
            .setMediaId("live-rewind")
            .build()
        val source = ProgressiveMediaSource.Factory(factory, tsOnlyExtractorsFactory())
            .createMediaSource(item)
        _pipelineLabel.value = "ExoPlayer · Rewind TS"
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        ts.onEnterTimeshift(fromWallMs)
        Log.i(TAG, "[REWIND] entered timeshift at $fromWallMs")
        return true
    }

    /**
     * GH #65: re-enter the buffer at an EXACT byte position; used to hop
     * a recorded splice gap. A wall-time entry interpolates within the
     * segment and could land back BEFORE the gap byte, re-throwing the
     * same discontinuity forever; the byte-addressed URI opens exactly
     * at the first post-gap byte (and the reader knows not to re-fire
     * markers at or before it).
     */
    private fun playTimeshiftAt(segName: String, byteOffset: Long, resumeWallMs: Long): Boolean {
        val p = player ?: return false
        val ts = timeshift.get()
        if (ts.activeWriter == null) return false
        isTimeshifting = true
        isAdoptedLive = false
        val factory = com.aeriotv.android.core.timeshift.TimeshiftDataSource.Factory { ts.activeWriter }
        val item = MediaItem.Builder()
            .setUri(
                com.aeriotv.android.core.timeshift.TimeshiftDataSource.uriAt(
                    segName, byteOffset, resumeWallMs,
                ),
            )
            .setMediaId("live-rewind")
            .build()
        val source = ProgressiveMediaSource.Factory(factory, tsOnlyExtractorsFactory())
            .createMediaSource(item)
        _pipelineLabel.value = "ExoPlayer · Rewind TS"
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        ts.onEnterTimeshift(resumeWallMs)
        Log.i(TAG, "[REWIND] entered timeshift at $segName+$byteOffset (wall $resumeWallMs)")
        return true
    }

    /**
     * True while the player reads a kept channel's buffer at its live edge,
     * fed by that channel's own kept connection ([playRetainedLive]). Holder
     * gates treat it like timeshift (no direct-stream watchdog), but the UI
     * reads it as live. Cleared by any real rewind, Go Live, re-prime or stop.
     */
    @Volatile
    var isAdoptedLive = false
        private set

    /** Wall ms the adopted-live tune started, for the first-frame log. */
    private var adoptedLiveTuneAtMs = 0L

    /**
     * Keep Recent Channels Live re-tune with NO new server connection (Apple
     * parity: the kept remuxer's live window resumes with no new ingest).
     * The kept channel's own fill connection keeps appending to [writer];
     * the player reads that buffer from [ADOPT_LIVE_CUSHION_MS] behind its
     * head and follows it at 1x. The cushion is wider than Dispatcharr's
     * 8 to 9.5 s delivery bursts so the reader never catches the write head
     * between bursts. All bytes are local, so first frame needs no network.
     */
    fun playRetainedLive(
        writer: com.aeriotv.android.core.timeshift.TimeshiftWriter,
        url: String,
        title: String?,
        subtitle: String?,
        artworkUri: android.net.Uri?,
        channelId: String,
    ): Boolean {
        if (writer.closed || writer.headWallMs <= writer.tailWallMs) return false
        val p = appContext?.let { acquireOrCreate(it) } ?: player ?: return false
        val ts = timeshift.get()
        val from = (writer.headWallMs - ADOPT_LIVE_CUSHION_MS).coerceAtLeast(writer.tailWallMs)
        isCatchup = false
        timeshiftErrorRetries = 0
        lastGapKey = null
        gapHops = 0
        // The direct URL stays the channel's, so Go Live, a feed end and the
        // failover hooks re-tune the right stream.
        lastPlayUrl = url
        lastPlayTitle = title
        lastPlaySubtitle = subtitle
        lastPlayArtworkUri = artworkUri
        lastPlayDrmType = null
        lastPlayDrmKey = null
        currentChannelIdForRebuild = channelId
        currentChannelId = channelId
        clearPauseStamp("adopted live")
        resetWatchdogStateForNewStream()
        setVideoTrackEnabled(!remoteAudioOnly)
        liveFailover.disarm()
        tracer.markTuneStart(title, "live")
        primeGeneration += 1
        resetForceHlsCushion("prime")
        isTimeshifting = true
        isAdoptedLive = true
        adoptedLiveTuneAtMs = SystemClock.elapsedRealtime()
        ts.onAdoptedFillEnded = { id ->
            mainHandler.post {
                if (isAdoptedLive && currentChannelId == id) {
                    Log.i(TAG, "[RETAIN] kept feed ended under adopted playback -> direct tune")
                    goLive()
                }
            }
        }
        val factory = com.aeriotv.android.core.timeshift.TimeshiftDataSource.Factory {
            ts.activeWriter?.takeIf { it === writer } ?: writer
        }
        val item = MediaItem.Builder()
            .setUri(com.aeriotv.android.core.timeshift.TimeshiftDataSource.uri(from))
            .setMediaId("live-rewind")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setDisplayTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artworkUri)
                    .build(),
            )
            .build()
        val source = ProgressiveMediaSource.Factory(factory, tsOnlyExtractorsFactory())
            .createMediaSource(item)
        _pipelineLabel.value = "ExoPlayer · Kept Live TS"
        val staleCalls = takeLiveCallTrackers()
        p.setMediaSource(source)
        retireLiveCalls(p, staleCalls)
        p.prepare()
        p.playWhenReady = true
        Log.i(
            TAG,
            "[RETAIN] adopted live start ch=$title from=${writer.headWallMs - from}ms behind head, " +
                "buffer ${(writer.headWallMs - writer.tailWallMs) / 1000}s",
        )
        return true
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Return to the live edge by re-tuning the DIRECT live stream. This blacks the
     *  screen ~1s while it re-primes (same cost as a channel tune) but it is CORRECT:
     *  a "smooth" seek to the buffer head instead STARVES -- you can only play as far
     *  as the recorder has written (~1x realtime), so there is no buffer-ahead cushion
     *  at the edge and the player constantly catches the write head and re-buffers
     *  (device: constant frame flashing, GH #33 2026-07-15). The direct stream pulls
     *  its own buffer-ahead off the live feed, so it plays smoothly at the edge. A
     *  truly smooth go-live would need a background direct re-prime + seamless swap. */
    fun goLive() {
        if (!isTimeshifting) return
        isTimeshifting = false
        isAdoptedLive = false
        timeshift.get().onGoLive()
        val url = lastPlayUrl ?: return
        Log.i(TAG, "[REWIND] go live -> re-tune direct stream")
        playUrl(url, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri)
    }

    /** True when playback is at the live edge: on the direct stream, or (in timeshift
     *  mode) with the playhead within 5s of the buffer head. Lets a smooth
     *  go-live-to-buffer-head still read as "live" for the LIVE indicators. */
    fun isAtLiveEdge(): Boolean {
        if (!isTimeshifting || isAdoptedLive) return true
        val w = rewindWindow() ?: return true
        val pos = currentRewindWallMs() ?: return true
        return pos >= w[1] - 5_000
    }

    // GH #33 cast rewind: read-only accessors so the cast RECEIVER can drive the
    // SAME rewind buffer the on-TV chrome scrubs (via playTimeshift/goLive) and
    // report the window/playhead back to the phone remote, without the receiver
    // needing its own TimeshiftController reference. Reads run on the ExoPlayer
    // main thread (the cast control listener's Main.immediate scope).

    /** Current wall-clock playhead while rewound, or null at the live edge / no
     *  session. Mirrors the on-TV formula (baseWallMs + raw player position). */
    fun currentRewindWallMs(): Long? =
        if (isTimeshifting) {
            timeshift.get().state.value.baseWallMs + (player?.currentPosition ?: 0L)
        } else {
            null
        }

    /** The rewind window as [tailWallMs, headWallMs], read FRESH off the active
     *  writer (the non-lagging source the on-TV commitScrubWall also reads), or
     *  null when no rewind session is rolling. */
    fun rewindWindow(): LongArray? =
        timeshift.get().activeWriter?.let { longArrayOf(it.tailWallMs, it.headWallMs) }

    /** True while the shared player runs a catch-up (server archive)
     *  replay inside the unified live player (task #148). Gates the same
     *  live-only machinery [isTimeshifting] gates - the stall watchdog,
     *  forceReload, and the terminal-error live re-prime would all yank
     *  playback back to the LIVE channel mid-replay. */
    @Volatile
    var isCatchup = false
        private set

    // Catch-up decoder-reclaim self-heal state: the last playCatchup args so
    // onPlayerError can replay the exact tune, a bounded retry counter, and a
    // flag so the retry replay doesn't reset its own counter.
    private var lastCatchupUrl: String? = null
    private var lastCatchupTitle: String? = null
    private var lastCatchupSubtitle: String? = null
    private var lastCatchupArtworkUri: android.net.Uri? = null
    private var catchupDecodeRetries = 0
    private var catchupRetryPass = false

    /**
     * Tune the shared player onto a catch-up timeshift URL. Raw MPEG-TS,
     * unseekable by design (Dispatcharr serves an estimated-length
     * stream) - seeks are URL re-tunes handled by the caller via
     * CatchupUrlBuilder.rebuildForOffset, mirroring VODPlayerScreen's
     * model. Deliberately NO tee (an archive replay must never fill the
     * Live Rewind buffer) and NO watchdog/failover. `lastPlayUrl` is left
     * pointing at the live channel so exiting catch-up can re-tune it.
     */
    fun playCatchup(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
    ): Boolean {
        val p = player ?: appContext?.let { acquireOrCreate(it) } ?: return false
        if (isTimeshifting) {
            isTimeshifting = false
            timeshift.get().onGoLive()
        }
        isCatchup = true
        timeshiftErrorRetries = 0
        lastGapKey = null
        gapHops = 0
        if (!catchupRetryPass) catchupDecodeRetries = 0
        lastCatchupUrl = url
        lastCatchupTitle = title
        lastCatchupSubtitle = subtitle
        lastCatchupArtworkUri = artworkUri
        resetWatchdogStateForNewStream()
        setVideoTrackEnabled(!remoteAudioOnly)
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUri)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId("catchup")
            .setMediaMetadata(mediaMetadata)
            .build()
        tracer.markTuneStart(title, "catchup")
        _pipelineLabel.value = "ExoPlayer · Catch-up TS"
        val staleCalls = takeLiveCallTrackers()
        val source = ProgressiveMediaSource.Factory(
            tracer.wrapDataSourceFactory(httpDataSourceFactory(isLive = true)),
            tsOnlyExtractorsFactory(),
        )
            // A Dispatcharr connection-limit refusal is shown, never re-GET.
            .setLoadErrorHandlingPolicy(DispatcharrConnectionLimit.LoadErrorPolicy())
            .createMediaSource(mediaItem)
        p.setMediaSource(source)
        retireLiveCalls(p, staleCalls)
        p.prepare()
        p.playWhenReady = true
        Log.i(TAG, "[CATCHUP] tuned archive replay")
        return true
    }

    /**
     * Set the media item + start loading. Equivalent of MPV's
     * mpv.command("loadfile", url). Pass [title] / [subtitle] /
     * [artworkUri] for the MediaSession notification + lock-screen
     * art.
     */
    @Suppress("NAME_SHADOWING")
    fun playUrl(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
        // GH #27: #KODIPROP DRM signalling for encrypted DASH channels
        // (license_type + license_key). Null for everything else.
        drmLicenseType: String? = null,
        drmLicenseKey: String? = null,
        // Channel id of the stream being primed (live tunes pass it). Stamped
        // onto currentChannelIdForRebuild so the terminal-error rebuild hook is
        // always asked about the channel actually playing (session2.txt
        // 19:56:49 wrong-channel re-prime). Internal re-primes pass null and
        // keep the existing id.
        channelId: String? = null,
    ) {
        // Self-heal: a channel tap can land before the persistent window's
        // factory ran, or after destroy() released the instance. Swallowing
        // the call here left the screen dead until the user picked a
        // DIFFERENT channel (PlayerScreen stamps currentChannelId after this
        // call, so re-selecting the same one was a no-op).
        // Learned live start buffer. ONLY live tunes get a raised gate: a VOD,
        // catch-up or DVR source is a seekable file served as fast as the link
        // allows, so the bursty-feed problem this solves does not exist there
        // and a deeper gate would only slow the open.
        // Developer Force HLS: Dispatcharr live only (path match); a re-prime of
        // an already rewritten url is left as is (never double-added).
        val url = if (PlaybackTracer.urlKind(url) == "live") {
            ForceHls.apply(url, "live tune")
        } else url
        // Developer Force HLS: resolve the entry 302 ONCE off the main thread
        // and re-enter with the token playlist. Media3 reloads a media
        // playlist from the URL it was given, so handing it the entry URL
        // would mint a new server client every target duration.
        if (ForceHls.isEntryUrl(url) && forceHlsResolveSkipUrl != url) {
            val hdrs = httpHeaders
            val gen = ++forceHlsResolveGeneration
            forceHlsTuneAtMs = SystemClock.elapsedRealtime()
            prefScope.launch {
                val t0 = SystemClock.elapsedRealtime()
                val session = ForceHls.resolveSession(url, forceHlsRequestHeaders(hdrs))
                withContext(Dispatchers.Main) {
                    if (gen != forceHlsResolveGeneration) return@withContext
                    val ms = SystemClock.elapsedRealtime() - t0
                    if (session != null) {
                        forceHlsSession = session
                        Log.i(ForceHls.TAG, "[FORCE-HLS] resolved in ${ms}ms; playing token playlist")
                        playUrl(session.playlistUrl, title, subtitle, artworkUri, drmLicenseType, drmLicenseKey, channelId)
                    } else {
                        Log.w(ForceHls.TAG, "[FORCE-HLS] could not resolve the entry redirect in ${ms}ms; opening the entry URL directly")
                        forceHlsResolveSkipUrl = url
                        playUrl(url, title, subtitle, artworkUri, drmLicenseType, drmLicenseKey, channelId)
                    }
                }
            }
            return
        }
        forceHlsResolveSkipUrl = null
        val forcedHls = ForceHls.isForcedHlsUrl(url)
        val kind = PlaybackTracer.urlKind(url)
        val effectiveChannelId = channelId ?: currentChannelIdForRebuild
        // A learned hold-back expires after 30 minutes so one bad session cannot
        // pin a channel's start buffer forever (Logan 2026-09-12). A missing
        // timestamp (prefs written before the stamp existed) reads as expired.
        val learnedEntry =
            if (kind == "live") effectiveChannelId?.let { cachedLiveStartBuffers[it] } else null
        val learnedAgeMs =
            learnedEntry?.let { System.currentTimeMillis() - it.learnedAtMs } ?: 0L
        val learnedExpired = learnedEntry != null && learnedAgeMs > HOLDBACK_LEARNED_TTL_MS
        if (learnedExpired && learnedEntry != null) {
            Log.i(
                TAG,
                "[HOLDBACK] learned ${learnedEntry.ms}ms expired " +
                    "(age ${learnedAgeMs / 60_000L}m), using base $LIVE_START_GATE_DEFAULT_MS ms",
            )
        }
        val learnedGateMs = if (learnedExpired) 0 else learnedEntry?.ms ?: 0
        if (!forcedHls && !learnedExpired && learnedEntry != null && learnedEntry.ms > 0) {
            restampLearnedOnUse(effectiveChannelId!!, learnedEntry)
        }
        // Forced HLS skips the learned TS start gate: the playlist's own
        // target duration governs the start, not the TS burst shape.
        // Forced HLS starts on one Dispatcharr burst plus margin so the first
        // 5 to 6 s burst gap does not drain a 1.2 s start buffer.
        val startGateMs = if (forcedHls) FORCE_HLS_START_GATE_MS
        else maxOf(LIVE_START_GATE_DEFAULT_MS, learnedGateMs)
            .coerceAtMost(LIVE_START_GATE_MAX_MS)
        if (forcedHls) {
            Log.i(ForceHls.TAG, "[FORCE-HLS] learned TS start gate skipped")
            Log.i(ForceHls.TAG, "[FORCE-HLS] start gate $startGateMs ms")
            forceHlsTargetOffsetMs = hlsLiveOffsetFor(effectiveChannelId)
            // Fast start, then spend the cushion at the first stall: resume
            // only once a full target offset is buffered again.
            Log.i(ForceHls.TAG, "[FORCE-HLS] rebuffer gate $forceHlsTargetOffsetMs ms")
            if (forceHlsTuneAtMs == 0L) forceHlsTuneAtMs = SystemClock.elapsedRealtime()
        } else {
            forceHlsTuneAtMs = 0L
        }
        Log.i(TAG, "[HOLDBACK] ch=${title ?: "?"} start gate $startGateMs ms (learned $learnedGateMs ms)")
        desiredStartGateMs = startGateMs
        desiredForcedHls = forcedHls
        if (kind == "live") lastTuneWasForcedHls = forcedHls
        // acquireOrCreate rebuilds when the gate changed (DefaultLoadControl is
        // fixed at build time). Doing it HERE, before the source is primed, is
        // what keeps a raised gate out of a running playback.
        val p = appContext?.let { acquireOrCreate(it) } ?: player ?: run {
            Log.w(TAG, "playUrl called before acquireOrCreate and no context cached")
            return
        }
        // Remember the args so the stall watchdog can re-prime the same stream;
        // reset its state for this fresh stream.
        if (isTimeshifting) {
            // A re-prime path (follow-poller, LAN/WAN flip, watchdog) can
            // land here mid-rewind; without this the controller stayed in
            // timeshifting=true and the independent filler streamed the
            // full live feed for the rest of the session.
            timeshift.get().onGoLive()
        }
        isTimeshifting = false
        isAdoptedLive = false
        isCatchup = false
        timeshiftErrorRetries = 0
        lastGapKey = null
        gapHops = 0
        lastPlayUrl = url
        // A fresh prime restarts the repeat-stall window; the rejoin cooldown
        // deliberately survives it, since a rejoin IS a re-prime.
        lastLiveUnderrunAtMs = 0L
        lastPlayTitle = title
        lastPlaySubtitle = subtitle
        lastPlayArtworkUri = artworkUri
        lastPlayDrmType = drmLicenseType
        lastPlayDrmKey = drmLicenseKey
        // Restamped from effectiveChannelId: a gate rebuild above goes through
        // destroy(), which clears currentChannelIdForRebuild, so an internal
        // re-prime (channelId == null) would otherwise lose the id the
        // terminal-error rebuild hook needs.
        // Both ids: [STALL] and the other reporters read currentChannelId, and a
        // failover / unavailable recovery re-primes through here after stop()
        // nulled it, which is why every stall line after a recovery said
        // ch=null (Streamer 2026-09-14, 13:54:43 onward).
        effectiveChannelId?.let {
            currentChannelIdForRebuild = it
            currentChannelId = it
        }
        clearPauseStamp("re-prime")
        // A caller-supplied channel id is a real tune (channel change, user
        // Retry): the clean-end reconnect budget starts over.
        if (channelId != null) resetCleanEndBudget()
        if (kind == "live") liveTuneWallSec = System.currentTimeMillis() / 1000.0
        resetWatchdogStateForNewStream()
        // watchdogReloadEnabled is kept current by the collector in init{}; the
        // cached value reflects the latest pref without blocking the main thread.
        // Foreground playback wants video; re-enable it in case an Android Auto
        // session previously dropped the video track on this shared player --
        // UNLESS a companion remote explicitly asked for Audio Only, which a
        // watchdog/poller re-prime must not undo.
        setVideoTrackEnabled(!remoteAudioOnly)
        tracer.markTuneStart(title, kind)
        primeGeneration += 1
        resetForceHlsCushion("prime")
        noteSourceOpen(url, effectiveChannelId)
        val staleCalls = takeLiveCallTrackers()
        val source = wrapForSwitchSkip(
            url, buildMediaSource(url, title, subtitle, artworkUri, drmLicenseType, drmLicenseKey),
        )
        p.setMediaSource(source)
        retireLiveCalls(p, staleCalls)
        p.prepare()
        p.playWhenReady = true
        // Arm the silent-start deadline for live only. A caller-supplied
        // channelId IS a user-initiated tune (internal re-primes pass null and
        // keep the walk's tried set).
        if (kind == "live") {
            liveFailover.onLiveTune(effectiveChannelId, title, userInitiated = channelId != null)
        } else {
            liveFailover.disarm()
        }
    }

    private fun httpDataSourceFactory(isLive: Boolean = false): DataSource.Factory {
        // GH #32: live (raw-TS Dispatcharr proxy) playback goes through OkHttp,
        // not Media3's DefaultHttpDataSource. On Android 16 the HttpURLConnection
        // that DefaultHttpDataSource wraps can open the connection to the chunked
        // TS proxy but never deliver any bytes -- ExoPlayer sits in BUFFERING with
        // no error and the picture stays black. A working Android 14 device runs
        // the identical code and renders frames in ~2s, so it's an OS-level
        // HttpURLConnection quirk. OkHttp is consistent across OS versions and is
        // already the app's HTTP client everywhere else. VOD / Auto keep the
        // battle-tested DefaultHttpDataSource path unchanged.
        if (isLive) return liveHttpDataSourceFactory()
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            // Always send a real player User-Agent. Without it Media3 falls back
            // to the platform default ("Dalvik/2.1.0 ..."), which Xtream reseller
            // panels' anti-restream WAFs drop on LIVE ("connection closed before
            // status line") while leaving VOD /movie/ files ungated. iOS does the
            // same (PlayerView headers.isEmpty -> DeviceInfo.defaultUserAgent).
            .setUserAgent(DEFAULT_PLAYBACK_USER_AGENT)
        // Apply Dispatcharr API-key / custom User-Agent. Headers are
        // applied verbatim; the User-Agent header (if present) replaces
        // the default.
        if (httpHeaders.isNotEmpty()) {
            factory.setDefaultRequestProperties(httpHeaders)
            httpHeaders.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?.let(factory::setUserAgent)
        }
        return factory
    }

    /** OkHttp-backed [DataSource.Factory] for live raw-TS playback (GH #32).
     *  Mirrors the DefaultHttpDataSource header/User-Agent handling: a custom
     *  UA header (if the active source supplies one) wins, otherwise the real
     *  player UA; remaining headers (Dispatcharr X-API-Key etc.) ride as default
     *  request properties. The UA is set via [OkHttpDataSource.Factory.setUserAgent]
     *  so it is never duplicated as a second header. */
    private fun liveHttpDataSourceFactory(): DataSource.Factory {
        val headerUa = httpHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
        val calls = LiveCallTracker(liveHttpClient, pendingRetireGate).also { liveCallTrackers.add(it) }
        val factory = OkHttpDataSource.Factory(calls)
            .setUserAgent(okHttpSafeUserAgent(headerUa ?: DEFAULT_PLAYBACK_USER_AGENT))
        val nonUaHeaders = okHttpSafeHeaders(
            httpHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) },
        )
        if (nonUaHeaders.isNotEmpty()) factory.setDefaultRequestProperties(nonUaHeaders)
        return factory
    }

    /**
     * Call.Factory for ONE live media source that remembers its recent Calls
     * so a superseded source's socket can be closed. ExoPlayer cancels the
     * loader on stop / a new source, but the loading thread sits in a blocking
     * OkHttp read that neither the cancel flag nor a thread interrupt breaks,
     * so the old Dispatcharr connection stayed open until the next burst
     * (~10 s) or the read timeout. Same discipline as TimeshiftController's
     * fill: cancel the CALL. A cancel after [cancelAll] applies to any late
     * retry the dying source still makes.
     *
     * [openGate] orders a same-player tune: the superseded source's Calls are
     * cancelled on the playback looper right after setMediaSource (so their
     * read failure reports as a canceled load), and a prepared player can start
     * the NEW source's loader before that post runs. The new source therefore
     * waits on the gate before issuing its first request, so the old
     * Dispatcharr connection is always closed before the new one opens (a
     * per-user stream limit otherwise counts both). Bounded so a player
     * released before the post runs can never wedge the loader thread.
     */
    private class LiveCallTracker(
        private val client: OkHttpClient,
        private val openGate: java.util.concurrent.CountDownLatch? = null,
    ) : okhttp3.Call.Factory {
        private val calls = ArrayDeque<okhttp3.Call>()
        private var cancelled = false

        override fun newCall(request: okhttp3.Request): okhttp3.Call {
            openGate?.let { gate ->
                if (gate.count > 0L) {
                    val closed = try {
                        gate.await(RETIRE_GATE_MAX_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    }
                    if (!closed) Log.w(TAG, "[TUNE] stale live connection not closed after ${RETIRE_GATE_MAX_WAIT_MS}ms; opening anyway")
                }
            }
            val call = client.newCall(request)
            synchronized(this) {
                if (cancelled) {
                    call.cancel()
                } else {
                    // A source opens one connection at a time; older entries
                    // are finished, so a short history is enough.
                    calls.addLast(call)
                    while (calls.size > 4) calls.removeFirst()
                }
            }
            return call
        }

        fun cancelAll() {
            val snapshot = synchronized(this) {
                cancelled = true
                calls.toList().also { calls.clear() }
            }
            snapshot.forEach { it.cancel() }
        }
    }

    /** Live sources built so far whose connections have not been retired. */
    private val liveCallTrackers = java.util.concurrent.CopyOnWriteArrayList<LiveCallTracker>()

    /** Detach every live source's tracker. Take this BEFORE building the next
     *  source so the new one is never in the set that gets cancelled. */
    private fun takeLiveCallTrackers(): List<LiveCallTracker> {
        val stale = liveCallTrackers.toList()
        liveCallTrackers.removeAll(stale.toSet())
        // A gate nobody retired (a take with no source swap after it) must
        // never hold a later source.
        pendingRetireGate?.countDown()
        pendingRetireGate = if (stale.isEmpty()) null else java.util.concurrent.CountDownLatch(1)
        return stale
    }

    /** Opens when the stale Calls taken by the latest [takeLiveCallTrackers]
     *  have been cancelled; live sources built in between wait on it before
     *  their first request (see [LiveCallTracker]). Main thread only. */
    private var pendingRetireGate: java.util.concurrent.CountDownLatch? = null

    /** Cancel [stale] Calls once ExoPlayer has released the old period.
     *  Posted to the playback looper, so it runs after the stop /
     *  setMediaSource already queued there has cancelled the loader: the
     *  resulting read failure then reports as a canceled load, never as a
     *  load error the reconnect / 503 / stall paths would act on. */
    private fun retireLiveCalls(p: ExoPlayer, stale: List<LiveCallTracker>) {
        val gate = pendingRetireGate
        pendingRetireGate = null
        if (stale.isEmpty()) {
            gate?.countDown()
            return
        }
        val posted = android.os.Handler(p.playbackLooper).post {
            try {
                stale.forEach { it.cancelAll() }
            } finally {
                gate?.countDown()
            }
        }
        if (!posted) {
            // Playback looper already gone: nothing is loading, close now.
            stale.forEach { it.cancelAll() }
            gate?.countDown()
        }
    }

    /**
     * GH #32: okhttp3 (the client behind the live + Android Auto DataSources)
     * enforces strict RFC-7230 header validation and throws
     * IllegalArgumentException on ANY header name/value byte that is < 0x20
     * (except tab) or >= 0x7f. The old [DefaultHttpDataSource] path -- Android's
     * vendored okhttp-2.x under HttpURLConnection, still used for VOD/DVR --
     * silently accepted those bytes. So a Dispatcharr source carrying a stray
     * non-ASCII / control char in a custom header (or a device whose Build.MODEL
     * puts a non-ASCII char in the default UA) opened fine on VOD/DVR yet nuked
     * LIVE playback: the throw fires inside OkHttpDataSource.open() building the
     * request, propagates as Loader.UnexpectedLoaderException -> the terminal
     * ERROR_CODE_IO_UNSPECIFIED ("Unexpected IllegalArgumentException") the user
     * sees, and the picture stays black. Sanitize to exactly the set okhttp3
     * permits: a clean value passes through byte-for-byte (reference-equal map
     * returned, no behavior change for the 99% case), and only an illegal value
     * is repaired -- matching what the lenient HttpURLConnection path effectively
     * did. Never log the header VALUE (it may carry an API key); log only the
     * name and that a repair happened, so the offending header is visible in a
     * shared debug log without leaking the secret.
     */
    private fun okHttpSafeHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return headers
        var changed = false
        val out = LinkedHashMap<String, String>(headers.size)
        for ((name, value) in headers) {
            if (!isOkHttpSafeHeaderName(name)) {
                changed = true
                Log.w(TAG, "GH#32: dropped request header with illegal name (len=${name.length}) for okhttp")
                continue
            }
            val safe = sanitizeOkHttpHeaderValue(value)
            if (safe !== value) {
                changed = true
                Log.w(TAG, "GH#32: stripped illegal char(s) from '$name' header value for okhttp")
            }
            out[name] = safe
        }
        return if (changed) out else headers
    }

    /** Sanitize a User-Agent for okhttp3; falls back to the default UA if the
     *  value would otherwise be empty after stripping illegal chars. */
    /** Force HLS resolve/DELETE go through a bare OkHttpClient whose default
     *  UA is "okhttp/x.y"; send the player's User-Agent instead so the server
     *  sees the same client identity as the playlist and segment requests. */
    private fun forceHlsRequestHeaders(headers: Map<String, String>): Map<String, String> {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val rest = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        return rest + ("User-Agent" to okHttpSafeUserAgent(ua ?: DEFAULT_PLAYBACK_USER_AGENT))
    }

    private fun okHttpSafeUserAgent(ua: String): String =
        sanitizeOkHttpHeaderValue(ua).ifBlank { DEFAULT_PLAYBACK_USER_AGENT }

    /** okhttp3 Headers.checkValue: legal chars are tab or 0x20..0x7e. Returns
     *  the same instance when already clean (so callers can cheaply detect a
     *  no-op via referential equality). */
    private fun sanitizeOkHttpHeaderValue(value: String): String {
        if (value.all { it == '\t' || it.code in 0x20..0x7e }) return value
        return buildString(value.length) {
            for (c in value) if (c == '\t' || c.code in 0x20..0x7e) append(c)
        }
    }

    /** okhttp3 Headers.checkName: legal name chars are 0x21..0x7e (no space,
     *  no control chars). An empty or otherwise illegal name is unrepairable. */
    private fun isOkHttpSafeHeaderName(name: String): Boolean =
        name.isNotEmpty() && name.all { it.code in 0x21..0x7e }

    /** OkHttp client for live playback. The long read timeout keeps a silent
     *  connection alive across Dispatcharr's server-side dead-source failover
     *  (Issue #17), matching the value the old DefaultHttpDataSource live path
     *  used. followSslRedirects mirrors setAllowCrossProtocolRedirects. */
    private val liveHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(liveReadTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun isRawTsUrl(url: String): Boolean {
        // Developer Force HLS: a Dispatcharr live URL asking for HLS is a
        // playlist, so every TS-only path (tee, switch skip, stall nets) skips it.
        if (ForceHls.isForcedHlsUrl(url)) return false
        if (url.endsWith(".ts", ignoreCase = true)) return true
        // Dispatcharr / Xtream proxy URLs that have no file extension
        // but ARE raw MPEG-TS. The path shape is the strongest signal:
        //   /proxy/ts/stream/<uuid>
        //   /live/<user>/<pass>/<id>.ts
        //   /stream/<id>.ts
        if (url.contains("/proxy/ts/", ignoreCase = true)) return true
        if (url.contains("/live/", ignoreCase = true) && !url.contains(".m3u8")) return true
        return false
    }

    /**
     * Composable-unmount hook. Does NOT release ExoPlayer; the
     * persistent-view architecture keeps it alive across screen
     * transitions. Media3's setVideoSurface(null) cleanly releases
     * the surface binding without tearing down decode state.
     */
    fun detach() {
        val p = player ?: return
        p.setVideoSurface(null)
    }

    /** Stop playback without releasing the player. Used by the X-close
     *  and the mini's 3rd-Back dismiss. Equivalent of MPV's
     *  command("stop"). */
    fun stop() {
        val p = player ?: return
        resetForceHlsCushion("stop")
        _pipelineLabel.value = null
        forceHlsResolveGeneration++
        forceHlsSession?.let { session ->
            forceHlsSession = null
            val hdrs = httpHeaders
            prefScope.launch { ForceHls.endSession(session, forceHlsRequestHeaders(hdrs)) }
        }
        // A deliberate stop outranks any waiting clean-end reconnect.
        cleanEndJob?.cancel()
        cleanEndJob = null
        currentChannelId = null
        currentChannelIdForRebuild = null
        // Disarm the stall watchdog so a deliberate stop isn't seen as a wedge.
        hasReachedPlaybackRestart = false
        lastPlayUrl = null
        isCatchup = false
        // Adopted-live reads a local buffer whose feed the timeshift
        // controller owns (it is handed back to the kept list on close).
        if (isAdoptedLive) {
            isAdoptedLive = false
            isTimeshifting = false
        }
        // Disarm the first-byte deadline with the pipeline but KEEP the tried
        // set: an internal retry is the same tune on the same channel.
        liveFailover.disarm()
        val staleCalls = takeLiveCallTrackers()
        p.stop()
        retireLiveCalls(p, staleCalls)
        p.clearMediaItems()
    }

    /** GH #22: true when there is nothing actually playing or loading --
     *  no player, or a player sitting in STATE_IDLE. PlayerScreen's prime
     *  gate consults this so a stale currentChannelId latch (any stop path
     *  that missed clearing it) can never skip the prime against a dead
     *  player and strand the user on a silent black screen. */
    fun isIdle(): Boolean {
        val p = player ?: return true
        return p.playbackState == Player.STATE_IDLE
    }

    fun setPaused(paused: Boolean) {
        player?.playWhenReady = !paused
    }

    fun isPaused(): Boolean = player?.playWhenReady?.not() ?: true

    /**
     * Enable / disable the video track on the shared player. Android Auto plays
     * audio-only (no video on the car screen while driving), so the Auto session
     * disables video to avoid decoding frames with no surface; the foreground
     * PlayerScreen re-enables it via [playUrl]. Must be called on the main
     * thread (ExoPlayer requirement).
     */
    fun setVideoTrackEnabled(enabled: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /**
     * Sticky Audio Only requested by a companion remote / cast sender
     * (GH #33). [playUrl]'s unconditional video re-enable exists for the
     * Android Auto case; without this flag any re-prime (follow-poller,
     * stall watchdog, LAN/WAN flip) silently restored video seconds after
     * a phone toggled Audio Only on (2026-07-17 Streamer test: state
     * flipped On -> video back -> state self-healed to Off). Set/cleared
     * only by the remote command paths; the foreground re-enables consult
     * it.
     */
    @Volatile
    var remoteAudioOnly = false

    /** Full teardown for the X-close button. Releases the codec,
     *  audio renderer, and DataSource. Next acquire creates fresh. */
    fun destroy() {
        val p = player ?: return
        player = null
        _playerInstance.value = null
        resumeGateActive = false
        currentChannelId = null
        currentChannelIdForRebuild = null
        // Full teardown clears the stream-failover walk and any pending
        // "Channel is stopping" retry.
        stoppingRetryJob?.cancel()
        stoppingRetryJob = null
        // A waiting clean-end reconnect must not outlive the player.
        cleanEndJob?.cancel()
        cleanEndJob = null
        liveFailover.resetWalk()
        watchdogJob?.cancel()
        watchdogJob = null
        lastPlayUrl = null
        val staleCalls = takeLiveCallTrackers()
        try {
            tracer.tracedPlayer = null
            p.removeAnalyticsListener(tracer.analyticsListener)
            p.removeListener(LoggingPlayerListener)
            p.removeListener(watchdogListener)
            p.release()
        } catch (t: Throwable) {
            Log.w(TAG, "ExoPlayer release failed", t)
        } finally {
            // release() blocks until the playback thread let go of the
            // loaders, so the stale Calls can be cancelled right here.
            staleCalls.forEach { it.cancelAll() }
            pendingRetireGate?.countDown()
            pendingRetireGate = null
            PlaybackActivityTracker.playerReleased()
        }
    }

    private fun armWatchdog() {
        // Steady playback: a later same-channel reopen earns a fresh quick retry.
        sameChannelQuickRetryUsed = false
        hasReachedPlaybackRestart = true
        lastPositionAdvanceAtMs = SystemClock.elapsedRealtime()
        lastKnownPositionMs = player?.currentPosition ?: 0L
        lastBufferAdvanceAtMs = lastPositionAdvanceAtMs
        lastKnownBufferedPositionMs = player?.bufferedPosition ?: 0L
        // Measure the no-frame window from steady playback, not from prime,
        // so a slow cold start is never mistaken for a black screen.
        if (!videoFrameRendered) streamPrimedAtMs = lastPositionAdvanceAtMs
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        lastWatchdogTickAtMs = 0L
        watchdogJob = watchdogScope.launch {
            while (isActive) {
                delay(watchdogPollMs)
                val p = player ?: continue
                val now = SystemClock.elapsedRealtime()
                // Steady-state heartbeat ([PERF] + [FEED], one pair per 15s).
                tracer.tick(p)

                // Jank discount. This loop runs on Main.immediate, so a tick
                // arriving well past its schedule means the MAIN THREAD was
                // hung (EPG scroll on a weak device, GC storm). Playback
                // starved by that same hang looks "stale" without the stream
                // being at fault -- don't count the hang against it.
                // (Chromecast field report 2026-07-18.)
                if (lastWatchdogTickAtMs != 0L) {
                    val overshoot = (now - lastWatchdogTickAtMs) - watchdogPollMs
                    if (overshoot > 2_000L) {
                        // Cap at `now`: an unbounded bump after a very long
                        // gap would park the baselines in the future and
                        // blind the watchdog for that long.
                        lastPositionAdvanceAtMs =
                            minOf(lastPositionAdvanceAtMs + overshoot, now)
                        lastBufferAdvanceAtMs =
                            minOf(lastBufferAdvanceAtMs + overshoot, now)
                    }
                }
                lastWatchdogTickAtMs = now

                // Ingest freshness, sampled EVERY tick (before any of the
                // early-outs below), so [ingestAgeMs] is honest for the
                // Dispatcharr follow-poller and for the stall status here.
                val bufferedNow = p.bufferedPosition
                if (bufferedNow != lastKnownBufferedPositionMs) {
                    lastKnownBufferedPositionMs = bufferedNow
                    lastBufferAdvanceAtMs = now
                }
                val ingestStaleNowMs = now - lastBufferAdvanceAtMs
                // A frozen frame is never silent (field 2026-09-14: the stream
                // was stopped server-side and the picture sat frozen ~30s with
                // no message). Once bytes stop arriving on a live channel we
                // intend to play, show the existing "Reconnecting" status until
                // they flow again or the unavailable card takes over.
                val stallWatchLive = lastPlayUrl?.let { isRawTsUrl(it) } == true &&
                    p.playWhenReady && !isTimeshifting && !isCatchup &&
                    (hasReachedPlaybackRestart || videoFrameRendered) &&
                    !_streamUnavailable.value
                // "Starving" is a draining buffer, never a quiet socket. This
                // proxy bursts (8 to 9.5 s gaps with 10 s buffered), so silence
                // only counts while the buffer ahead of the playhead has drained
                // under STALL_BUFFER_AHEAD_MS, or on a real BUFFERING underrun.
                // That is an internal signal only (logged, mirrors
                // [isLiveIngestStalled] for the follow-poller). With the start
                // gate at 1200 ms the buffer routinely sits under 1.5 s between
                // bursts while the picture plays perfectly (Streamer 2026-09-14:
                // ~1 s Reconnecting flashes every few seconds at 60 fps), so the
                // OVERLAY waits for playback itself to stop: a rebuffer after
                // READY, the playhead frozen >= STALL_OVERLAY_FROZEN_MS, or the
                // buffer effectively empty with ingest silent. It clears as soon
                // as the playhead moves again.
                val bufferAheadNowMs = (bufferedNow - p.currentPosition).coerceAtLeast(0L)
                val lowBuffer = bufferAheadNowMs < STALL_BUFFER_AHEAD_MS
                val ingestSilent = ingestStaleNowMs >= INGEST_STALL_STATUS_MS
                val underrunBuffering =
                    p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
                val starvingNow = stallWatchLive && lowBuffer && (ingestSilent || underrunBuffering)
                if (starvingNow != liveStarving) {
                    liveStarving = starvingNow
                    Log.i(
                        TAG,
                        if (starvingNow) {
                            "[STALL] starving: buffered ahead ${bufferAheadNowMs}ms, " +
                                "ingest quiet ${ingestStaleNowMs}ms ch=$currentChannelId"
                        } else {
                            "[STALL] fed: buffered ahead ${bufferAheadNowMs}ms ch=$currentChannelId"
                        },
                    )
                }
                val probePos = p.currentPosition
                val playheadAdvanced = probePos != stallProbePositionMs
                if (playheadAdvanced) {
                    stallProbePositionMs = probePos
                    stallProbeAdvanceAtMs = now
                }
                val playheadFrozen = !playheadAdvanced &&
                    now - stallProbeAdvanceAtMs >= STALL_OVERLAY_FROZEN_MS
                val bufferEmpty = bufferAheadNowMs < STALL_OVERLAY_EMPTY_BUFFER_MS && ingestSilent
                val reallyStalled = stallWatchLive &&
                    (underrunBuffering || playheadFrozen || bufferEmpty)
                if (reallyStalled) {
                    if (!ingestStallStatusShown) {
                        ingestStallStatusShown = true
                        Log.i(
                            TAG,
                            "[STALL] playback stalled: state=${p.playbackState} " +
                                "frozen=$playheadFrozen buffered ahead ${bufferAheadNowMs}ms, " +
                                "ingest quiet ${ingestStaleNowMs}ms ch=$currentChannelId; showing Reconnecting",
                        )
                        liveFailover.publishServerStatus("Reconnecting...")
                    }
                } else if (ingestStallStatusShown &&
                    (!stallWatchLive || playheadAdvanced)
                ) {
                    ingestStallStatusShown = false
                    Log.i(
                        TAG,
                        "[STALL] cleared: playback advancing, buffered ahead ${bufferAheadNowMs}ms " +
                            "ch=$currentChannelId; hiding Reconnecting",
                    )
                    liveFailover.publishServerStatus(null)
                }

                // Live resume gate: runs before every heal below, because while it
                // holds, playWhenReady is false and the stale-position check
                // deliberately skips the stream.
                tickResumeGate(p, now)
                tickForceHlsCushion(p, now)

                // Cold-start NO-DATA net (never-started stream). Runs INDEPENDENT
                // of hasReachedPlaybackRestart: a dead Dispatcharr proxy stream
                // delivers zero bytes, never reaches READY, and would otherwise be
                // invisible to every heal below and hang on black forever (field:
                // 57s+). Android analog of iOS libmpv network-timeout=30. If we
                // still intend to play, no frame has rendered, we have not reached
                // steady playback, the player is still BUFFERING, and NOTHING has
                // arrived since prime, then after the threshold (1) reconnect once
                // -- a fresh GET to the same /proxy/ts/stream/<uuid> url, which also
                // gives Dispatcharr a chance to re-select a live stream -- and (2)
                // if still no bytes, surface "Channel unavailable" instead of black.
                // Issue #17: give a live Dispatcharr channel a MUCH longer ceiling
                // (the held-open connection is where the proxy fails a dead source
                // over to a working one server-side); VOD/other keep the tight net.
                val coldStartUrl = lastPlayUrl
                val coldStartIsLive = coldStartUrl != null && isRawTsUrl(coldStartUrl)
                val coldStartCeilingMs =
                    if (coldStartIsLive) liveNoDataStartupThresholdMs else noDataStartupThresholdMs
                if (coldStartUrl != null && p.playWhenReady && !hasReachedPlaybackRestart &&
                    !videoFrameRendered && p.playbackState == Player.STATE_BUFFERING &&
                    p.currentPosition <= 0L && p.bufferedPosition <= 0L &&
                    now - streamPrimedAtMs >= coldStartCeilingMs
                ) {
                    val deadMs = now - streamPrimedAtMs
                    if (coldStartIsLive) {
                        // The long read timeout already held this connection open
                        // across the server-side failover. Nothing arrived by the
                        // ceiling => the whole channel is dead. Reconnecting here
                        // would only abandon the connection the proxy is advancing
                        // on, so surface "unavailable" directly.
                        Log.w(TAG, "[NO-DATA] live no bytes after ${deadMs}ms ch=$currentChannelId; surfacing unavailable")
                        markStreamUnavailable()
                    } else {
                        when (noDataHealAttempts) {
                            0 -> if (forceReload("startup-no-data=${deadMs}ms")) noDataHealAttempts = 1
                            else -> {
                                noDataHealAttempts = 2
                                Log.w(TAG, "[NO-DATA] no bytes after reconnect ch=$currentChannelId (${deadMs}ms); surfacing unavailable")
                                markStreamUnavailable()
                            }
                        }
                    }
                    continue
                }

                // Only when we intend to play, have a url to reload, and aren't at
                // end-of-stream. Steady gate: skip a TRUE cold start (never reached
                // steady AND never rendered a frame -- the cold-start net above owns
                // that). But once a frame HAS rendered, keep monitoring even if the
                // steady flag later drops: a stream that played then wedged (proxy
                // dropped our read / decoder hung) clears hasReachedPlaybackRestart
                // WITHOUT a reload, and used to fall through BOTH the cold-start net
                // (needs !videoFrameRendered + 0 position) and this position-stall
                // check -- so it froze forever with no recovery (Shield field freeze
                // 2026-07-15: status 404 / read wedged, no reload for >1min).
                if (lastPlayUrl == null || isTimeshifting || isCatchup || !p.playWhenReady ||
                    (!hasReachedPlaybackRestart && !videoFrameRendered) ||
                    p.playbackState == Player.STATE_ENDED
                ) {
                    continue
                }
                // Black-screen net. Runs before the position check because an
                // advancing audio position is exactly what masks this failure.
                // videoFormat != null excludes radio/audio-only feeds; the
                // disabled-types check excludes deliberate Audio Only mode.
                if (watchdogReloadEnabled &&
                    !videoFrameRendered &&
                    p.isPlaying &&
                    p.videoFormat != null &&
                    !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) &&
                    now - streamPrimedAtMs >= noVideoFrameThresholdMs
                ) {
                    // GH #107 dead-surface signal, measured not parsed: the
                    // AUDIO clock has genuinely advanced this far past the
                    // prime (so the pipeline is healthy and decoding) while the
                    // video renderer has a format, video is not disabled, and
                    // NO first frame ever arrived. That is exactly the state
                    // "Could not find corresponding native window for surface"
                    // produces. A re-prime cannot fix it - the surface is gone -
                    // so skip straight to the full rebuild.
                    val audioAdvancedMs = p.currentPosition
                    if (audioAdvancedMs >= DEAD_SURFACE_AUDIO_ADVANCE_MS &&
                        noFrameHealAttempts == 0
                    ) {
                        noFrameHealAttempts = 2
                        Log.w(
                            TAG,
                            "[RECOVER] surface dead; rebuilding (audio +${audioAdvancedMs}ms, " +
                                "no first frame ch=$currentChannelId)",
                        )
                        if (!rebuildPlayerAndSurface("surface dead")) noFrameHealAttempts = 3
                        continue
                    }
                    when (noFrameHealAttempts) {
                        0 -> if (forceReload("no-video-frame")) noFrameHealAttempts = 1
                        1 -> { noFrameHealAttempts = 2; recreateForBlackScreen() }
                        2 -> {
                            noFrameHealAttempts = 3
                            Log.w(TAG, "[BLACKSCREEN] still no frame after reload + recreate ch=$currentChannelId; giving up")
                        }
                    }
                    continue
                }
                val pos = p.currentPosition
                // GH #43: CHANGE-detection, not a monotonic watermark. Media3
                // positions for sliding-window live HLS (plain public playlists
                // like TDTChannels) are window-relative and sawtooth around a
                // constant as the window trims, so healthy playback never beats
                // the recorded MAXIMUM - the old `pos > lastKnown` comparison
                // let both staleness clocks accrue on a perfectly playing
                // stream and reload-cycled every channel switch (cut + resume).
                // A truly wedged player reports a bit-identical frozen position
                // on every 1s poll (its media clock stopped), so `!=` keeps the
                // Shield raw-TS wedge true-positive while curing the HLS one.
                if (pos != lastKnownPositionMs) {
                    lastKnownPositionMs = pos
                    lastPositionAdvanceAtMs = now
                    consecutiveReloads = 0
                    continue
                }
                // Byte-ingest discriminator: bufferedPosition advancing while
                // render position is stuck = data IS arriving and the player
                // is honestly buffering (or the decoder is starved on a weak
                // device). A reload there throws away the buffer it just
                // built and re-primes a healthy connection -- the reload
                // storm that wedged Frankie's Chromecast. Only a stream whose
                // INGEST is also stale (wedged proxy read, dead source) gets
                // the stale reload; that is the Shield 2026-07-15 wedge this
                // check exists for.
                // GH #43: same change-detection rationale as the render
                // position above - bufferedPosition is window-relative too.
                // Sampled once per tick at the top of the loop.
                val staleMs = now - lastPositionAdvanceAtMs
                val ingestStaleMs = ingestStaleNowMs
                // Dispatcharr live bursts every 8 to 13 s (gaps up to ~15 s), and a
                // same-channel reopen during a quiet upstream trips stream_limit
                // (JayK 2026-09-15). Raw-TS live therefore needs 25 s of silence
                // AND an empty buffer; other sources keep the 6 s rule.
                val rawTsLive = lastPlayUrl?.let { isRawTsUrl(it) } == true
                val ingestReloadMs =
                    if (rawTsLive) LIVE_INGEST_RELOAD_SILENCE_MS else staleReloadThresholdMs
                val bufferOk = !rawTsLive || bufferAheadNowMs < STALL_OVERLAY_EMPTY_BUFFER_MS
                // Developer Force HLS: a rebuffer that is filling toward the
                // (long) resume gate is a deliberate hold, not a wedge.
                val forcedHlsHold = lastPlayUrl?.let(ForceHls::isForcedHlsUrl) == true &&
                    p.playbackState == Player.STATE_BUFFERING &&
                    bufferAheadNowMs < forceHlsTargetOffsetMs &&
                    now - lastBufferAdvanceAtMs < staleReloadThresholdMs
                if (forcedHlsHold) {
                    if (staleMs >= staleReloadThresholdMs) {
                        Log.i(ForceHls.TAG, "[FORCE-HLS] rebuffer hold ${bufferAheadNowMs}/${forceHlsTargetOffsetMs} ms; watchdog reload skipped")
                    }
                    continue
                }
                if (watchdogReloadEnabled &&
                    staleMs >= staleReloadThresholdMs &&
                    ingestStaleMs >= ingestReloadMs &&
                    bufferOk
                ) {
                    forceReload("stale=${staleMs}ms ingest-stale=${ingestStaleMs}ms")
                }
            }
        }
    }

    /** Re-prime the demuxer + decoder against the SAME url (mpv loadfile
     *  replace). Returns true when a reload actually ran (false while inside
     *  the cooldown or past the attempt cap). */
    private fun forceReload(reason: String): Boolean {
        if (isTimeshifting || isCatchup) return false
        if (inSwitchWindow() && !reason.startsWith("error:")) {
            Log.i(TAG, "[RECOVER] suppressed reload during switch window (reason=$reason)")
            return false
        }
        val p = player ?: return false
        val url = lastPlayUrl ?: return false
        val now = SystemClock.elapsedRealtime()
        // Exponential backoff: 5s before attempt 1, 10s before attempt 2,
        // 20s before attempt 3. Back-to-back re-primes on a struggling
        // device compound the problem (each throws away buffer + decoder
        // state); spacing them out gives a healthy-but-starved pipeline
        // room to recover on its own before the next hammer falls.
        val backoffMs = reloadCooldownMs shl consecutiveReloads.coerceAtMost(3)
        if (now - lastForcedReloadAtMs < backoffMs) return false
        if (consecutiveReloads >= maxConsecutiveReloads) {
            // GH #63: do NOT dead-end here. Giving up used to leave the player
            // frozen forever with every recovery net disarmed: this watchdog
            // capped out, the follow-poller parked on the cleared steady flag,
            // and the cold-start net never re-arms for an in-place reload. A
            // Dispatcharr failover that takes longer than the three reload
            // attempts (~35s of backoff) hit exactly that hole and only a
            // manual close/reopen recovered. Escalate to the standard
            // unavailable overlay instead: it stops playback, tells the user,
            // and auto-retries a FRESH connection every 5 seconds until the
            // server actually comes back.
            Log.w(
                TAG,
                "[MPV-RELOAD] in-place reloads exhausted ($consecutiveReloads) " +
                    "ch=$currentChannelId reason=$reason; escalating to unavailable overlay",
            )
            markStreamUnavailable()
            return false
        }
        lastForcedReloadAtMs = now
        consecutiveReloads++
        // A re-prime throws the buffer away, so any gate holding against it is
        // stale; the fresh stream is governed by the tune-path start gate.
        resumeGateActive = false
        Log.w(TAG, "[MPV-RELOAD] live stall reload ch=$currentChannelId reason=$reason attempt=$consecutiveReloads")
        clearPauseStamp("re-prime")
        tracer.recover("in-place reload reason=$reason attempt=$consecutiveReloads")
        tracer.markTuneStart(lastPlayTitle, PlaybackTracer.urlKind(url))
        // Disarm until the re-primed stream reaches steady playback again.
        hasReachedPlaybackRestart = false
        lastKnownPositionMs = 0L
        lastPositionAdvanceAtMs = now
        videoFrameRendered = false
        streamPrimedAtMs = now
        lastKnownBufferedPositionMs = 0L
        lastBufferAdvanceAtMs = now
        primeGeneration += 1
        resetForceHlsCushion("prime")
        noteSourceOpen(url, currentChannelIdForRebuild ?: currentChannelId)
        val staleCalls = takeLiveCallTrackers()
        val source = wrapForSwitchSkip(
            url,
            buildMediaSource(
                url, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri,
                lastPlayDrmType, lastPlayDrmKey,
            ),
        )
        p.setMediaSource(source)
        retireLiveCalls(p, staleCalls)
        p.prepare()
        p.playWhenReady = true
        return true
    }

    /**
     * Dispatcharr 503 handling for the live tune path (Nothing Phone
     * session4.txt 01:48:51, ESPN HD: HTTP 503 on every attempt, four ExoPlayer
     * retries then four in-place reloads, then "stream unavailable; stopping" --
     * no other stream was ever tried and the message guessed at a cause).
     *
     * The server always says WHY in the 503 body, so:
     *  - "Channel is stopping, retry shortly": the previous session for this
     *    channel is still being torn down (typically right after a cast ended).
     *    Wait the advertised Retry-After (1 s default, 3 s ceiling) and retry the
     *    SAME url, up to [Dispatcharr503.MAX_STOPPING_RETRIES] times, before
     *    anything else is attempted.
     *  - anything else ("No available streams for this channel", "Channel
     *    resources unavailable", a specific upstream error_reason): the server has
     *    already tried this channel's streams and failed, so waiting on this URL
     *    cannot help. Trigger the SAME failover walk the first-byte deadline uses,
     *    immediately.
     *
     * The user-facing text quotes the server verbatim either way; we never
     * substitute a guessed cause such as "too many connections".
     */
    private fun handleLive503(error: Throwable): Boolean {
        if (DispatcharrConnectionLimit.parse(error) != null) return false
        val info = Dispatcharr503.parse(error) ?: return false
        // Live only: VOD / catch-up / DVR have their own error paths and no
        // member-stream walk to fall back on.
        val url = lastPlayUrl ?: return false
        if (isTimeshifting || isCatchup || PlaybackTracer.urlKind(url) != "live") return false
        // The same 503 arrives twice: once as the load error, then again as the
        // terminal player error it turns into. Act once, but keep OWNING it both
        // times so the generic reload ladder stands down on the second arrival.
        val now = SystemClock.elapsedRealtime()
        if (live503HandledKey == url && now - live503HandledAtMs < LIVE_503_DEDUPE_MS) {
            return live503OwnedRecovery
        }
        live503HandledKey = url
        live503HandledAtMs = now
        live503OwnedRecovery = false
        serverReason = info.reason
        if (!sameChannelQuickRetryUsed && sameChannelReopenAtMs != 0L &&
            now - sameChannelReopenAtMs <= SAME_CHANNEL_REOPEN_WINDOW_MS &&
            stoppingRetryJob?.isActive != true
        ) {
            sameChannelQuickRetryUsed = true
            Log.i(
                TAG,
                "[RECONNECT] same-channel reopen rejected, quick retry " +
                    "(503 \"${info.reason}\" ${now - sameChannelReopenAtMs}ms after reopen; " +
                    "retrying in ${SAME_CHANNEL_QUICK_RETRY_MS}ms)",
            )
            liveFailover.publishServerStatus("Reconnecting...")
            stoppingRetryJob = watchdogScope.launch {
                delay(SAME_CHANNEL_QUICK_RETRY_MS)
                if (lastPlayUrl != url) return@launch
                reprimeSameUrl("503 ${info.reason} same-channel quick retry")
            }
            live503OwnedRecovery = true
            return true
        }
        if (info.kind == Dispatcharr503.Kind.STOPPING) {
            // A teardown is the one case where waiting is the whole cure. The
            // failover walk must NOT run here: on the Streamer 2026-09-14
            // teardown it POSTed change_stream twice (both answered 504 "Stream
            // switch was not confirmed by the channel owner"), walked every
            // member stream, and recovered on the stream it started from, while
            // the five 1.2 s retries per cycle turned one server stop into 60
            // requests in 52 s. So: back off on the SAME url on the
            // 2/4/8/16/30 s ladder, up to a 60 s budget, then the unavailable
            // card with Retry.
            if (stoppingFirstAtMs == 0L) stoppingFirstAtMs = now
            val elapsedMs = now - stoppingFirstAtMs
            if (stoppingRetryJob?.isActive == true) {
                // One in-flight retry at a time: the duplicate copy of this same
                // 503 (load error, then the terminal player error) and any later
                // 503 arriving mid-wait must not double-schedule.
                live503OwnedRecovery = true
                return true
            }
            if (stoppingRetries >= Dispatcharr503.MAX_STOPPING_RETRIES ||
                elapsedMs >= Dispatcharr503.STOPPING_BUDGET_MS
            ) {
                Log.w(
                    TAG,
                    "[RECOVER] 503 reason=\"${info.reason}\" still stopping after " +
                        "${elapsedMs}ms / $stoppingRetries retries; surfacing unavailable",
                )
                markStreamUnavailable()
                live503OwnedRecovery = true
                return true
            }
            stoppingRetries += 1
            val attempt = stoppingRetries
            val waitMs = Dispatcharr503.stoppingDelayMs(
                attempt = attempt,
                retryAfterMs = info.retryAfterMs,
                elapsedMs = elapsedMs,
            )
            liveFailover.publishServerStatus("Reconnecting...")
            Log.i(
                TAG,
                "[RECOVER] 503 reason=\"${info.reason}\" backing off ${waitMs}ms " +
                    "then retrying same url ($attempt/${Dispatcharr503.MAX_STOPPING_RETRIES}, " +
                    "${elapsedMs}ms of ${Dispatcharr503.STOPPING_BUDGET_MS}ms budget spent)",
            )
            stoppingRetryJob = watchdogScope.launch {
                delay(waitMs)
                // A channel flip / teardown since the wait started means this
                // retry belongs to a stream nobody is watching any more.
                if (lastPlayUrl != url) return@launch
                reprimeSameUrl("503 ${info.reason} retry $attempt")
            }
            live503OwnedRecovery = true
            return true
        }
        // "No available streams", "Channel resources unavailable", a specific
        // upstream error_reason: start the walk now, and leave the generic
        // ladder running behind it -- the walk can legitimately find nothing to
        // switch to, and that case must still reach the unavailable card.
        liveFailover.onServer503(info.reason)
        return false
    }

    // ---- repeated clean end (Dispatcharr "terminate on limit exceeded") ----
    // The server ends the OLDEST client's stream cleanly when a new client
    // takes the account's last slot, and the booted client sees nothing but a
    // clean end of stream. Reconnecting immediately boots the other device,
    // which reconnects in turn: two devices bouncing forever. So: always keep
    // reconnecting (a clean end is usually just a restarted stream), but back
    // off - now, 5 s, 15 s, 30 s, then every 60 s - and give up ONLY when
    // [StreamEndVerifier] can prove the account is at its stream limit with a
    // newer session elsewhere. Never on a guess.
    /** Clean ends in the current run: 1 = the first (immediate reconnect). */
    private var cleanEndStreak = 0
    /** When the reconnect after a clean end opened; 0 when none is pending. */
    private var cleanEndReconnectAtMs = 0L
    /** The scheduled reconnect (verification + backoff), if one is waiting. */
    private var cleanEndJob: Job? = null
    /** Wall clock (epoch seconds) at which the current live session opened,
     *  so the verifier can tell a NEWER session elsewhere from our own. */
    @Volatile private var liveTuneWallSec = 0.0

    /** Channel change, Retry, teardown: forget the streak and any wait. */
    private fun resetCleanEndBudget() {
        cleanEndStreak = 0
        cleanEndReconnectAtMs = 0L
        cleanEndJob?.cancel()
        cleanEndJob = null
    }

    /**
     * Main thread. A live stream ended cleanly ([source] = "ENDED") or the
     * follow-poller found the session dead after such an end. Returns true
     * when this owns recovery (a reconnect is scheduled, or the notice is up),
     * so the caller must not reconnect as well.
     */
    private fun onLiveCleanEnd(source: String): Boolean {
        val url = lastPlayUrl ?: return false
        if (isTimeshifting || isCatchup || PlaybackTracer.urlKind(url) != "live") return false
        if (_connectionLimit.value != null) return true
        // A reconnect is already waiting out its backoff; this end is the same
        // one seen twice (ENDED, then the follow-poller's dead session).
        if (cleanEndJob?.isActive == true) return true
        if (inSwitchWindow()) {
            Log.i(TAG, "[RECOVER] clean end ($source) during switch window; left to the switch watch")
            return false
        }
        // A dead session with no clean end behind it is the old wedge case:
        // leave the follow-poller's own re-prime alone.
        if (source != "ENDED" && cleanEndStreak == 0) return false
        val now = SystemClock.elapsedRealtime()
        val sinceReconnect = now - cleanEndReconnectAtMs
        cleanEndStreak = if (
            cleanEndReconnectAtMs > 0L && sinceReconnect < StreamEndVerifier.HEALTHY_RESET_MS
        ) {
            cleanEndStreak + 1
        } else {
            if (cleanEndStreak > 0) {
                Log.i(
                    TAG,
                    "[RECOVER] clean-end streak reset: ${sinceReconnect}ms of playback " +
                        "since the last reconnect ch=$currentChannelId",
                )
            }
            1
        }
        val streak = cleanEndStreak
        val backoffMs = StreamEndVerifier.backoffMs(streak)
        val channelUuid = StreamEndVerifier.channelUuidFromUrl(url)
        val connectedAtSec = liveTuneWallSec
        Log.w(
            TAG,
            "[RECOVER] clean end ($source) #$streak ch=$currentChannelId; " +
                "reconnecting in ${backoffMs}ms",
        )
        tracer.recover("clean end ($source) #$streak; reconnect in ${backoffMs}ms")
        // The stream is gone and we are waiting; say so instead of freezing on
        // the last frame with no message. Deliberately NOT the stall
        // watchdog's flag: that clears the moment the (stopped) player stops
        // looking live, which would blank the status through the whole wait.
        // The next tune's first byte clears it (LiveStreamFailover).
        if (backoffMs > 0L) liveFailover.publishServerStatus("Reconnecting...")
        cleanEndJob = watchdogScope.launch {
            // Verification first: the ONLY way this ever stops for good. A
            // non-admin account, an old server or any transport failure
            // answers "not verified" and we just keep reconnecting.
            if (streak >= 2) {
                val verdict = StreamEndVerifier.verify(channelUuid, connectedAtSec)
                Log.i(
                    TAG,
                    "[RECOVER] clean end #$streak session check: " +
                        "${if (verdict.stopped) "AT LIMIT" else "not verified"} (${verdict.detail})",
                )
                if (verdict.stopped) {
                    withContext(Dispatchers.Main) { stopForVerifiedStreamEnd(url) }
                    return@launch
                }
            }
            if (backoffMs > 0L) delay(backoffMs)
            withContext(Dispatchers.Main) {
                if (_connectionLimit.value != null || isTimeshifting || isCatchup) return@withContext
                val target = lastPlayUrl ?: url
                Log.i(TAG, "[RECOVER] clean end #$streak: reconnecting ch=$currentChannelId")
                cleanEndReconnectAtMs = SystemClock.elapsedRealtime()
                // Keep the follow-poller (and the stall watchdog) from opening a
                // SECOND connection right behind this one.
                lastForcedReloadAtMs = cleanEndReconnectAtMs
                playUrl(
                    target, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri,
                    drmLicenseType = lastPlayDrmType, drmLicenseKey = lastPlayDrmKey,
                )
            }
        }
        return true
    }

    /** Verified boot: this account is at its stream limit and a newer session
     *  elsewhere took the slot. Stop everything until the user presses Retry. */
    private fun stopForVerifiedStreamEnd(url: String) {
        if (_connectionLimit.value != null) return
        Log.w(TAG, "[RECOVER] stream end VERIFIED at the stream limit ch=$currentChannelId; stopping, waiting for Retry")
        tracer.recover("stream end verified at the stream limit; stopping")
        cleanEndJob = null
        resetCleanEndBudget()
        stoppingRetryJob?.cancel()
        stoppingRetryJob = null
        reconnectUrl = lastPlayUrl ?: url
        reconnectChannelId = currentChannelIdForRebuild ?: currentChannelId ?: reconnectChannelId
        connectionLimitWasCatchup = false
        // No failover walk after this: forget it along with the pipeline.
        liveFailover.resetWalk()
        liveFailover.publishServerStatus(null)
        ingestStallStatusShown = false
        _connectionLimit.value = DispatcharrConnectionLimit.STREAM_ENDED
        stop()
    }

    /**
     * Dispatcharr connection-limit refusal on a live or catch-up tune: record
     * the notice, stop the pipeline (no reconnect, no failover walk, no
     * Reconnecting status) and keep what Retry needs. Returns true when the
     * error was a limit refusal this holder now owns, including the duplicate
     * second sighting (load error, then terminal player error).
     */
    private fun handleConnectionLimit(error: Throwable): Boolean {
        val notice = DispatcharrConnectionLimit.parse(error) ?: return false
        if (_connectionLimit.value != null) return true
        if (isTimeshifting) return false
        val url = lastPlayUrl
        val catchup = isCatchup
        if (!catchup && (url == null || PlaybackTracer.urlKind(url) != "live")) return false
        Log.w(TAG, "[LIMIT] ${notice.kind} \"${notice.message}\"; stopping, waiting for Retry")
        tracer.recover("connection limit ${notice.kind}; stopping")
        stoppingRetryJob?.cancel()
        stoppingRetryJob = null
        if (!catchup) {
            reconnectUrl = url ?: reconnectUrl
            reconnectChannelId = currentChannelIdForRebuild ?: currentChannelId ?: reconnectChannelId
        }
        connectionLimitWasCatchup = catchup
        liveFailover.publishServerStatus(null)
        _connectionLimit.value = notice
        stop()
        return true
    }

    /** Retry button of the connection-limit notice: one fresh tune of what
     *  was refused. */
    fun retryConnectionLimit() {
        if (_connectionLimit.value == null) return
        _connectionLimit.value = null
        if (connectionLimitWasCatchup) {
            val cu = lastCatchupUrl ?: return
            playCatchup(cu, lastCatchupTitle, lastCatchupSubtitle, lastCatchupArtworkUri)
        } else {
            retryUnavailable()
        }
    }

    /**
     * Re-prime the SAME url right now, with no backoff and outside the stall
     * watchdog's attempt cap. Used only by the "Channel is stopping" 503 path,
     * where the server explicitly told us when to come back: spending the
     * watchdog's escalating budget on a retry the server invited would burn the
     * ladder that exists for genuinely wedged streams.
     */
    private fun reprimeSameUrl(reason: String) {
        val p = player ?: return
        val url = lastPlayUrl ?: return
        val now = SystemClock.elapsedRealtime()
        tracer.recover("re-prime same url reason=$reason")
        tracer.markTuneStart(lastPlayTitle, PlaybackTracer.urlKind(url))
        hasReachedPlaybackRestart = false
        lastKnownPositionMs = 0L
        lastPositionAdvanceAtMs = now
        videoFrameRendered = false
        streamPrimedAtMs = now
        lastKnownBufferedPositionMs = 0L
        lastBufferAdvanceAtMs = now
        LoggingPlayerListener.sawTracksChangedSincePrime = false
        primeGeneration += 1
        resetForceHlsCushion("prime")
        noteSourceOpen(url, currentChannelIdForRebuild ?: currentChannelId)
        val staleCalls = takeLiveCallTrackers()
        val source = wrapForSwitchSkip(
            url,
            buildMediaSource(
                url, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri,
                lastPlayDrmType, lastPlayDrmKey,
            ),
        )
        p.setMediaSource(source)
        retireLiveCalls(p, staleCalls)
        p.prepare()
        p.playWhenReady = true
    }

    /** Terminal heal for a never-started live stream: the Dispatcharr proxy
     *  produced no bytes even after a reconnect. Flag it so the player UI shows
     *  "Channel unavailable" (instead of an endless black screen) and stop the
     *  dead connection. A fresh [playUrl] (channel flip / re-tap) clears it. */
    private fun markStreamUnavailable() {
        // Preserve the replay URL BEFORE stop() nulls lastPlayUrl, so the
        // overlay's countdown + Retry can actually re-tune (and recover when
        // the server returns). Prefer whatever fresh URL a rebuild hook would
        // yield next; the raw lastPlayUrl is the reliable fallback.
        reconnectUrl = lastPlayUrl ?: reconnectUrl
        reconnectChannelId = currentChannelIdForRebuild ?: currentChannelId ?: reconnectChannelId
        if (_lastErrorText.value == null) {
            // Quote the server when it told us why; only fall back to the
            // generic no-data line when nothing was said.
            _lastErrorText.value = serverReason?.let { "Server: $it" }
                ?: "No data received from the stream"
        }
        _streamUnavailable.value = true
        tracer.recover("stream unavailable; stopping")
        stop()
    }

    /** Last-resort black-screen heal: full player teardown + rebuild + replay.
     *  A recreate gets a fresh video codec AND a fresh surface binding (the
     *  persistent window rebinds via the playerInstance flow), curing wedges
     *  a same-player re-prime cannot reach. */
    private fun recreateForBlackScreen() {
        val url = lastPlayUrl ?: return
        val ctx = appContext ?: return
        Log.w(TAG, "[BLACKSCREEN] no video frame after reload; recreating player ch=$currentChannelId")
        tracer.recover("recreating player (no video frame)")
        val title = lastPlayTitle
        val subtitle = lastPlaySubtitle
        val art = lastPlayArtworkUri
        val chan = currentChannelId
        val attempts = noFrameHealAttempts
        destroy()
        // GH #107: a recreate that keeps the OLD SurfaceView re-binds the fresh
        // codec to the same (possibly dead) native window. Ask the persistent
        // window for a new one at the same time; the rebind happens through the
        // playerInstance flow either way, so this only ever adds a surface swap.
        _surfaceRebuildRequest.value = _surfaceRebuildRequest.value + 1
        acquireOrCreate(ctx)
        playUrl(url, title, subtitle, art, channelId = chan)
        // destroy()/playUrl() reset these; the heal must keep its place in the
        // escalation ladder and the screen's channel identity.
        currentChannelId = chan
        noFrameHealAttempts = attempts
    }

    /** GH #8 audio self-heal: full teardown + rebuild (acquireOrCreate now
     *  sees audioSinkFallback=true, so it builds the stock context sink) +
     *  replay the same channel. Preserves the screen's channel identity across
     *  the destroy()/playUrl() resets, same shape as recreateForBlackScreen. */
    private fun rebuildWithStockAudioAndReplay() {
        val url = lastPlayUrl ?: return
        val ctx = appContext ?: return
        val title = lastPlayTitle
        val subtitle = lastPlaySubtitle
        val art = lastPlayArtworkUri
        val chan = currentChannelId
        destroy()
        acquireOrCreate(ctx)
        playUrl(url, title, subtitle, art, channelId = chan)
        currentChannelId = chan
        // destroy() cleared the flag's backing player but not the field; keep
        // it set so this session stays on the working sink.
        audioSinkFallback = true
    }

    /** Reset watchdog state for a brand-new stream (iOS play(url:)/swapStream). */
    private fun resetWatchdogStateForNewStream() {
        // A fresh stream gets the tune-path start gate, not a stale hold.
        resumeGateActive = false
        resumeGateArmedAtMs = 0L
        resumeGateLoggedTargetMs = 0L
        hasReachedPlaybackRestart = false
        consecutiveReloads = 0
        lastForcedReloadAtMs = 0L
        lastKnownPositionMs = 0L
        lastPositionAdvanceAtMs = SystemClock.elapsedRealtime()
        videoFrameRendered = false
        noFrameHealAttempts = 0
        noDataHealAttempts = 0
        stoppingRetries = 0
        stoppingFirstAtMs = 0L
        LoggingPlayerListener.sawTracksChangedSincePrime = false
        live503HandledKey = null
        live503HandledAtMs = 0L
        live503OwnedRecovery = false
        stoppingRetryJob?.cancel()
        stoppingRetryJob = null
        serverReason = null
        _streamUnavailable.value = false
        _lastErrorText.value = null
        _connectionLimit.value = null
        // A fresh stream is starting; if it fails, markStreamUnavailable will
        // re-preserve the current URL. (retryUnavailable already captured its
        // URL before the playUrl that lands here, so clearing is safe.)
        reconnectUrl = null
        reconnectChannelId = null
        decoderRetryUsed = false
        streamPrimedAtMs = lastPositionAdvanceAtMs
    }

    private object LoggingPlayerListener : Player.Listener {

        /** Whether ANY tracks-changed callback has arrived for the current
         *  prime. The first one can precede track discovery, so the "no audio
         *  track group" warning waits for the second. */
        @Volatile
        var sawTracksChangedSincePrime = false
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} (${error.errorCode})", error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val label = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.i(TAG, "ExoPlayer state -> $label")
        }

        // GH #8 diagnostic (release-safe, so it lands in a user's captured
        // debug log): "No Sound / no audio track" on some devices (e.g.
        // Chromecast with Google TV). When the stream carries an audio track
        // the device can decode in neither hardware nor the bundled FFmpeg
        // software decoder (since 2026-09-14 the bundled build covers aac, ac3,
        // eac3, dca, truehd, mlp, mp2, mp3, flac and alac, so this path is now
        // reached only by codecs outside that set, e.g. Opus or Vorbis on a
        // device with no hardware decoder for them), ExoPlayer exposes the
        // group but marks it
        // unsupported, and the track selector offers nothing -- silent
        // playback with "no audio track available". Logging every audio group
        // with its codec + per-track support pins the exact culprit codec
        // (e.g. ac-3 support=UNSUPPORTED_TYPE) from a user's log, which the
        // AnalyticsListener format hooks cannot show because no audio renderer
        // ever selects the track. Distinguishes that from "stream has no audio
        // group at all" (a demux/remux problem, not a decoder gap).
        override fun onTracksChanged(tracks: Tracks) {
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audioGroups.isEmpty()) {
                // The FIRST callback after a prime routinely lands before track
                // discovery finishes (Streamer 2026-09-14: this warning at
                // 14:25:11.656, AC-3 selected 370 ms later), so it is never
                // evidence of a demux problem. Warn only if a later callback
                // still has no audio group.
                if (sawTracksChangedSincePrime) {
                    Log.w(TAG, "ExoPlayer audio: stream exposes NO audio track group")
                } else {
                    sawTracksChangedSincePrime = true
                }
                return
            }
            sawTracksChangedSincePrime = true
            audioGroups.forEachIndexed { g, group ->
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val support = when (group.getTrackSupport(i)) {
                        C.FORMAT_HANDLED -> "HANDLED"
                        C.FORMAT_EXCEEDS_CAPABILITIES -> "EXCEEDS_CAPABILITIES"
                        C.FORMAT_UNSUPPORTED_DRM -> "UNSUPPORTED_DRM"
                        C.FORMAT_UNSUPPORTED_SUBTYPE -> "UNSUPPORTED_SUBTYPE"
                        C.FORMAT_UNSUPPORTED_TYPE -> "UNSUPPORTED_TYPE"
                        else -> "UNKNOWN"
                    }
                    Log.i(
                        TAG,
                        "ExoPlayer audio track g$g:$i -> ${f.sampleMimeType} " +
                            "codecs=${f.codecs} ${f.channelCount}ch ${f.sampleRate}Hz " +
                            "support=$support selected=${group.isTrackSelected(i)}",
                    )
                }
            }
        }
    }

    /**
     * Debug-only player diagnostics, the Android analog of iOS's libmpv log
     * bridge ([MPV-DIAG]): the chosen decoder (hwdec path, e.g.
     * c2.qti.avc.decoder), input format changes, dropped frames, audio
     * underruns, and video size. Registered only under BuildConfig.DEBUG
     * (mirrors iOS's `#if DEBUG` gate); tagged for `adb logcat -s AerioPlayerDiag`.
     */
    /** Release-safe network diagnostics: logs every media LOAD error (HTTP
     *  connect/read failures, source errors) into the shareable log. Player
     *  errors are already logged, but those are only the TERMINAL failure after
     *  ExoPlayer's internal retries; a load that fails and gets retried (or a
     *  connection that opens then dies) left no trace before this. Kept minimal
     *  and always attached so black-screen reports (GH #32) carry the real
     *  network cause. URIs are redacted for embedded credentials by
     *  LogSanitizer before the log is shared. */
    private inner class LoadErrorDiagnosticsListener : AnalyticsListener {
        /** Always-on: which audio renderer actually claimed the track on this
         *  tune. The decoder name distinguishes the platform MediaCodec
         *  renderer (e.g. c2.android.ac3.decoder, OMX.google.raw.decoder) from
         *  the bundled FFmpeg fallback renderer (names starting "ffmpeg"). The
         *  2026-09-14 Shield E-AC-3 silence was exactly this gap: with
         *  passthrough off the platform exposed E-AC-3 for bitstream only, so
         *  the track fell through to the FFmpeg renderer, which at the time no
         *  longer carried an eac3 decoder and refused it as well. Debug builds
         *  log the same name under AerioPlayerDiag; this copy is release-safe
         *  so a user's shared log answers "which renderer took the audio". */
        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val renderer =
                if (decoderName.startsWith("ffmpeg", ignoreCase = true)) "FFmpeg extension renderer"
                else "platform MediaCodec renderer"
            Log.i(TAG, "audio renderer -> $renderer (decoder=$decoderName)")
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            // wasCanceled here does NOT mean "the user cancelled": Media3 sets
            // it from `!loadErrorAction.isRetry()` (ProgressiveMediaPeriod /
            // HlsSampleStreamWrapper), i.e. "this load will not be retried".
            // Live503LoadErrorPolicy deliberately returns C.TIME_UNSET for a
            // 503, which makes the action DONT_RETRY_FATAL -- so every 503 the
            // policy handed us arrived here flagged wasCanceled and the old
            // blanket early-return threw it away before handleLive503 ever ran
            // (Frankie B. Shield log 2026-09-14 18:21:03: 503, no parse, no
            // Retry-After, straight into the generic in-place reload ladder and
            // the "Channel Unavailable ... Retrying in 4s" card).
            val limit = DispatcharrConnectionLimit.parse(error)
            val is503 = Dispatcharr503.parse(error) != null
            if (wasCanceled && !is503 && limit == null) return
            Log.w(
                TAG,
                "load error uri=${loadEventInfo.uri} " +
                    "${error.javaClass.simpleName}: ${error.message}${causeChain(error)}",
            )
            // A connection-limit refusal owns the error outright: the 503 flavor
            // of it must not start the stopping ladder or the failover walk.
            if (limit != null) {
                handleConnectionLimit(error)
                return
            }
            // A Dispatcharr 503 carries the server's OWN reason; act on it here
            // rather than letting the retry ladders guess (see handleLive503).
            handleLive503(error)
        }

        /** Append the CLASS names of the cause chain (GH #32). Media3 wraps an
         *  unexpected RuntimeException from a DataSource as UnexpectedLoaderException
         *  ("Unexpected IllegalArgumentException"), whose own message hides the real
         *  fault class; walking .cause surfaces it. Deliberately logs class names
         *  ONLY -- a cause message can embed a request header value (e.g. okhttp's
         *  "Unexpected char ... in <name> value: <value>"), which may be a credential. */
        private fun causeChain(error: Throwable): String {
            val sb = StringBuilder()
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 4) {
                sb.append(" <- ").append(cause.javaClass.simpleName)
                cause = cause.cause
                depth++
            }
            return sb.toString()
        }
    }

    private object DiagnosticAnalyticsListener : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG_DIAG, "video decoder -> $decoderName (init ${initializationDurationMs}ms)")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG_DIAG, "audio decoder -> $decoderName")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(
                TAG_DIAG,
                "video format -> ${format.sampleMimeType} ${format.width}x${format.height} " +
                    "@${format.frameRate}fps ${format.bitrate}bps codecs=${format.codecs}",
            )
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(
                TAG_DIAG,
                "audio format -> ${format.sampleMimeType} ${format.sampleRate}Hz " +
                    "${format.channelCount}ch ${format.bitrate}bps",
            )
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            Log.w(TAG_DIAG, "dropped $droppedFrames frames over ${elapsedMs}ms")
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            Log.w(
                TAG_DIAG,
                "audio underrun: bufferMs=$bufferSizeMs elapsedSinceFeed=${elapsedSinceLastFeedMs}ms",
            )
        }

        override fun onVideoSizeChanged(
            eventTime: AnalyticsListener.EventTime,
            videoSize: VideoSize,
        ) {
            Log.i(TAG_DIAG, "video size -> ${videoSize.width}x${videoSize.height}")
        }
    }

    companion object {
        private const val TAG = "AerioExoPlayer"
        /** Ingest silence that COUNTS toward a stall, once the player is also
         *  starving. Silence alone means nothing: the Dispatcharr proxy delivers
         *  in bursts (Streamer 2026-09-14: routine 8 to 9.5 s gaps with 10 s
         *  buffered and zero rebuffers), which produced ~200 false Reconnecting
         *  overlays in 22 minutes of perfect playback and armed the dead-session
         *  rule that turned one teardown into a 52 s request storm. */
        const val INGEST_STALL_STATUS_MS = 2_000L

        /** Buffered-ahead (bufferedPosition - currentPosition) below which the
         *  player is actually starving. THIS is what "stalled" means. */
        const val STALL_BUFFER_AHEAD_MS = 1_500L

        /** Playhead frozen this long while we intend to play = a real stall
         *  worth showing "Reconnecting" for. */
        const val STALL_OVERLAY_FROZEN_MS = 1_000L

        /** Playhead frozen this long after a switch = the new upstream is not
         *  flowing yet; the watch starts logging and keeps the switch window open. */
        const val SWITCH_NO_PROGRESS_MS = 6_000L
        private const val SWITCH_POLL_MS = 250L
        /** Interval of the "[SWITCH] still waiting for new upstream" log. */
        private const val SWITCH_WAIT_LOG_MS = 10_000L
        /** How far past the buffered head at the switch the playhead must get
         *  before playback counts as running on the new stream's bytes. */
        private const val SWITCH_PAST_BUFFER_MS = 1_000L
        /** New-stream media that must be loaded past the boundary before old
         *  samples are dropped. Small by design (AMD 2026-09-16): the earliest
         *  switch onto the new stream is the intent, and the keyframe gate in
         *  [SwitchSkipMediaSource] is what guarantees a clean decoder start, so
         *  there is no reason to play out a 16 to 22 s old buffer first. */
        private const val SWITCH_SKIP_MIN_NEW_DATA_MS = 750L
        /** A skip that has not seen new data by now is abandoned (old buffer plays out). */
        private const val SWITCH_SKIP_TIMEOUT_MS = 30_000L
        /** Underruns inside this window after a jump do not arm the resume gate. */
        private const val SWITCH_JUMP_GRACE_MS = 5_000L
        /** Longest the new stream's audio is muted waiting for its first video
         *  frame after a switch jump; on timeout the normal stall recovery owns it. */
        private const val SWITCH_AUDIO_HOLD_MS = 3_000L
        private const val SWITCH_AUDIO_HOLD_POLL_MS = 100L
        /** After a switch follow, only a player error or a confirmed dead
         *  session may reopen the channel. */
        private const val SWITCH_WINDOW_MS = 30_000L
        /** Raw-TS live ingest silence required (with an empty buffer) before
         *  the watchdog reopens the same channel. */
        private const val LIVE_INGEST_RELOAD_SILENCE_MS = 25_000L
        /** A 503 this soon after a same-channel reopen gets one quick retry. */
        private const val SAME_CHANNEL_REOPEN_WINDOW_MS = 3_000L
        private const val SAME_CHANNEL_QUICK_RETRY_MS = 1_000L
        /** Stalls this soon after a same-channel re-prime do not train the start buffer. */
        private const val SAME_CHANNEL_LEARN_QUIET_MS = 30_000L

        /** Buffered-ahead treated as empty for the overlay (with ingest silent). */
        const val STALL_OVERLAY_EMPTY_BUFFER_MS = 250L

        /** Baseline live start gate (bufferForPlaybackMs). See the LoadControl
         *  comment in [acquireOrCreate] for why 1_200 and not 500 or 2_000. */
        private const val LIVE_START_GATE_DEFAULT_MS = 1_200

        /** Kept Live re-tune: how far behind the kept buffer's head the
         *  player starts. Wider than Dispatcharr's 8 to 9.5 s delivery bursts
         *  so 1x playback never reaches the write head between bursts. */
        private const val ADOPT_LIVE_CUSHION_MS = 12_000L
        /** Ceiling on the learned hold-back: past 10 s the tap-to-motion cost
         *  outweighs riding out the gap. */
        private const val LIVE_START_GATE_MAX_MS = 10_000
        /** At or above this media-time ratio the feed is keeping up with real
         *  time, so a stall means the bytes arrived in bursts and the start
         *  cushion was too shallow. */
        private const val HOLDBACK_MEDIA_RATIO_MIN = 0.9
        /** How long a learned hold-back stays valid. Past this a tune discards it
         *  and uses the base gate, so one bad session does not pin a channel
         *  (Logan 2026-09-12). A fresh learn re-arms it. */
        private const val HOLDBACK_LEARNED_TTL_MS = 30L * 60L * 1_000L
        private const val RESTAMP_DEBOUNCE_MS = 20_000L
        private const val HLS_GATE_KEY_FLAG = 1_000_000
        const val FORCE_HLS_MIN_BUFFER_MS = 15_000
        const val FORCE_HLS_START_GATE_MS = 6_500
        private const val FORCE_HLS_TARGET_OFFSET_MS = ForceHls.DEFAULT_TARGET_OFFSET_MS
        private const val FORCE_HLS_MIN_OFFSET_MS = 8_000L
        private const val FORCE_HLS_MAX_OFFSET_MS = 30_000L
        private const val FORCE_HLS_LEARNED_OFFSET_MAX_MS = 20_000
        private const val FORCE_HLS_OFFSET_KEY_SUFFIX = "#hls_live_offset"
        /** Cushion added on top of the worst observed delivery gap so the feed
         *  has room to land the next burst before the buffer runs dry. */
        private const val RESUME_GATE_HEADROOM_MS = 2_000L
        /** Ceiling on the resume gate. A live feed delivers at about real time,
         *  so every millisecond of cushion is a millisecond of wait, but an 8 s
         *  ceiling was BELOW the gaps it had to ride out (Apple device result
         *  2026-09-13: 7-8 s gaps, gate pinned at 8 s, 9 stalls in 90 s). 12 s
         *  clears a gap of that size with margin; past it the hold costs more
         *  than the stall it prevents, and the repeat-stall rejoin takes over. */
        private const val RESUME_GATE_CAP_MS = 12_000L
        /** The gate can only hold what the LoadControl will keep, so a live
         *  player is built with at least this much max buffer (the default
         *  minBufferMs * 2 tops out around 10.4 s at the base start gate, which
         *  would silently clamp a 12 s gate to 9.4 s). */
        private const val LIVE_MAX_BUFFER_FLOOR_MS = 14_000
        /** Two underruns inside this window mean the gate is not winning on this
         *  feed, so the next recovery rejoins behind the live edge instead. */
        private const val REPEAT_STALL_WINDOW_MS = 60_000L
        /** Minimum spacing between rejoins: a rejoin costs a re-prime or a jump
         *  behind live, so at most one per 3 minutes. */
        private const val REJOIN_COOLDOWN_MS = 180_000L
        /** Floor on the rejoin seek-back: less than this lands back inside the
         *  same delivery gap. */
        private const val REJOIN_MIN_BACK_MS = 8_000L
        /** Ceiling on the rejoin seek-back: past this the user is watching
         *  meaningfully old live. */
        private const val REJOIN_MAX_BACK_MS = 18_000L
        /** Safety margin kept off the tail of the local window so a rejoin never
         *  seeks to or past the oldest byte retained. */
        private const val REJOIN_WINDOW_MARGIN_MS = 1_000L
        /** Hard timeout: a feed that cannot rebuild the cushion in 25 s is not
         *  going to, so resume with whatever is buffered and log why. */
        private const val RESUME_GATE_TIMEOUT_MS = 25_000L
        private const val TAG_DIAG = "AerioPlayerDiag"

        /** How long after a tune a decoder failure still counts as the codec
         *  handover race (session2.txt: the error landed 2.5 s after the flip). */
        private const val DECODER_RETRY_WINDOW_MS = 3_000L
        /** GH #107: full player + surface rebuilds allowed per window. */
        private const val MAX_FULL_REBUILDS = 2
        /** GH #107: rolling window the rebuild budget is counted in. */
        private const val FULL_REBUILD_WINDOW_MS = 90_000L
        /** GH #107: settle time between dropping the old surface and tuning
         *  into the new one (tripled on the second attempt). */
        private const val FULL_REBUILD_SETTLE_MS = 600L
        /** GH #107: a tune whose audio clock has advanced this far with no
         *  first video frame is rendering into a dead surface. */
        private const val DEAD_SURFACE_AUDIO_ADVANCE_MS = 3_000L

        /** How long one 503 stays "already decided" while its load error and the
         *  terminal player error it becomes both reach [handleLive503]. Shorter
         *  than [Dispatcharr503.DEFAULT_RETRY_AFTER_MS] so the NEXT 503, the one
         *  answering a scheduled retry, is always judged fresh. */
        private const val LIVE_503_DEDUPE_MS = 500L
        /** Longest a new live source waits for the superseded source's
         *  connection to be closed before opening its own anyway. */
        private const val RETIRE_GATE_MAX_WAIT_MS = 1_500L

        /**
         * Default player User-Agent. Without an explicit UA, Media3's
         * DefaultHttpDataSource falls back to the platform default
         * ("Dalvik/2.1.0 ..."), which Xtream reseller panels' anti-restream
         * WAFs fingerprint as a bot and drop on LIVE ("connection closed
         * before status line") while leaving VOD /movie/ files ungated. Same
         * shape as DispatcharrClient's UA + iOS DeviceInfo.defaultUserAgent.
         */
        private val DEFAULT_PLAYBACK_USER_AGENT =
            "AerioTV/${BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})"
    }
}
