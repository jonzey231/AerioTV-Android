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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull

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
) {

    var player: ExoPlayer? = null
        private set

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

    /** Application context captured at first acquire so playUrl can
     *  self-heal when called before/after the player exists. */
    private var appContext: android.content.Context? = null

    /** Passthrough state the current player was built with; a pref flip
     *  forces a rebuild because sink capabilities are fixed at build. */
    private var builtWithPassthrough: Boolean? = null
    /** Buffer floor (ms) the current player was built with; a pref flip forces
     *  a rebuild because the LoadControl is fixed at build time. */
    private var builtWithBufferFloorMs: Int? = null
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
     *  elsewhere (Auto / background). iOS analog: PlayerSession.failoverRetryCurrent. */
    @Volatile var onTerminalErrorRebuildUrl: (suspend () -> String?)? = null

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
    }

    private val watchdogScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var watchdogJob: Job? = null
    private var lastPositionAdvanceAtMs = 0L
    private var lastKnownPositionMs = 0L
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

    // ---- shared keepalive re-prime (manual Switch Stream + live follow-poller) ----
    private val reprimeMutex = Mutex()
    @Volatile private var reprimeInFlight = false
    /** True while a keepalive re-prime is mid-flight; the follow-poller parks on it. */
    val isReprimeInFlight: Boolean get() = reprimeInFlight

    /**
     * Re-prime the SAME proxy [url], holding a SECOND bare AllowAny GET to it open
     * across the flush so the channel's client count never hits 0. The server's
     * stop_channel (default channel_shutdown_delay=0) otherwise deletes
     * channel_stream:{id} and the reconnect cold-resolves to the channel DEFAULT
     * stream. This forces ProgressiveMediaSource to re-sync onto a stream
     * Dispatcharr swapped in place (manual change_stream, WebUI switch, or
     * automatic failover -- all keep our connection open + only mutate
     * metadata.url, so ExoPlayer never self-flushes).
     *
     * Serialised via [reprimeMutex] and gated on the shared [reloadCooldownMs]
     * (same window the stall watchdog uses) UNLESS [bypassCooldown] -- the
     * user-initiated manual switch sets it so its own re-prime always runs.
     * Returns true if the re-prime ran.
     */
    suspend fun reprimeWithKeepalive(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
        bypassCooldown: Boolean = false,
        keepaliveHoldMs: Long = 5_000L,
    ): Boolean = reprimeMutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!bypassCooldown && now - lastForcedReloadAtMs < reloadCooldownMs) {
            Log.i(TAG, "[FOLLOW] re-prime skipped (within ${reloadCooldownMs}ms cooldown)")
            return@withLock false
        }
        lastForcedReloadAtMs = now
        reprimeInFlight = true
        try {
            val connHolder = java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>(null)
            val connected = CompletableDeferred<Boolean>()
            val keepAlive = watchdogScope.launch(Dispatchers.IO) {
                try {
                    val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 8000
                        requestMethod = "GET"
                        httpHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                        setRequestProperty("User-Agent", "AerioTV-switch-keepalive")
                    }
                    connHolder.set(c)
                    c.inputStream.use { ins ->
                        val buf = ByteArray(32 * 1024)
                        if (ins.read(buf) >= 0 && !connected.isCompleted) connected.complete(true)
                        while (isActive) { if (ins.read(buf) < 0) break }
                    }
                } catch (_: Throwable) {
                    // best-effort; re-prime proceeds regardless
                } finally {
                    if (!connected.isCompleted) connected.complete(false)
                    runCatching { connHolder.get()?.disconnect() }
                }
            }
            // Attach (or definitively fail) the keepalive before dropping the player's connection.
            withTimeoutOrNull(4_000L) { connected.await() }
            withContext(Dispatchers.Main) { playUrl(url, title, subtitle, artworkUri) }
            // Hold until ExoPlayer's reconnect is established (client count back >= 2).
            delay(keepaliveHoldMs)
            keepAlive.cancel()
            runCatching { connHolder.get()?.disconnect() }
            true
        } finally {
            reprimeInFlight = false
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
    /** The URL to replay when the unavailable overlay retries. Preserved by
     *  [markStreamUnavailable] BEFORE it calls [stop] (which nulls
     *  [lastPlayUrl]) - without this every [retryUnavailable] hit the
     *  `lastPlayUrl ?: return` guard and no-op'd, so the countdown cycled
     *  forever but never re-tuned and a returning server never recovered
     *  (Streamer field test 2026-07-12: Dispatcharr container killed then
     *  restarted, retry never reconnected). Cleared on a fresh [playUrl]. */
    private var reconnectUrl: String? = null

    /** Task #150: manual/auto retry for the unavailable overlay. Clears the
     *  flag, resets the no-data heal budget, and re-primes the last URL --
     *  through the LAN/WAN re-probe hook when the screen wired one, so a
     *  network flip since the failure is picked up. */
    fun retryUnavailable() {
        // lastPlayUrl is null here (markStreamUnavailable -> stop() cleared it),
        // so fall back to the URL preserved at markStreamUnavailable time.
        val url = lastPlayUrl ?: reconnectUrl ?: return
        _streamUnavailable.value = false
        _lastErrorText.value = null
        noDataHealAttempts = 0
        val hook = onTerminalErrorRebuildUrl
        if (hook != null) {
            watchdogScope.launch {
                val fresh = runCatching { hook() }.getOrNull()
                withContext(Dispatchers.Main) {
                    playUrl(
                        if (!fresh.isNullOrBlank()) fresh else url,
                        lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri,
                    )
                }
            }
        } else {
            playUrl(url, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri)
        }
    }

    /** Arms the watchdog on first steady playback + recovers on a hard error. */
    private val watchdogListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && player?.isPlaying == true) armWatchdog()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && player?.playbackState == Player.STATE_READY) armWatchdog()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Task #150: remember the failure in user-showable form for the
            // unavailable overlay (self-heals below may still recover; the
            // text only surfaces if the stream ends up flagged unavailable).
            _lastErrorText.value = error.cause?.message
                ?.let { "${error.errorCodeName}: $it" } ?: error.errorCodeName
            // GH #8: the forced-PCM sink produced no audio / failed to init on
            // some devices. Rebuild once with the stock context sink (which is
            // the path that works everywhere) and replay. A plain forceReload
            // would just hit the same dead sink.
            if (!audioSinkFallback &&
                (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
            ) {
                Log.w(TAG, "[AUDIO-HEAL] sink failed (${error.errorCodeName}); rebuilding with stock context sink")
                audioSinkFallback = true
                rebuildWithStockAudioAndReplay()
                return
            }
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
            if (lastPlayUrl != null) {
                val hook = onTerminalErrorRebuildUrl
                if (hook != null) {
                    watchdogScope.launch {
                        val fresh = runCatching { hook() }.getOrNull()
                        if (!fresh.isNullOrBlank() && fresh != lastPlayUrl) {
                            Log.w(TAG, "[RETUNE] terminal error; re-priming onto reprobed url $fresh")
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
            // The one line that lets a user log definitively separate "video
            // rendered" from "decoded but never painted". Once per prime, so
            // it's cheap enough for release builds.
            Log.i(TAG, "first video frame rendered ch=$currentChannelId (+${SystemClock.elapsedRealtime() - streamPrimedAtMs}ms)")
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
            .setUserAgent(headerUa ?: DEFAULT_PLAYBACK_USER_AGENT)
        val nonUaHeaders = h.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
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
        // watchdogReloadEnabled is kept current by the autoRecoverFrozenStreams
        // collector launched in init{}; no blocking read needed here.
        player?.let { existing ->
            if (builtWithPassthrough == audioPassthrough && builtWithBufferFloorMs == bufferFloorMs) return existing
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
        val minBufferMs = maxOf(4_000, bufferFloorMs)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ minBufferMs,
                /* maxBufferMs = */ maxOf(8_000, minBufferMs),
                /* bufferForPlaybackMs = */ 1_200,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val fresh = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
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
                addListener(LoggingPlayerListener)
                addListener(watchdogListener)
                // Always-on: network LOAD errors into the shareable log (GH #32).
                addAnalyticsListener(LoadErrorDiagnosticsListener)
                // Debug-only rich diagnostics firehose (codec / hwdec path,
                // input format changes, dropped frames, audio underruns) -- the
                // Android analog of iOS's libmpv log bridge. Read with
                // `adb logcat -s AerioPlayerDiag`.
                if (BuildConfig.DEBUG) addAnalyticsListener(DiagnosticAnalyticsListener)
                // Repeat off for live; setRepeatMode(REPEAT_MODE_ONE) is
                // a VOD concern.
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
            }

        player = fresh
        _playerInstance.value = fresh
        builtWithPassthrough = audioPassthrough
        builtWithBufferFloorMs = bufferFloorMs
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
        val mediaItem = mediaItemBuilder.build()
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
                    .createMediaSource(mediaItem)
            }
            url.endsWith(".m3u8", ignoreCase = true) -> {
                HlsMediaSource.Factory(dataSourceFactory)
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

    /** TS-only extractor factory shared by the live raw-TS path and the
     *  timeshift buffer reader (same no-sniff rationale, see buildMediaSource). */
    private fun tsOnlyExtractorsFactory(): ExtractorsFactory = ExtractorsFactory {
        val all: Array<Extractor> = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            .createExtractors()
        val tsOnly: List<Extractor> = all.filterIsInstance<TsExtractor>()
        if (tsOnly.isNotEmpty()) tsOnly.toTypedArray() else all
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

    /** Live Rewind can only buffer what the tee mirrors: raw MPEG-TS.
     *  PlayerScreen gates session start on this so HLS/DASH live channels
     *  never show a transport over a permanently empty buffer. */
    fun canBufferLiveRewind(url: String): Boolean = isRawTsUrl(url)

    fun playTimeshift(fromWallMs: Long): Boolean {
        val p = player ?: return false
        val ts = timeshift.get()
        if (ts.activeWriter == null) return false
        isTimeshifting = true
        val factory = com.aeriotv.android.core.timeshift.TimeshiftDataSource.Factory { ts.activeWriter }
        val item = MediaItem.Builder()
            .setUri(com.aeriotv.android.core.timeshift.TimeshiftDataSource.uri(fromWallMs))
            .setMediaId("live-rewind")
            .build()
        val source = ProgressiveMediaSource.Factory(factory, tsOnlyExtractorsFactory())
            .createMediaSource(item)
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        ts.onEnterTimeshift(fromWallMs)
        Log.i(TAG, "[REWIND] entered timeshift at $fromWallMs")
        return true
    }

    /** Leave the timeshift buffer and re-tune the direct live stream. */
    fun goLive() {
        if (!isTimeshifting) return
        isTimeshifting = false
        timeshift.get().onGoLive()
        val url = lastPlayUrl ?: return
        Log.i(TAG, "[REWIND] go live -> re-tune direct stream")
        playUrl(url, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri)
    }

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
        if (!catchupRetryPass) catchupDecodeRetries = 0
        lastCatchupUrl = url
        lastCatchupTitle = title
        lastCatchupSubtitle = subtitle
        lastCatchupArtworkUri = artworkUri
        resetWatchdogStateForNewStream()
        setVideoTrackEnabled(true)
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
        val source = ProgressiveMediaSource.Factory(
            httpDataSourceFactory(isLive = true),
            tsOnlyExtractorsFactory(),
        ).createMediaSource(mediaItem)
        p.setMediaSource(source)
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
    fun playUrl(
        url: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUri: android.net.Uri? = null,
        // GH #27: #KODIPROP DRM signalling for encrypted DASH channels
        // (license_type + license_key). Null for everything else.
        drmLicenseType: String? = null,
        drmLicenseKey: String? = null,
    ) {
        // Self-heal: a channel tap can land before the persistent window's
        // factory ran, or after destroy() released the instance. Swallowing
        // the call here left the screen dead until the user picked a
        // DIFFERENT channel (PlayerScreen stamps currentChannelId after this
        // call, so re-selecting the same one was a no-op).
        val p = player ?: appContext?.let { acquireOrCreate(it) } ?: run {
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
        isCatchup = false
        timeshiftErrorRetries = 0
        lastPlayUrl = url
        lastPlayTitle = title
        lastPlaySubtitle = subtitle
        lastPlayArtworkUri = artworkUri
        lastPlayDrmType = drmLicenseType
        lastPlayDrmKey = drmLicenseKey
        resetWatchdogStateForNewStream()
        // watchdogReloadEnabled is kept current by the collector in init{}; the
        // cached value reflects the latest pref without blocking the main thread.
        // Foreground playback wants video; re-enable it in case an Android Auto
        // session previously dropped the video track on this shared player.
        setVideoTrackEnabled(true)
        val source = buildMediaSource(url, title, subtitle, artworkUri, drmLicenseType, drmLicenseKey)
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
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
        val factory = OkHttpDataSource.Factory(liveHttpClient)
            .setUserAgent(headerUa ?: DEFAULT_PLAYBACK_USER_AGENT)
        val nonUaHeaders = httpHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (nonUaHeaders.isNotEmpty()) factory.setDefaultRequestProperties(nonUaHeaders)
        return factory
    }

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
        currentChannelId = null
        // Disarm the stall watchdog so a deliberate stop isn't seen as a wedge.
        hasReachedPlaybackRestart = false
        lastPlayUrl = null
        isCatchup = false
        p.stop()
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

    /** Full teardown for the X-close button. Releases the codec,
     *  audio renderer, and DataSource. Next acquire creates fresh. */
    fun destroy() {
        val p = player ?: return
        player = null
        _playerInstance.value = null
        currentChannelId = null
        watchdogJob?.cancel()
        watchdogJob = null
        lastPlayUrl = null
        try {
            p.removeListener(LoggingPlayerListener)
            p.removeListener(watchdogListener)
            p.release()
        } catch (t: Throwable) {
            Log.w(TAG, "ExoPlayer release failed", t)
        }
    }

    private fun armWatchdog() {
        hasReachedPlaybackRestart = true
        lastPositionAdvanceAtMs = SystemClock.elapsedRealtime()
        lastKnownPositionMs = player?.currentPosition ?: 0L
        // Measure the no-frame window from steady playback, not from prime,
        // so a slow cold start is never mistaken for a black screen.
        if (!videoFrameRendered) streamPrimedAtMs = lastPositionAdvanceAtMs
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = watchdogScope.launch {
            while (isActive) {
                delay(watchdogPollMs)
                val p = player ?: continue
                val now = SystemClock.elapsedRealtime()

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

                // Only when we intend to play, have a url to reload, have reached
                // steady playback at least once (skip cold-start probes + user
                // pauses), and aren't at end-of-stream.
                if (lastPlayUrl == null || isTimeshifting || isCatchup || !p.playWhenReady ||
                    !hasReachedPlaybackRestart || p.playbackState == Player.STATE_ENDED
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
                if (pos > lastKnownPositionMs) {
                    lastKnownPositionMs = pos
                    lastPositionAdvanceAtMs = now
                    consecutiveReloads = 0
                    continue
                }
                val staleMs = now - lastPositionAdvanceAtMs
                if (watchdogReloadEnabled && staleMs >= staleReloadThresholdMs) {
                    forceReload("stale=${staleMs}ms")
                }
            }
        }
    }

    /** Re-prime the demuxer + decoder against the SAME url (mpv loadfile
     *  replace). Returns true when a reload actually ran (false while inside
     *  the cooldown or past the attempt cap). */
    private fun forceReload(reason: String): Boolean {
        if (isTimeshifting || isCatchup) return false
        val p = player ?: return false
        val url = lastPlayUrl ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastForcedReloadAtMs < reloadCooldownMs) return false   // shared 5s cooldown
        if (consecutiveReloads >= maxConsecutiveReloads) {
            Log.w(TAG, "[MPV-RELOAD] giving up after $consecutiveReloads attempts ch=$currentChannelId reason=$reason")
            return false
        }
        lastForcedReloadAtMs = now
        consecutiveReloads++
        Log.w(TAG, "[MPV-RELOAD] live stall reload ch=$currentChannelId reason=$reason attempt=$consecutiveReloads")
        // Disarm until the re-primed stream reaches steady playback again.
        hasReachedPlaybackRestart = false
        lastKnownPositionMs = 0L
        lastPositionAdvanceAtMs = now
        videoFrameRendered = false
        streamPrimedAtMs = now
        val source = buildMediaSource(
            url, lastPlayTitle, lastPlaySubtitle, lastPlayArtworkUri,
            lastPlayDrmType, lastPlayDrmKey,
        )
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        return true
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
        if (_lastErrorText.value == null) {
            _lastErrorText.value = "No data received from the stream"
        }
        _streamUnavailable.value = true
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
        val title = lastPlayTitle
        val subtitle = lastPlaySubtitle
        val art = lastPlayArtworkUri
        val chan = currentChannelId
        val attempts = noFrameHealAttempts
        destroy()
        acquireOrCreate(ctx)
        playUrl(url, title, subtitle, art)
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
        playUrl(url, title, subtitle, art)
        currentChannelId = chan
        // destroy() cleared the flag's backing player but not the field; keep
        // it set so this session stays on the working sink.
        audioSinkFallback = true
    }

    /** Reset watchdog state for a brand-new stream (iOS play(url:)/swapStream). */
    private fun resetWatchdogStateForNewStream() {
        hasReachedPlaybackRestart = false
        consecutiveReloads = 0
        lastForcedReloadAtMs = 0L
        lastKnownPositionMs = 0L
        lastPositionAdvanceAtMs = SystemClock.elapsedRealtime()
        videoFrameRendered = false
        noFrameHealAttempts = 0
        noDataHealAttempts = 0
        _streamUnavailable.value = false
        _lastErrorText.value = null
        // A fresh stream is starting; if it fails, markStreamUnavailable will
        // re-preserve the current URL. (retryUnavailable already captured its
        // URL before the playUrl that lands here, so clearing is safe.)
        reconnectUrl = null
        streamPrimedAtMs = lastPositionAdvanceAtMs
    }

    private object LoggingPlayerListener : Player.Listener {
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
        // software decoder, ExoPlayer exposes the group but marks it
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
                Log.w(TAG, "ExoPlayer audio: stream exposes NO audio track group")
                return
            }
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
    private object LoadErrorDiagnosticsListener : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            if (wasCanceled) return
            Log.w(
                TAG,
                "load error uri=${loadEventInfo.uri} " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
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
        private const val TAG_DIAG = "AerioPlayerDiag"

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
