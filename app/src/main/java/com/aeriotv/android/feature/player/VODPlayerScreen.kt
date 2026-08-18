package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.data.VodLearnedStream
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.pip.supportsPip
import com.aeriotv.android.feature.ondemand.VodProviderOption
import com.aeriotv.android.feature.ondemand.VodVersionPickerSheet
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.settings.bufferMillisFor
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import com.aeriotv.android.ui.tv.tvFocusScale
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "VODPlayerScreen"

/**
 * GH #78: how long the "Watch from Beginning" watcher follows an in-progress
 * DVR recording after prepare(), waiting to see whether media3's live-edge
 * default overrode our pinned start position. Generous on purpose: on a cold
 * app launch on a low-power Google TV box, a 25-minute recording's playlist
 * (375+ segments at Dispatcharr's -hls_time 4) can take many seconds to
 * resolve, and the previous 6-second cap expired before it ever did.
 */
private const val WATCH_FROM_START_WINDOW_MS = 45_000L

/**
 * GH #78: target live offset used to drag an in-progress recording's live
 * window default start position back to its FIRST segment. Any value larger
 * than the recording can possibly be works, because HlsMediaSource constrains
 * it to the playlist duration before subtracting; 30 days is far beyond any
 * real recording and stays clear of overflow in Util.msToUs.
 */
private const val WATCH_FROM_START_TARGET_OFFSET_MS = 30L * 24L * 60L * 60L * 1000L

/** Cap on re-corrections so a window that refuses to hold position 0 degrades
 *  to "starts at live" rather than an endless seek loop. */
private const val WATCH_FROM_START_MAX_CORRECTIONS = 5
private const val AUTO_HIDE_MS = 4_000L

/**
 * Android-TV VOD transport focus zones (Archie spec, task #44). The whole
 * D-pad model is driven from the single root onPreviewKeyEvent below, so
 * "focus" here is app-owned state rather than Compose focus traversal
 * (the catch-all handler swallows every D-pad key before a child could
 * receive it). PlayPause is the default landing spot when controls reveal;
 * LEFT/RIGHT cycle Rewind <-> PlayPause <-> Forward; UP enters Scrubber.
 */
private enum class TvVodFocusZone { None, Rewind, PlayPause, Forward, Options, Scrubber }

/**
 * VOD playback. Task #62: rebuilt on Media3 ExoPlayer.
 *
 * The earlier libmpv version owned a per-screen MPVPlayerView and
 * polled `time-pos` / `duration` / `pause` via setOptionString every
 * 500ms. We now spin up a dedicated ExoPlayer for the lifetime of
 * this screen (we don't share the Live TV persistent holder -- VOD
 * has its own buffering profile, doesn't need surface persistence
 * across nav transitions, and the lifetimes don't overlap usefully).
 *
 * Position + duration come from Player.contentPosition /
 * contentDuration; play/pause/seek are direct Player API calls.
 * Save / resume continues to use WatchProgress unchanged.
 */
@OptIn(UnstableApi::class)
@Composable
fun VODPlayerScreen(
    streamUrl: String,
    title: String,
    httpHeaders: Map<String, String> = emptyMap(),
    onClose: () -> Unit = {},
    loadingMessage: String? = null,
    videoId: String? = null,
    posterUrl: String? = null,
    isDvr: Boolean = false,
    startAtLiveEdge: Boolean = true,
    /** Catch-up (task #136): the programme's UTC window + the panel timezone
     *  the timeshift URL's start was rendered in. When csEnd > csStart the
     *  player runs in catch-up mode: duration = programme length and seeks
     *  re-tune by rebuilding the URL at the target start (the timeshift
     *  protocol's only random access), enabling commercial skipping. */
    catchupStartMillis: Long = 0L,
    catchupEndMillis: Long = 0L,
    catchupTz: String = "",
    /** Task #149: Dispatcharr channel uuid, non-blank only for NATIVE
     *  catch-up sessions (/proxy/catchup/ playback URL). Seeks then
     *  re-mint a session at programmeStart+offset instead of rebuilding
     *  an XC wall-clock URL, and closing the player revokes the
     *  session (frees the server's per-session provider slot). */
    catchupChannelUuid: String = "",
    /** Task #149: mint a fresh native session for a seek re-tune at the
     *  given absolute UTC start. Returns the new absolute playback URL
     *  or null (the player keeps its current window). */
    onRemintCatchup: suspend (channelUuid: String, currentUrl: String, absStartMillis: Long) -> String? = { _, _, _ -> null },
    /** Task #149: best-effort revoke of the native session on close
     *  (frees the server's per-session provider slot early). */
    onRevokeCatchup: (playbackUrl: String) -> Unit = {},
    /** Task #183: report local playhead/pause for a native catch-up
     *  session (keeps server stats honest + refreshes the idle TTL
     *  through long pauses). Returns false when the server lacks the
     *  endpoint - the screen then stops reporting for this playback. */
    onReportCatchupPosition: suspend (playbackUrl: String, positionSecs: Double, paused: Boolean) -> Boolean =
        { _, _, _ -> true },
    /** VOD version switching (Dispatcharr Direct Connect): the provider
     *  copies of the playing item. The Options sheet offers "Switch Version"
     *  only when there is more than one. */
    versionOptions: List<VodProviderOption> = emptyList(),
    /** The pinned version, or null for "Auto" (server priority + failover). */
    selectedVersion: VodProviderOption? = null,
    /** Records the selection in the VM and re-resolves the playback URL for
     *  it (null option = back to Auto). Returns the new session URL, or null
     *  when resolution failed (the player then keeps the current stream). */
    onSelectVersion: suspend (VodProviderOption?) -> String? = { null },
    /** Remembers what ExoPlayer MEASURED for the pinned provider copy, so the
     *  Version picker can describe copies the upstream panel never did.
     *
     *  MOVIES ONLY: wired by the movie player route alone. An episode option
     *  pins an m3u ACCOUNT rather than a specific file, so its relation id
     *  says nothing about what played; DVR and live playback have no provider
     *  copy at all. Leaving this at its no-op default is what excludes them. */
    onLearnStream: suspend (relationId: Int, stream: VodLearnedStream) -> Unit = { _, _ -> },
) {
    // Keep the screen on during VOD playback. Matches PlayerScreen for the
    // same reason: system screen-timeout would otherwise dim/sleep the panel
    // mid-movie. iOS parity via IdleTimerRefCount.
    KeepScreenOnWhilePlaying()

    val settingsVm: SettingsViewModel = hiltViewModel()
    val audioPassthrough by settingsVm.audioPassthroughEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val streamBufferSize by settingsVm.streamBufferSize.collectAsStateWithLifecycle(initialValue = "default")
    val aspectMode by settingsVm.playerAspectMode.collectAsStateWithLifecycle(initialValue = "fit")
    val watchVm: WatchProgressViewModel = hiltViewModel()

    val context = LocalContext.current
    val inPip by PipState.inPictureInPicture
    val pipAvailable = remember { context.supportsPip() }

    // VOD is always video, so leaving the app should auto-enter PiP while this
    // screen is up. Cleared when the player leaves composition.
    DisposableEffect(Unit) {
        PipState.videoPlaybackActive.value = true
        PipState.audioPlaybackActive.value = false
        onDispose { PipState.videoPlaybackActive.value = false }
    }

    var chromeVisible by remember { mutableStateOf(true) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // Options entry point (bottom chrome, rightmost): a small sheet with
    // Switch Version (when the item has > 1 provider copy) + Audio Track +
    // Subtitles. All FormFactorModal-based so they work on phone AND TV.
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showVersionSheet by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showSubtitlesSheet by remember { mutableStateOf(false) }

    // The copy has a video track this device has no decoder for, so ExoPlayer
    // dropped it and is playing audio only. AerioMediaCodecVideoRenderer
    // already rescues what it can (Dolby Vision falls back to its HEVC base
    // layer); this is the residue that nothing can decode, and without a
    // notice it looks like a black-screen bug.
    var videoUnsupported by remember { mutableStateOf(false) }

    // GH #33 companion remote: register this screen's per-screen player with the
    // companion host while mounted, so a paired phone's play/pause/seek drives
    // THIS VOD/recording playback (the host otherwise drives the shared live
    // player, which is stopped on this route). Inert off Android TV: the host
    // only starts on FEATURE_LEANBACK devices.
    val companionHostReg = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerScreenEntryPoint::class.java,
        ).companionHost()
    }
    DisposableEffect(Unit) {
        val provider = { exoPlayer }
        companionHostReg.externalPlayerProvider = provider
        onDispose {
            // Identity check: on A->B navigation between two VOD/recording items
            // the NavHost keeps A composed through the transition, so B registers
            // FIRST and A's dispose runs after -- an unconditional null here would
            // clobber B's registration and kill companion transport on B.
            if (companionHostReg.externalPlayerProvider === provider) {
                companionHostReg.externalPlayerProvider = null
            }
        }
    }

    // Player progress, polled every 500ms while the player is mounted. Backs
    // the scrubber + position/duration row. positionMs is the canonical
    // playback position; previewMs is the user's pending-drag position before
    // the seek is committed.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPaused by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Task #149: native catch-up seeks mint a session over the network,
    // so the re-tune lands via this scope rather than synchronously.
    val seekScope = rememberCoroutineScope()
    // Task #149: one-shot 4xx recovery for native sessions. A session's
    // 10-minute idle TTL lapses during a long pause; the next range
    // request then 404s, which the shared 4xx branch would treat as
    // "no archive" and close the player mid-replay. One silent re-mint
    // at the current position absorbs that; a second 4xx closes.
    var nativeRemintRecoveryUsed by remember { mutableStateOf(false) }

    // ── Catch-up mode (task #136) ────────────────────────────────────────
    // durationMs is pinned to the programme length and positionMs is
    // programme-relative: the tuned stream only covers from the URL's start
    // to the programme end, so displayed position = window offset + player
    // position. Seeks outside the buffered window re-tune (see seekPlayer).
    val isCatchup = catchupEndMillis > catchupStartMillis &&
        (streamUrl.contains("/timeshift/") || streamUrl.contains("/proxy/catchup/"))
    // Task #149: native Dispatcharr catch-up session (vs XC wall-clock URL).
    val isNativeCatchup = isCatchup && catchupChannelUuid.isNotBlank() &&
        streamUrl.contains("/proxy/catchup/")
    // The URL the player is CURRENTLY tuned to. For native catch-up every
    // seek re-mint replaces it (new session_id); revoke-on-close and the
    // re-mint's base-host derivation both read this, never the original
    // streamUrl param.
    var currentPlaybackUrl by remember { mutableStateOf(streamUrl) }
    // Task #149: native re-mints are serialized. While one mint is in
    // flight, later seek targets coalesce here and only the LATEST runs
    // when it lands (rapid +/-30s presses used to race out of order and
    // 503 the server's provider slot).
    var nativeRemintInFlight by remember { mutableStateOf(false) }
    var nativeRemintPendingMs by remember { mutableStateOf<Long?>(null) }
    // Task #150 (iOS parity): playback-error card state. The card shows the
    // real error, auto-retries on an escalating 5s->30s delay, and offers a
    // manual Retry; STATE_READY (any successful retry) clears everything.
    var playbackErrorMessage by remember { mutableStateOf<String?>(null) }
    var errorRetrySerial by remember { mutableIntStateOf(0) }
    var errorRetryCountdown by remember { mutableIntStateOf(0) }
    var errorReconnecting by remember { mutableStateOf(false) }
    // Programme-relative start of the currently tuned timeshift window (0 on
    // first tune; the seek target after each re-tune).
    var catchupOffsetMs by remember { mutableLongStateOf(0L) }

    // ── TV transport (task #44) ──────────────────────────────────────────
    // D-pad / media-key scrub preview. Non-null while the user is stepping
    // LEFT/RIGHT; holds the pending seek target in ms. The debounced
    // LaunchedEffect below commits the seek 650ms after the last step (iOS
    // PlayerView.scheduleScrubCommit parity) so key autorepeat sweeps the
    // preview instead of queueing one seek per repeat (no stutter).
    var scrubTargetMs by remember { mutableStateOf<Long?>(null) }
    // GH #78: set by the first deliberate seek of any kind (scrub commit,
    // d-pad skip, media key, scrubber drag). The from-beginning watcher below
    // stands down the moment this flips, so a correction can never land on top
    // of a position the user chose.
    var userSeeked by remember { mutableStateOf(false) }
    // iOS PlayerView.scrubStep acceleration: consecutive same-direction
    // steps grow the multiplier (1 + count/2, capped 12x of the 10s base).
    var scrubAccelCount by remember { mutableIntStateOf(0) }
    var scrubLastDirection by remember { mutableIntStateOf(0) }
    var scrubLastStepAt by remember { mutableLongStateOf(0L) }
    // Bumped on every handled remote press so the chrome auto-hide re-arms
    // (PlayerScreen Phase 172 pattern). Stays 0 on phone.
    var lastInteractionAt by remember { mutableLongStateOf(0L) }
    val playbackFocus = remember { FocusRequester() }
    // Which transport control the D-pad is "on" while chrome is up (TV only).
    // None on phone / while chrome hidden. PlayPause is the reveal default.
    var tvFocusZone by remember { mutableStateOf(TvVodFocusZone.None) }
    val isTvForm = (
        context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK
        ) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    // GH #7 (True Android Fullscreen), VOD parity with the live player: hide the
    // status + nav bars in LANDSCAPE for edge-to-edge playback, but keep the
    // status bar in PORTRAIT so the top control banner never slides under a
    // camera cutout (in portrait the OS reliably reserves the cutout area with
    // the status bar on every device; the DisplayCutout inset is not always
    // exposed to apps, e.g. the Samsung Z Fold cover screen). Plus a manual
    // fullscreen toggle (forcedLandscape) that force-rotates to landscape and
    // pins it under a portrait rotation-lock (iOS PlayerView parity). Keyed on
    // orientation so a rotation re-applies the right mode; restored on dispose.
    var forcedLandscape by remember { mutableStateOf(false) }
    if (!isTvForm) {
        val activity = context.findActivity()
        val isLandscape = LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        DisposableEffect(activity, isLandscape) {
            val window = activity?.window
            val controller = window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
            }
            controller?.apply {
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (isLandscape) {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {
                controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                // Auto-Rotate aware: UNSPECIFIED when following the sensor,
                // LOCKED when the user disabled rotation in App Behaviors.
                activity?.requestedOrientation =
                    com.aeriotv.android.core.preferences.AutoRotateState.restingOrientation
            }
        }
    }

    // "Audio keeps playing after leaving the app" on TV (jonzee222): VOD owns
    // its OWN ExoPlayer (not the live holder MainActivity.onUserLeaveHint tears
    // down), and a TV has no PiP, so HOME left this player decoding audio at
    // the launcher. Pause on ON_STOP when on a TV (also covers screen-off).
    // Phone keeps the existing PiP / continue behavior. The player stays built,
    // so returning resumes from where it paused.
    if (isTvForm) {
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, exoPlayer) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                    exoPlayer?.playWhenReady = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // Phone (#120): HOME auto-enters PiP and VOD SHOULD keep playing in that
    // window, but CLOSING the PiP with its X must stop it -- mirroring the live
    // #106 fix. MainActivity's onPictureInPictureModeChanged X-dismiss branch
    // fires at the right moment (activity already CREATED) but reaches only the
    // live holder, so it invokes PipState.onPipDismissed; register this VOD
    // player's stop here. (onStop can't detect the X-dismiss: it runs BEFORE
    // onPictureInPictureModeChanged flips inPictureInPicture.) Cleared on
    // dispose so a backgrounded VOD screen is never stopped by a later live PiP.
    DisposableEffect(exoPlayer) {
        PipState.onPipDismissed = { exoPlayer?.playWhenReady = false }
        onDispose { PipState.onPipDismissed = null }
    }

    // Step the scrub preview one increment. Mirrors iOS PlayerView.scrubStep
    // (10_000ms base, accelerating to 12x). Autorepeat events are throttled
    // to one step per 250ms so holding LEFT/RIGHT sweeps smoothly instead of
    // rocketing at the raw ~50ms system key-repeat rate.
    val scrubStep: (Int, Boolean) -> Unit = step@{ dir, isRepeat ->
        val now = android.os.SystemClock.uptimeMillis()
        if (isRepeat && now - scrubLastStepAt < 250L) return@step
        if (dir == scrubLastDirection && now - scrubLastStepAt < 1_000L) {
            scrubAccelCount += 1
        } else {
            scrubAccelCount = 0
        }
        scrubLastDirection = dir
        scrubLastStepAt = now
        val mult = minOf(12, 1 + scrubAccelCount / 2)
        val base = scrubTargetMs ?: positionMs
        // Clamp a DVR scrub a few seconds behind the live edge so a forward
        // sweep can't overrun the growing window and stall; plain VOD clamps
        // at the duration; unknown duration steps unclamped.
        val maxPos = if (durationMs > 0L) {
            if (isDvr) (durationMs - 5_000L).coerceAtLeast(0L) else durationMs
        } else {
            Long.MAX_VALUE
        }
        scrubTargetMs = (base + dir * 10_000L * mult).coerceIn(0L, maxPos)
        chromeVisible = true
        lastInteractionAt = now
    }
    // Every transport seek funnels through here. Plain VOD/DVR = a direct
    // player seek. Catch-up (task #136) = the timeshift stream has no in-band
    // random access (unknown length, session-bound), but its URL encodes the
    // start time, so a seek outside the buffered window is a RE-TUNE: rebuild
    // the URL at programmeStart + target and prepare again. The URL's start
    // segment has minute granularity, so the window starts at the floored
    // minute and a residual in-stream seek lands the exact second. Targets
    // inside what's already buffered use a normal (instant) player seek.
    val seekPlayer: (Long) -> Unit = seek@{ rawTarget ->
        val player = exoPlayer ?: return@seek
        userSeeked = true
        if (!isCatchup) {
            player.seekTo(rawTarget)
            positionMs = rawTarget
            return@seek
        }
        val progLenMs = catchupEndMillis - catchupStartMillis
        val target = rawTarget.coerceIn(0L, (progLenMs - 5_000L).coerceAtLeast(0L))
        // NO in-buffer fast path: the catch-up stream reports LENGTH_UNSET
        // (UnboundedLengthDataSource) so TsExtractor emits an UNSEEKABLE
        // SeekMap and media3 collapses every seekTo into t=0 - which, with
        // backBufferDurationMs=0, resets the load and restarted playback
        // at the current WINDOW START (press +10s, jump minutes back).
        // Every catch-up seek re-tunes; it already handles both directions.
        val absFlooredStart = ((catchupStartMillis + target) / 60_000L) * 60_000L
        val windowOffset = (absFlooredStart - catchupStartMillis).coerceAtLeast(0L)
        if (isNativeCatchup) {
            // Task #149: native session seek = mint a NEW session at the
            // exact programme offset. NO minute flooring here: the sessions
            // API takes full-second timestamps, and flooring broke the
            // +/-30s skips (a +30 press from mid-minute floored BACKWARD
            // and then pinned every following press to the same window).
            // Mints are SERIALIZED: rapid presses used to fire overlapping
            // mint/prepare cycles that raced out of order and 503'd the
            // server's provider slot (tvOS log 2026-07-11); while one is
            // in flight, later targets coalesce and only the LATEST runs.
            // The outgoing session is revoked once the new one is playing
            // so its provider slot frees ahead of the 10-minute idle TTL.
            if (nativeRemintInFlight) {
                nativeRemintPendingMs = target
                positionMs = target
                return@seek
            }
            seekScope.launch {
                nativeRemintInFlight = true
                var t = target
                while (true) {
                    val outgoing = currentPlaybackUrl
                    val minted = onRemintCatchup(
                        catchupChannelUuid,
                        outgoing,
                        catchupStartMillis + t,
                    )
                    val pending = nativeRemintPendingMs
                    if (pending != null) {
                        // A newer target arrived while minting: the session
                        // just minted was never played, so free its slot and
                        // chase the newest target instead of a stale window.
                        nativeRemintPendingMs = null
                        if (minted != null) onRevokeCatchup(minted)
                        t = pending
                        continue
                    }
                    if (minted == null) {
                        Log.w(TAG, "Native catch-up re-mint failed; keeping current window")
                        break
                    }
                    onRevokeCatchup(outgoing)
                    currentPlaybackUrl = minted
                    catchupOffsetMs = t
                    positionMs = t
                    player.setMediaItem(MediaItem.fromUri(minted))
                    player.prepare()
                    player.playWhenReady = true
                    Log.i(TAG, "Native catch-up re-mint to ${t / 1000}s")
                    break
                }
                nativeRemintInFlight = false
            }
            return@seek
        }
        val newUrl = com.aeriotv.android.core.playback.CatchupUrlBuilder.rebuildForOffset(
            url = streamUrl,
            panelTimeZoneId = catchupTz.ifBlank { "UTC" },
            programmeStartMillis = catchupStartMillis,
            programmeEndMillis = catchupEndMillis,
            offsetMillis = windowOffset,
        )
        if (newUrl == null) {
            // Unable to rebuild the window URL: do nothing. A raw seekTo
            // would collapse to t=0 on the unseekable stream (see above).
            Log.w(TAG, "Catch-up re-tune URL rebuild failed; ignoring seek")
            return@seek
        }
        catchupOffsetMs = windowOffset
        // Honest minute granularity: the residual in-stream seek also
        // collapsed to 0 on the unseekable stream, so the scrubber showed
        // the target then visibly snapped back. Playback starts at the
        // floored minute; say so.
        positionMs = windowOffset
        player.setMediaItem(MediaItem.fromUri(newUrl))
        player.prepare()
        player.playWhenReady = true
        Log.i(TAG, "Catch-up re-tune to ${target / 1000}s (window ${windowOffset / 1000}s)")
    }
    // Commit an in-progress scrub immediately (OK press, iOS "Select commits
    // an in-progress scrub right away"). Setting scrubTargetMs back to null
    // also cancels the pending debounce commit.
    val commitScrub: () -> Unit = {
        scrubTargetMs?.let { target -> seekPlayer(target) }
        scrubTargetMs = null
        scrubAccelCount = 0
        scrubLastDirection = 0
    }
    // Task #150: retry from the playback-error card (manual button + the
    // auto-reconnect loop). Catch-up re-tunes through seekPlayer (native
    // mints a fresh session -- the tuned URL is session-bound and may be
    // dead; XC rebuilds the window URL); plain VOD/DVR re-prepares the
    // current item in place (media3 keeps the position across prepare()).
    val doErrorRetry: () -> Unit = {
        val p = exoPlayer
        if (p != null) {
            if (isCatchup) {
                seekPlayer(positionMs)
            } else {
                p.prepare()
                p.playWhenReady = true
            }
        }
    }
    val togglePlayPause: () -> Unit = {
        exoPlayer?.let { player ->
            val nowPaused = !player.playWhenReady
            player.playWhenReady = nowPaused
            isPaused = !nowPaused
        }
        chromeVisible = true
        lastInteractionAt = android.os.SystemClock.uptimeMillis()
    }
    // Switch the playing item to another provider copy IN PLACE (same
    // mechanism as the native catch-up re-mint above): record the selection +
    // re-resolve via the caller, then swap the media item at the current
    // position so playback resumes where it was. Watch progress is keyed by
    // the movie/episode uuid, which is unchanged across versions, so resume
    // state survives the swap untouched.
    val switchVersion: (VodProviderOption?) -> Unit = { option ->
        seekScope.launch {
            val newUrl = onSelectVersion(option)
            val p = exoPlayer
            if (newUrl != null && p != null) {
                val resumeAt = p.contentPosition.coerceAtLeast(0L)
                currentPlaybackUrl = newUrl
                p.setMediaItem(MediaItem.fromUri(newUrl), resumeAt)
                p.prepare()
                p.playWhenReady = true
                Log.i(TAG, "VOD version switch; resuming at ${resumeAt / 1000}s")
            } else if (newUrl == null) {
                Log.w(TAG, "VOD version switch failed to resolve; keeping current stream")
            }
        }
    }

    // ── Learned stream measurements ─────────────────────────────────────
    // Dispatcharr relays each provider panel's ffprobe output to the Version
    // picker, but coverage is uneven: on a live server many copies publish a
    // bitrate and nothing else, and some publish nothing at all. A copy that
    // is PLAYING is fully described though, so we hand the real frame size and
    // codecs back to the VM and the picker keeps showing them. Still a
    // measurement start to finish; provider titles are never parsed.
    //
    // Keyed on the pinned copy's relation id: on Auto there is no copy to
    // attribute the measurement to. [onLearnStream] is a no-op unless the
    // caller is the MOVIE route (see its KDoc), and isDvr is belt and braces.
    val learnRelationId = selectedVersion?.relationId?.takeIf { !isDvr }
    val currentOnLearnStream by rememberUpdatedState(onLearnStream)
    DisposableEffect(exoPlayer, learnRelationId) {
        val player = exoPlayer
        if (player == null || learnRelationId == null) return@DisposableEffect onDispose { }
        // Playback reports the same format repeatedly (tracks change, then
        // READY, then again on every renderer reconfigure), so only a CHANGED
        // measurement is worth handing on.
        var lastRecorded: VodLearnedStream? = null
        fun capture() {
            val measured = player.readLearnedStream()
            if (measured.isEmpty || measured == lastRecorded) return
            lastRecorded = measured
            seekScope.launch { currentOnLearnStream(learnRelationId, measured) }
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = capture()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) capture()
            }
        }
        player.addListener(listener)
        // A version switch reuses this ExoPlayer, so the copy already playing
        // can have announced its tracks before this effect re-registered.
        capture()
        onDispose { player.removeListener(listener) }
    }

    // ── Undecodable video detection ─────────────────────────────────────
    // Read support off the track groups rather than Tracks.isTypeSupported so
    // the meaning is explicit: the item carries video, and not one of its
    // video tracks can be played here. Support flags come from the renderer
    // capabilities, so a Dolby Vision copy rescued by the HEVC base-layer
    // fallback reads as supported and never trips this.
    DisposableEffect(exoPlayer) {
        val player = exoPlayer ?: return@DisposableEffect onDispose { }
        fun evaluate(tracks: Tracks) {
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            val playable = videoGroups.any { group ->
                (0 until group.length).any { group.isTrackSupported(it) }
            }
            val unsupported = videoGroups.isNotEmpty() && !playable
            if (unsupported != videoUnsupported) {
                videoUnsupported = unsupported
                if (unsupported) {
                    val codecs = videoGroups.flatMap { group ->
                        (0 until group.length).map { group.getTrackFormat(it).sampleMimeType ?: "?" }
                    }.distinct().joinToString()
                    Log.w(TAG, "No decoder for this copy's video ($codecs); playing audio only")
                }
            }
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = evaluate(tracks)
        }
        player.addListener(listener)
        evaluate(player.currentTracks)
        onDispose { player.removeListener(listener) }
    }

    // Saved progress lookup. Null while loading; -1L after a confirmed "no
    // saved progress" read. Drives the resume-seek LaunchedEffect.
    var savedPositionMs by remember(videoId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(videoId) {
        if (videoId.isNullOrBlank()) return@LaunchedEffect
        val existing = watchVm.get(videoId)
        savedPositionMs = existing?.positionMs ?: -1L
    }

    // Black player background -- not the navy app-background -- so the
    // pre-first-frame gap reads as "loading" and 4:3/2.35:1 streams
    // letterbox to black bars instead of navy. Matches PlayerScreen +
    // every video player on every platform.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Android TV transport (task #44, tvOS PlayerView parity).
            // Live TV keeps its own model in PlayerScreen; this handler is
            // VOD + DVR recordings only and never runs on phone. Gated on
            // exoPlayer readiness so the loading screen's Close button
            // still receives OK presses.
            .onPreviewKeyEvent { event ->
                if (!isTvForm || exoPlayer == null) return@onPreviewKeyEvent false
                // The error overlay owns the remote while it is up. The player
                // object still exists during a playback error, so without this
                // the handler below keeps eating OK and the arrows for its
                // transport model: the card takes focus on Retry Now, the
                // button highlights, and no press ever reaches it or moves to
                // Close. Reported on the Google TV Streamer, 2026-08-15.
                // There is nothing playing behind the card to transport anyway.
                if (playbackErrorMessage != null) return@onPreviewKeyEvent false
                val handledKey = when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                    Key.DirectionLeft, Key.DirectionRight,
                    Key.DirectionUp, Key.DirectionDown,
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
                    Key.MediaRewind, Key.MediaFastForward,
                    -> true
                    else -> false
                }
                if (!handledKey) return@onPreviewKeyEvent false
                // Swallow the matching KeyUp too so the focused clickable
                // underneath never sees a half-delivered press.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                val isRepeat = event.nativeKeyEvent.repeatCount > 0
                val now = android.os.SystemClock.uptimeMillis()
                val reveal = {
                    chromeVisible = true
                    if (tvFocusZone == TvVodFocusZone.None) tvFocusZone = TvVodFocusZone.PlayPause
                    lastInteractionAt = now
                }
                when (event.key) {
                    // Hardware media keys keep working regardless of zone.
                    Key.MediaPlay -> {
                        exoPlayer?.playWhenReady = true; isPaused = false; reveal()
                    }
                    Key.MediaPause -> {
                        exoPlayer?.playWhenReady = false; isPaused = true; reveal()
                    }
                    Key.MediaPlayPause -> {
                        if (scrubTargetMs != null) commitScrub(); togglePlayPause()
                        reveal()
                    }
                    Key.MediaRewind -> { reveal(); tvFocusZone = TvVodFocusZone.Scrubber; scrubStep(-1, isRepeat) }
                    Key.MediaFastForward -> { reveal(); tvFocusZone = TvVodFocusZone.Scrubber; scrubStep(+1, isRepeat) }

                    // OK / Select.
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!chromeVisible) {
                            // First OK only reveals controls + lands on Play/Pause.
                            reveal()
                        } else when (tvFocusZone) {
                            TvVodFocusZone.Scrubber -> {
                                // Commit an in-progress scrub (iOS Select-commits).
                                if (scrubTargetMs != null) commitScrub()
                                lastInteractionAt = now
                            }
                            TvVodFocusZone.Rewind -> {
                                // Drop any stale scrub preview so its debounce
                                // can't override this discrete seek (see DOWN).
                                scrubTargetMs = null
                                scrubAccelCount = 0
                                scrubLastDirection = 0
                                val target = max(0L, positionMs - 10_000L)
                                seekPlayer(target); reveal()
                            }
                            TvVodFocusZone.Forward -> {
                                scrubTargetMs = null
                                scrubAccelCount = 0
                                scrubLastDirection = 0
                                val maxPos = if (durationMs > 0L) {
                                    if (isDvr) (durationMs - 5_000L).coerceAtLeast(0L) else durationMs
                                } else Long.MAX_VALUE
                                val target = min(maxPos, positionMs + 10_000L)
                                seekPlayer(target); reveal()
                            }
                            TvVodFocusZone.Options -> {
                                showOptionsSheet = true
                                lastInteractionAt = now
                            }
                            else -> { togglePlayPause() } // PlayPause / None
                        }
                    }

                    Key.DirectionLeft -> {
                        if (!chromeVisible) { reveal() }
                        else when (tvFocusZone) {
                            TvVodFocusZone.Scrubber -> scrubStep(-1, isRepeat)
                            TvVodFocusZone.PlayPause -> { tvFocusZone = TvVodFocusZone.Rewind; lastInteractionAt = now }
                            TvVodFocusZone.Forward -> { tvFocusZone = TvVodFocusZone.PlayPause; lastInteractionAt = now }
                            TvVodFocusZone.Options -> { tvFocusZone = TvVodFocusZone.Forward; lastInteractionAt = now }
                            else -> { tvFocusZone = TvVodFocusZone.Rewind; lastInteractionAt = now } // Rewind/None: stay leftmost
                        }
                        chromeVisible = true
                    }
                    Key.DirectionRight -> {
                        if (!chromeVisible) { reveal() }
                        else when (tvFocusZone) {
                            TvVodFocusZone.Scrubber -> scrubStep(+1, isRepeat)
                            TvVodFocusZone.PlayPause -> { tvFocusZone = TvVodFocusZone.Forward; lastInteractionAt = now }
                            TvVodFocusZone.Rewind -> { tvFocusZone = TvVodFocusZone.PlayPause; lastInteractionAt = now }
                            TvVodFocusZone.Forward -> { tvFocusZone = TvVodFocusZone.Options; lastInteractionAt = now }
                            else -> { tvFocusZone = TvVodFocusZone.Options; lastInteractionAt = now } // Options/None: stay rightmost
                        }
                        chromeVisible = true
                    }
                    // UP enters the scrubber so the user can D-pad scrub.
                    Key.DirectionUp -> {
                        reveal(); tvFocusZone = TvVodFocusZone.Scrubber
                    }
                    // DOWN drops back from the scrubber to the control row
                    // (Play/Pause); from the control row it just keeps chrome up.
                    Key.DirectionDown -> {
                        reveal()
                        if (tvFocusZone == TvVodFocusZone.Scrubber) {
                            // Cancel any pending scrub preview when leaving the
                            // scrubber so its ~650ms debounce can't later fire a
                            // stale seekTo over a Rewind/Forward/PlayPause action.
                            scrubTargetMs = null
                            scrubAccelCount = 0
                            scrubLastDirection = 0
                            tvFocusZone = TvVodFocusZone.PlayPause
                        }
                    }
                }
                true
            },
    ) {
        // Don't mount MPV until the proxy redirect has been resolved into a
        // session URL - otherwise libmpv hits the 301 path that strips our
        // auth headers and fails with "Failed to open".
        if (streamUrl.isBlank() || loadingMessage != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = loadingMessage ?: "Loading…",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
            // Close affordance during load / error. Phone/tablet only: the X is
            // not D-pad-reachable on TV (GH #32), so TV cancels a stuck load with
            // the remote Back button (pops the nav back stack) instead.
            if (!isTvForm) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
            return
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // VOD-friendly buffer windows. Bigger than the live numbers
                // in AerioExoPlayerHolder because VOD users tolerate a
                // slightly slower start in exchange for smoother seeking
                // and fewer rebuffers across the duration of a long film.
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs */ 15_000,
                        /* maxBufferMs */ 50_000,
                        /* bufferForPlaybackMs */ 2_000,
                        /* bufferForPlaybackAfterRebufferMs */ 5_000,
                    )
                    .build()

                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(30_000)
                    .setReadTimeoutMs(30_000)
                    // Send a real player UA (parity with live path); some
                    // panels reject the platform "Dalvik/..." default.
                    .setUserAgent("AerioTV/${com.aeriotv.android.BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})")
                if (httpHeaders.isNotEmpty()) {
                    dataSourceFactory.setDefaultRequestProperties(httpHeaders)
                    httpHeaders.entries
                        .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                        ?.value
                        ?.let(dataSourceFactory::setUserAgent)
                }

                val renderersFactory =
                    com.aeriotv.android.core.playback.aerioRenderersFactory(
                        ctx,
                        audioPassthrough,
                        // GH #45: on-demand (VOD + DVR recordings) prefers the
                        // FFmpeg audio decoder so HE-AAC/SBR recordings that the
                        // platform hardware AAC decoder fails on (Hisense/MediaTek
                        // c2.android.aac.decoder err 0xe) decode reliably. Live +
                        // multiview keep hardware-first (default false).
                        preferSoftwareAudio = true,
                    )

                // Wrap the header-aware HTTP factory in DefaultDataSource.Factory
                // so a local DVR recording's file:// URL resolves through
                // FileDataSource while remote URLs (VOD + Dispatcharr server
                // recordings) still flow through the HTTP factory carrying the
                // auth headers. A bare DefaultHttpDataSource.Factory cannot open
                // file://, so local recordings would otherwise fail to load.
                val upstreamFactory: androidx.media3.datasource.DataSource.Factory =
                    DefaultDataSource.Factory(ctx, dataSourceFactory).let { base ->
                        // Catch-up (task #133): a Dispatcharr /timeshift/ archive
                        // reports an estimated Content-Length and re-redirects to
                        // a fresh ?session_id on every connection, so ExoPlayer's
                        // TS end-seek-for-duration EOFs before playback. Hiding the
                        // length makes it stream forward like live TS. VOD +
                        // recordings keep their real length for whole-file seeking.
                        if (streamUrl.contains("/timeshift/")) {
                            com.aeriotv.android.core.playback.UnboundedLengthDataSource.Factory(base)
                        } else {
                            base
                        }
                    }
                val mediaSourceFactory = DefaultMediaSourceFactory(ctx)
                    .setDataSourceFactory(upstreamFactory)

                val player = ExoPlayer.Builder(ctx)
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setHandleAudioBecomingNoisy(true)
                    .build()
                    .apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(TAG, "VOD ExoPlayer error: ${error.errorCodeName}", error)
                                // Catch-up (task #136): a provider that flags
                                // tv_archive but serves no archive answers the
                                // timeshift URL with 404 "Catch-up not
                                // available yet". Only a genuine no-archive
                                // 4xx closes; everything else falls through to
                                // the task #150 error card below.
                                if (isCatchup &&
                                    error.errorCode ==
                                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                                ) {
                                    val status = (error.cause as?
                                        androidx.media3.datasource.HttpDataSource
                                            .InvalidResponseCodeException)?.responseCode ?: 0
                                    if (status in 400..499 &&
                                        isNativeCatchup && !nativeRemintRecoveryUsed
                                    ) {
                                        // Native session TTL lapsed (long
                                        // pause / missed handshake): re-mint
                                        // at the current position instead of
                                        // kicking the user out.
                                        nativeRemintRecoveryUsed = true
                                        seekScope.launch {
                                            val resumeAt = positionMs.coerceAtLeast(0L)
                                            val minted = onRemintCatchup(
                                                catchupChannelUuid,
                                                currentPlaybackUrl,
                                                catchupStartMillis + resumeAt,
                                            )
                                            val p = exoPlayer
                                            if (minted != null && p != null) {
                                                currentPlaybackUrl = minted
                                                catchupOffsetMs = resumeAt
                                                positionMs = resumeAt
                                                p.setMediaItem(MediaItem.fromUri(minted))
                                                p.prepare()
                                                p.playWhenReady = true
                                                Log.i(TAG, "Native catch-up session recovered after 4xx")
                                            } else {
                                                // Silent recovery failed: the
                                                // card (with its remint-aware
                                                // Retry) takes over.
                                                playbackErrorMessage =
                                                    "Catch-up session expired (HTTP $status)"
                                                errorReconnecting = false
                                            }
                                        }
                                        return
                                    }
                                    if (status in 400..499 && !isNativeCatchup) {
                                        android.widget.Toast.makeText(
                                            ctx,
                                            "Catch-up isn't available for this program on your provider.",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                        onClose()
                                        return
                                    }
                                }
                                // Task #150 (iOS parity): every other failure
                                // surfaces the error card with the REAL error;
                                // the card auto-retries and offers manual Retry.
                                playbackErrorMessage = error.cause?.message
                                    ?.let { "${error.errorCodeName}: $it" }
                                    ?: error.errorCodeName
                                errorReconnecting = false
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                // A retry (manual, auto, or the silent native
                                // re-mint) reached steady playback: dismiss
                                // the card and reset the escalation.
                                if (playbackState == Player.STATE_READY &&
                                    playbackErrorMessage != null
                                ) {
                                    playbackErrorMessage = null
                                    errorReconnecting = false
                                    errorRetryCountdown = 0
                                    errorRetrySerial = 0
                                }
                            }
                        })
                        playWhenReady = true
                        // "Watch from Beginning" on an in-progress recording
                        // pins the START POSITION at prepare time rather than
                        // seeking afterwards. Media3 resolves a live HLS to its
                        // DEFAULT position (the live edge) the moment the window
                        // is known, so a seek issued from a LaunchedEffect was
                        // racing that resolution and losing - the user landed at
                        // live and had to rewind by hand (Logan 2026-08-10).
                        // An explicit start position means the default is never
                        // consulted, so there is no race to lose.
                        //
                        // GH #78: the pin alone was not enough. Dispatcharr's
                        // DVR muxer omits EXT-X-ENDLIST, so media3 builds a
                        // DYNAMIC window whose DEFAULT position is the live
                        // edge, and any time our pinned 0 was masked against
                        // the placeholder timeline that default won instead.
                        // So move the default itself. HlsMediaSource derives
                        // the live window's default start position as
                        // `durationUs + liveEdgeOffsetUs - targetOffsetMs`,
                        // having first constrained targetOffsetMs to at most
                        // `durationUs + liveEdgeOffsetUs`. Dispatcharr writes
                        // no EXT-X-PROGRAM-DATE-TIME, so liveEdgeOffsetUs is
                        // 0, an oversized target offset clamps to the full
                        // duration, and the default start position lands on
                        // the FIRST segment. That is the same thing the Apple
                        // build asks ffmpeg for with live_start_index=0: the
                        // decision is made before the first byte is fetched,
                        // so there is no resolution race left to lose.
                        // Leaving min/max playback speed unset also pins the
                        // rate at 1.0x, so media3 never speeds up trying to
                        // chase the live edge.
                        if (isDvr && !startAtLiveEdge) {
                            val fromStartItem = MediaItem.Builder()
                                .setUri(streamUrl)
                                .setLiveConfiguration(
                                    MediaItem.LiveConfiguration.Builder()
                                        .setTargetOffsetMs(WATCH_FROM_START_TARGET_OFFSET_MS)
                                        .build(),
                                )
                                .build()
                            setMediaItem(fromStartItem, 0L)
                        } else {
                            setMediaItem(MediaItem.fromUri(streamUrl))
                        }
                        prepare()
                    }

                Log.i(TAG, "Loading VOD on ExoPlayer: $streamUrl")
                exoPlayer = player

                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setPlayer(player)
                }
            },
            update = { view ->
                // iOS Issue #26: live aspect-ratio toggle (Fit / Zoom / Fill).
                view.resizeMode = when (aspectMode) {
                    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            onRelease = { view ->
                Log.i(TAG, "Releasing VOD ExoPlayer")
                // Task #149: free the native catch-up session's provider
                // slot ahead of its idle TTL. Best-effort, fire-and-forget.
                if (isNativeCatchup) onRevokeCatchup(currentPlaybackUrl)
                exoPlayer?.release()
                exoPlayer = null
                view.player = null
            },
        )

        // Resume from saved position. Wait until the player reports a
        // sane duration before issuing seekTo -- ExoPlayer accepts
        // seekTo in STATE_IDLE but it's a no-op until the manifest is
        // parsed and contentDuration arrives.
        LaunchedEffect(exoPlayer, savedPositionMs) {
            val player = exoPlayer ?: return@LaunchedEffect
            // A DVR recording is not VOD: never restore a saved VOD position;
            // the DVR catch-up effect below owns the start position.
            if (isDvr) return@LaunchedEffect
            val pos = savedPositionMs ?: return@LaunchedEffect
            if (pos <= 0L) return@LaunchedEffect
            // Spin until the player has parsed duration. Bail out after
            // ~6s so a broken / DRM-locked stream doesn't leak this
            // coroutine.
            var waited = 0L
            while (player.contentDuration <= 0L && waited < 6_000L) {
                delay(200L)
                waited += 200L
            }
            // Defensive (GH #45): never seek into a player that has already
            // errored. A seekTo triggers a codec flush; flushing an
            // already-errored MediaCodec throws IllegalStateException and turns
            // a recoverable decode error into a fatal native crash cascade
            // (seen in the #45 logcat: "Resumed from 10000ms" -> native_flush
            // IllegalStateException). The HE-AAC decode fix makes this path
            // healthy, but the guard is cheap insurance for any future error.
            if (player.contentDuration > 0L && player.playerError == null) {
                player.seekTo(pos)
                Log.i(TAG, "Resumed from ${pos}ms")
            }
        }

        // GH #78: "Watch from Beginning" on an in-progress recording.
        //
        // The start position is pinned to 0 at setMediaItem time (see the
        // player builder above), which is the primary fix. It is not
        // sufficient on its own. Dispatcharr records with
        // `-hls_flags append_list+omit_endlist` and writes no
        // EXT-X-PLAYLIST-TYPE, so by spec the playlist is a LIVE playlist and
        // media3 builds a DYNAMIC window whose DEFAULT position is the live
        // edge. Whenever the real window resolves after our pin was masked
        // against the placeholder timeline, that default wins and the user
        // lands at live with 25 minutes to rewind by hand.
        //
        // The previous backstop sampled the position exactly ONCE, and only
        // within 6 seconds of the player being built. Both limits fail in
        // precisely the case kmac reported: on a cold app launch on a
        // low-power Google TV box, a 25-minute recording's playlist (375+
        // segments at Dispatcharr's -hls_time 4) can take longer than 6s to
        // resolve, and if the single sample lands BEFORE it resolves it reads
        // ~0, logs "nothing to correct", and exits, leaving nothing to catch
        // the drag that follows. That is exactly the reported shape: broken
        // from a fresh launch, fine once the app is warm.
        //
        // So follow playback instead of guessing once. Dispatcharr uses
        // `-hls_list_size 0`, so no segment ever rolls off and window position
        // 0 stays valid for the whole recording; a correction can never
        // strand the user mid-window.
        LaunchedEffect(exoPlayer, isDvr, startAtLiveEdge) {
            if (!isDvr || startAtLiveEdge) return@LaunchedEffect
            val player = exoPlayer ?: return@LaunchedEffect
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var lastPos = -1L
            var corrections = 0
            while (android.os.SystemClock.elapsedRealtime() - startedAt < WATCH_FROM_START_WINDOW_MS) {
                delay(200L)
                if (userSeeked) {
                    Log.i(TAG, "DVR from-beginning: user seeked, standing down (corrections=$corrections)")
                    return@LaunchedEffect
                }
                // GH #45: never seek into an errored player. seekTo flushes the
                // codec, and flushing an already-errored MediaCodec turns a
                // recoverable decode error into a fatal native crash cascade.
                // Reset the position baseline so the reconnect that follows is
                // not mistaken for a live-edge drag.
                if (player.playerError != null) {
                    lastPos = -1L
                    continue
                }
                // media3 publishes a placeholder window (empty timeline, not
                // seekable) while the playlist loads. Treat that as "not
                // resolved yet" and KEEP WAITING; the old 6s cap gave up here.
                if (player.currentTimeline.isEmpty || !player.isCurrentMediaItemSeekable) {
                    lastPos = -1L
                    continue
                }
                val pos = player.contentPosition
                // Two shapes of the same failure. Either the window resolved
                // straight to the live edge (the first resolved sample is
                // already deep into the recording), or it resolved late and
                // DRAGGED playback forward (position jumped much further than
                // 200ms of wall clock can account for). Normal playback moves
                // ~200ms per sample, so a 3s jump is never organic.
                val resolvedAtEdge = lastPos < 0L && pos > 5_000L
                val draggedForward = lastPos >= 0L && pos - lastPos > 3_000L
                if (resolvedAtEdge || draggedForward) {
                    if (corrections >= WATCH_FROM_START_MAX_CORRECTIONS) {
                        Log.w(
                            TAG,
                            "DVR from-beginning: window returned to ${pos}ms after $corrections " +
                                "corrections; standing down rather than looping seeks",
                        )
                        return@LaunchedEffect
                    }
                    player.seekTo(player.currentMediaItemIndex, 0L)
                    corrections++
                    Log.i(
                        TAG,
                        "DVR from-beginning: window resolved at ${pos}ms (live edge); " +
                            "corrected to window start (correction $corrections)",
                    )
                    lastPos = 0L
                    continue
                }
                lastPos = pos
            }
            Log.i(
                TAG,
                "DVR from-beginning: watcher done after ${WATCH_FROM_START_WINDOW_MS}ms " +
                    "(corrections=$corrections, position=${lastPos}ms)",
            )
        }

        // Periodic save. Mirrors iOS NowPlayingManager.currentWatchProgress's
        // ~5s persistence cadence. Same logic, just reading from ExoPlayer
        // instead of libmpv property-strings.
        //
        // rememberUpdatedState: this loop launches before the nav route's
        // movie/episode lookup resolves (Navigation.kt passes title/posterUrl
        // from a route-scoped ViewModel whose library is still loading), and
        // a LaunchedEffect closure captures its parameters at launch. Without
        // the indirection every save would persist the initial null poster +
        // placeholder title, which is how Continue Watching cards ended up
        // art-less.
        val latestTitle by rememberUpdatedState(title)
        val latestPosterUrl by rememberUpdatedState(posterUrl)
        LaunchedEffect(exoPlayer, videoId) {
            val player = exoPlayer ?: return@LaunchedEffect
            if (videoId.isNullOrBlank()) return@LaunchedEffect
            while (true) {
                delay(5_000L)
                // Once playback has ENDED, stop persisting: contentPosition pins
                // at the end, so the loop would keep re-saving a stale past-EOF
                // position and could overwrite the finished / advanced-up-next
                // state the final save already recorded. `continue` (not break)
                // so a seek back into the movie -- which leaves STATE_ENDED --
                // resumes saving for the re-watch.
                if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) continue
                val pos = player.contentPosition
                val dur = player.contentDuration
                if (pos <= 0L || dur <= 0L) continue
                watchVm.save(
                    videoId = videoId,
                    title = latestTitle,
                    posterUrl = latestPosterUrl,
                    positionMs = pos,
                    durationMs = dur,
                )
            }
        }

        // Tight 500ms poll for scrubber state. ExoPlayer exposes the
        // values directly; no string parsing. The poll-instead-of-
        // observe trade is the same as the libmpv version: cheaper than
        // wiring listener callbacks for properties that fire on every
        // frame, and only runs while VOD is mounted.
        LaunchedEffect(exoPlayer) {
            val player = exoPlayer ?: return@LaunchedEffect
            val window = androidx.media3.common.Timeline.Window()
            // Task #183: throttled position/pause reports for native
            // catch-up sessions ride this poll (which keeps running while
            // paused - each accepted report refreshes the session idle
            // TTL, so a long pause can't expire the session). 20s cadence,
            // immediate on a pause-state flip; one 404 latches reporting
            // off (stable-tag server without the endpoint).
            var reportUnsupported = false
            var lastReportAtMs = 0L
            var lastReportedPaused: Boolean? = null
            while (true) {
                delay(500L)
                if (isDragging) continue
                // Catch-up: the tuned stream only spans window-start..prog-end,
                // so the programme-relative position adds the window offset and
                // the duration is the (fixed, known) programme length.
                if (isCatchup) {
                    positionMs = catchupOffsetMs + player.contentPosition.coerceAtLeast(0L)
                    durationMs = catchupEndMillis - catchupStartMillis
                    isPaused = !player.playWhenReady
                    if (isNativeCatchup && !reportUnsupported) {
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        val pausedChanged = lastReportedPaused != isPaused
                        if (pausedChanged || nowMs - lastReportAtMs >= 20_000L) {
                            lastReportAtMs = nowMs
                            lastReportedPaused = isPaused
                            val url = currentPlaybackUrl
                            val posSecs = positionMs / 1000.0
                            val pausedNow = isPaused
                            // Child launch so a slow report can't stall the poll.
                            launch {
                                if (!onReportCatchupPosition(url, posSecs, pausedNow)) {
                                    reportUnsupported = true
                                }
                            }
                        }
                    }
                    continue
                }
                positionMs = player.contentPosition.coerceAtLeast(0L)
                durationMs = if (isDvr) {
                    // Live HLS window: contentDuration is C.TIME_UNSET. Derive
                    // an effective right edge from the seekable window length,
                    // floored at positionMs (iOS PlayerView.timelineEndMs).
                    val tl = player.currentTimeline
                    val winLen = if (!tl.isEmpty) {
                        tl.getWindow(player.currentMediaItemIndex, window).durationMs
                    } else {
                        androidx.media3.common.C.TIME_UNSET
                    }
                    maxOf(if (winLen > 0L) winLen else 0L, positionMs)
                } else {
                    player.contentDuration.coerceAtLeast(0L)
                }
                isPaused = !player.playWhenReady
            }
        }

        // Tap-to-toggle chrome layer. On TV it doubles as the D-pad focus
        // anchor so the root onPreviewKeyEvent above sees every remote press.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(playbackFocus)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { chromeVisible = !chromeVisible },
        )

        // TV: park D-pad focus on the playback surface (mount + every chrome
        // toggle) so key events keep routing through the transport handler.
        if (isTvForm) {
            LaunchedEffect(chromeVisible, exoPlayer) {
                delay(100)
                runCatching { playbackFocus.requestFocus() }
            }
            // Drive the default transport zone off chrome visibility (Archie
            // spec default focus). When chrome SHOWS (initial 4s auto-reveal or
            // a tap toggle false->true), land focus on Play/Pause if no zone is
            // active yet, so the first OK reveals nothing-new and OK lands on a
            // highlighted Play/Pause instead of blindly toggling play/pause.
            // When chrome HIDES, clear the zone so the next reveal re-defaults.
            // An in-flight LEFT/RIGHT/UP transition (already a non-None zone) is
            // left untouched.
            LaunchedEffect(chromeVisible) {
                tvFocusZone = if (!chromeVisible) {
                    TvVodFocusZone.None
                } else if (tvFocusZone == TvVodFocusZone.None) {
                    TvVodFocusZone.PlayPause
                } else {
                    tvFocusZone
                }
            }
        }

        // Debounced scrub commit: seek 650ms after the last LEFT/RIGHT step
        // (iOS scheduleScrubCommit parity). Each step restarts this effect;
        // an OK press commits early by nulling scrubTargetMs itself.
        LaunchedEffect(scrubTargetMs) {
            val target = scrubTargetMs ?: return@LaunchedEffect
            delay(650L)
            seekPlayer(target)
            scrubTargetMs = null
            scrubAccelCount = 0
            scrubLastDirection = 0
        }

        AnimatedVisibility(
            visible = chromeVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top scrim so the title + X button stay legible against bright frames.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .align(Alignment.TopCenter),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // GH #32 (djkotik): the Close "X" and the Fullscreen toggle
                    // below are phone affordances -- on Android TV the X is not
                    // reachable by the D-pad and Fullscreen is meaningless (TV is
                    // always landscape). Match the tvOS player, which compiles both
                    // out (#if !os(tvOS) / #if os(iOS)) and exits via the remote
                    // Back/Menu button; here VOD Back falls through onPreviewKeyEvent
                    // to pop the nav back stack. TV keeps only the title + the
                    // bottom transport row.
                    if (!isTvForm) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!isTvForm) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconButton(onClick = {
                                forcedLandscape = !forcedLandscape
                                context.findActivity()?.requestedOrientation = if (forcedLandscape) {
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                                } else {
                                    // Auto-Rotate aware release (App Behaviors).
                                    com.aeriotv.android.core.preferences.AutoRotateState.restingOrientation
                                }
                            }) {
                                Icon(
                                    imageVector = if (forcedLandscape) {
                                        Icons.Filled.FullscreenExit
                                    } else {
                                        Icons.Filled.Fullscreen
                                    },
                                    contentDescription = if (forcedLandscape) "Exit fullscreen" else "Fullscreen",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                    // PiP is a phone/tablet affordance; the TV player has no PiP
                    // button (tvOS parity), and the top-bar cluster is hidden on TV.
                    if (!isTvForm && pipAvailable) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconButton(onClick = { context.findActivity()?.enterPip16x9() }) {
                                Icon(
                                    imageVector = Icons.Filled.PictureInPicture,
                                    contentDescription = "Picture in picture",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }

                // Bottom chrome: scrubber + position/duration + play/pause + skip.
                // Mirrors iOS PlayerView scrubberBar + transport control row.
                // While a D-pad scrub is pending, the bar previews the target
                // position so the user sees the jump before the seek commits.
                BottomChrome(
                    positionMs = scrubTargetMs ?: positionMs,
                    livePositionMs = positionMs,
                    isTvForm = isTvForm,
                    tvFocusZone = tvFocusZone,
                    durationMs = durationMs,
                    isPaused = isPaused,
                    isDragging = isDragging,
                    dragFraction = dragFraction,
                    onDragStart = { isDragging = true },
                    onDragChanged = { dragFraction = it },
                    onDragEnd = { fraction ->
                        val target = (fraction * durationMs).toLong()
                        seekPlayer(target)
                        isDragging = false
                    },
                    onTogglePlay = {
                        val player = exoPlayer ?: return@BottomChrome
                        val nowPaused = !player.playWhenReady
                        player.playWhenReady = nowPaused  // toggling: paused -> resume
                        isPaused = !nowPaused
                    },
                    onSkipBack = {
                        seekPlayer(max(0L, positionMs - 10_000L))
                    },
                    onSkipForward = {
                        val maxPos = if (durationMs > 0) {
                            if (isDvr) (durationMs - 5_000L).coerceAtLeast(0L) else durationMs
                        } else {
                            Long.MAX_VALUE
                        }
                        seekPlayer(min(maxPos, positionMs + 10_000L))
                    },
                    isDvr = isDvr,
                    onSeekToLive = {
                        val p = exoPlayer ?: return@BottomChrome
                        p.seekToDefaultPosition()
                        positionMs = durationMs
                    },
                    onOptions = {
                        showOptionsSheet = true
                        lastInteractionAt = android.os.SystemClock.uptimeMillis()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
            }
        }

        // Audio-only notice. Playback is healthy, so this is deliberately not
        // the error card: no Retry, no auto-reconnect, nothing to retry into.
        // Where another copy exists the way out is a version switch, which on
        // TV runs through the Options zone (the player swallows D-pad keys for
        // its own transport model, so a focusable button here would never see
        // an OK press); on touch the card itself opens the picker.
        val canSwitchVersion = versionOptions.size > 1
        if (videoUnsupported && playbackErrorMessage == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .then(
                        if (canSwitchVersion && !isTvForm) {
                            Modifier.clickable { showVersionSheet = true }
                        } else Modifier
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = "Video Not Supported",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    text = "This device cannot decode this copy's video format, " +
                        "so only the audio is playing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
                if (canSwitchVersion) {
                    Text(
                        text = if (isTvForm) {
                            "Open Options in the player controls, then Switch Version, " +
                                "to try another copy of this title."
                        } else {
                            "Tap here to try another copy of this title."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Task #150 (iOS parity): playback-error card. Shows the real error,
        // auto-retries on an escalating 5s->30s delay, and offers manual
        // Retry / Close. STATE_READY in the listener clears it the moment a
        // retry reaches steady playback.
        playbackErrorMessage?.let { errMsg ->
            LaunchedEffect(errorRetrySerial) {
                errorReconnecting = false
                // 5s, 10s, 20s, then 30s forever.
                var remaining = minOf(30, 5 shl minOf(errorRetrySerial, 3))
                while (remaining > 0) {
                    errorRetryCountdown = remaining
                    delay(1_000L)
                    remaining -= 1
                }
                errorRetryCountdown = 0
                errorReconnecting = true
                errorRetrySerial += 1
                doErrorRetry()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = "Playback Problem",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    text = errMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = when {
                        errorReconnecting -> "Reconnecting…"
                        errorRetryCountdown > 0 -> "Retrying in ${errorRetryCountdown}s"
                        else -> " "
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
                // TV: nothing else on this overlay is focusable, so without an
                // explicit request the remote can never reach these buttons --
                // they render, look active, and simply do not respond. Logan hit
                // exactly that on the Android TV emulator (2026-08-10): "I can't
                // navigate to Retry Now or Close". Focusing Retry when the card
                // appears puts both in the focus order (Close is one step right).
                //
                // The live-TV card (PlayerScreen) solves the same problem the
                // other way, by hiding its button on TV and pointing at the
                // transport controls. Here there ARE no controls behind the
                // overlay to point at, so the buttons have to be reachable.
                val errorRetryFocus = remember { FocusRequester() }
                if (isTvForm) {
                    LaunchedEffect(errMsg, errorReconnecting) {
                        // runCatching: requesting focus on a node that is not
                        // yet attached throws, and a transient failure here
                        // must not take down the error overlay itself. Retry
                        // across a few frames rather than giving up on the
                        // first throw - a single swallowed attempt left the
                        // buttons unreachable (the original bug) whenever the
                        // node attached a frame late, and nothing re-ran until
                        // errMsg changed.
                        repeat(5) {
                            if (runCatching { errorRetryFocus.requestFocus() }.isSuccess) {
                                return@LaunchedEffect
                            }
                            kotlinx.coroutines.delay(50)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            errorReconnecting = true
                            errorRetrySerial += 1
                            doErrorRetry()
                        },
                        modifier = Modifier.focusRequester(errorRetryFocus),
                    ) {
                        Text("Retry Now")
                    }
                    Button(onClick = onClose) {
                        Text("Close")
                    }
                }
            }
        }

        // ── Options sheets ───────────────────────────────────────────────
        // FormFactorModal-based (bottom sheet on touch, centered dialog on
        // TV); the dialog window owns D-pad input while open, so the root
        // key handler above never fights the rows.
        if (showOptionsSheet) {
            com.aeriotv.android.ui.FormFactorModal(onDismiss = { showOptionsSheet = false }) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        text = "Options",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (versionOptions.size > 1) {
                        PlayerOptionRow("Switch Version") {
                            showOptionsSheet = false
                            showVersionSheet = true
                        }
                    }
                    PlayerOptionRow("Audio Track") {
                        showOptionsSheet = false
                        showAudioSheet = true
                    }
                    PlayerOptionRow("Subtitles") {
                        showOptionsSheet = false
                        showSubtitlesSheet = true
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (showVersionSheet) {
            VodVersionPickerSheet(
                options = versionOptions,
                selected = selectedVersion,
                onSelect = { option ->
                    showVersionSheet = false
                    // Re-selecting the active version (or Auto while on Auto)
                    // would just re-tune the same copy; skip the churn.
                    if (option?.relationId != selectedVersion?.relationId) {
                        switchVersion(option)
                    }
                },
                onDismiss = { showVersionSheet = false },
            )
        }
        if (showAudioSheet) {
            exoPlayer?.let { p ->
                AudioTracksSheet(
                    tracks = remember { p.readAudioTracks() },
                    currentTrackId = p.readCurrentAid(),
                    onSelect = { id ->
                        p.selectAudioTrack(id)
                        showAudioSheet = false
                    },
                    onDismiss = { showAudioSheet = false },
                )
            }
        }
        if (showSubtitlesSheet) {
            exoPlayer?.let { p ->
                SubtitlesSheet(
                    tracks = remember { p.readSubtitleTracks() },
                    currentTrackId = p.readCurrentSid(),
                    onSelect = { id ->
                        p.selectSubtitleTrack(id)
                        showSubtitlesSheet = false
                    },
                    onDismiss = { showSubtitlesSheet = false },
                )
            }
        }
    }

    // Auto-hide. The extra keys are inert on phone (lastInteractionAt stays
    // 0, scrubTargetMs stays null, tvHoldChrome stays false) so phone timing
    // is unchanged. On TV: every handled remote press re-arms the timer, a
    // pending scrub pins the chrome, and pause holds the chrome up until
    // resume (iOS scheduleControlsHide fires only while playing).
    val tvHoldChrome = isTvForm && isPaused
    LaunchedEffect(chromeVisible, isDragging, lastInteractionAt, scrubTargetMs, tvHoldChrome) {
        if (chromeVisible && !isDragging && scrubTargetMs == null && !tvHoldChrome) {
            delay(AUTO_HIDE_MS)
            if (!isDragging) chromeVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { /* AndroidView.onRelease handles native cleanup. */ }
    }
}

@Composable
private fun BottomChrome(
    positionMs: Long,
    livePositionMs: Long,   // un-previewed playback position, for the delta
    isTvForm: Boolean,
    tvFocusZone: TvVodFocusZone = TvVodFocusZone.None,
    durationMs: Long,
    isPaused: Boolean,
    isDragging: Boolean,
    dragFraction: Float,
    onDragStart: () -> Unit,
    onDragChanged: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    isDvr: Boolean = false,
    onSeekToLive: () -> Unit = {},
    onOptions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var thumbCenterPx by remember { mutableFloatStateOf(0f) }
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    // The preview/target position the bubble reflects: the dragged spot
    // while a finger is down, else the D-pad scrub target, else live.
    val targetMs = when {
        isDragging -> (dragFraction * durationMs).toLong()
        else -> positionMs            // already = scrubTargetMs ?: live from caller
    }
    val showBubble = isDragging || (positionMs != livePositionMs)
    val delta = targetMs - livePositionMs

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            ScrubberBar(
                positionMs = positionMs,
                durationMs = durationMs,
                isDragging = isDragging,
                dragFraction = dragFraction,
                tvScrubberFocused = isTvForm && tvFocusZone == TvVodFocusZone.Scrubber,
                onDragStart = onDragStart,
                onDragChanged = onDragChanged,
                onDragEnd = onDragEnd,
                onThumbGeometry = { c, w -> thumbCenterPx = c; trackWidthPx = w },
            )
            Spacer(Modifier.height(6.dp))
            val displayMs = if (isDragging) (dragFraction * durationMs).toLong() else positionMs
            // Equal-weight side SLOTS with the transport at its natural width
            // in between. One weighted spacer either side looked centred but
            // was not: the right group (duration plus the Options button) is
            // wider than the elapsed time on the left, and splitting the
            // leftover space equally then pushed the transport left of screen
            // centre by half that difference. Slots pin the middle whatever
            // the sides hold, and each side stays inside its own half.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = formatTime(displayMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
            TransportIconButton(
                icon = Icons.Filled.Replay10,
                contentDescription = "Back 10 seconds",
                onClick = onSkipBack,
                focused = isTvForm && tvFocusZone == TvVodFocusZone.Rewind,
                isTvForm = isTvForm,
            )
            Spacer(Modifier.width(8.dp))
            val ppFocused = isTvForm && tvFocusZone == TvVodFocusZone.PlayPause
            Box(
                modifier = Modifier
                    .tvFocusScale(ppFocused)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (ppFocused) Color.White else Color.White.copy(alpha = 0.18f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTogglePlay() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (isPaused) "Play" else "Pause",
                    tint = if (ppFocused) Color.Black else Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            TransportIconButton(
                icon = Icons.Filled.Forward10,
                contentDescription = "Forward 10 seconds",
                onClick = onSkipForward,
                focused = isTvForm && tvFocusZone == TvVodFocusZone.Forward,
                isTvForm = isTvForm,
            )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
            if (isDvr) {
                // LIVE pill (iOS PlayerView): filled red within 15s of the
                // live edge, hollow/gray when scrubbed back. Tapping it
                // jumps to the live edge.
                val atLive = durationMs > 0L && displayMs >= durationMs - 15_000L
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSeekToLive,
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (atLive) Color(0xFFFF3B30)
                                else Color.White.copy(alpha = 0.4f),
                            ),
                    )
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (atLive) Color.White else Color.White.copy(alpha = 0.55f),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    text = formatTime(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            // Options entry point (rightmost): Switch Version / Audio Track /
            // Subtitles sheet. Same white-fill focus visual as the skip
            // buttons under the TV zone model.
            Spacer(Modifier.width(8.dp))
            TransportIconButton(
                icon = Icons.Outlined.Tune,
                contentDescription = "Player options",
                onClick = onOptions,
                focused = isTvForm && tvFocusZone == TvVodFocusZone.Options,
                isTvForm = isTvForm,
            )
            }
            }
        }
        // Floating scrub-readout bubble (iOS PlayerView.scrubReadout parity,
        // commit b7b7f6387). Overlay so it never shifts the timeline; it sits
        // above the thumb and fades/scales in only while the playhead moves.
        ScrubReadoutBubble(
            visible = showBubble,
            targetMs = targetMs,
            deltaMs = delta,
            thumbCenterPx = thumbCenterPx,
            trackWidthPx = trackWidthPx,
            isTvForm = isTvForm,
        )
    }
}

@Composable
private fun TransportIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    focused: Boolean,
    isTvForm: Boolean,
) {
    // Touch (phone): a Material IconButton so the skip controls keep the 48dp
    // minimum touch target, the Material ripple, and the Role.Button semantics
    // they had before the IconButton -> TransportIconButton swap.
    if (!isTvForm) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        return
    }
    // TV: white-fill + grow focus visual matching PlayerPill so the targeted
    // skip button reads as selected under the app-owned D-pad zone model (focus
    // is driven by the root key handler, not Compose traversal, so this is
    // purely a visual treatment).
    Box(
        modifier = Modifier
            .tvFocusScale(focused)
            .size(44.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Transparent)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** One action row of the player Options sheet. Plain clickable (focusable on
 *  TV by default inside the dialog window) - no radio, these rows only open
 *  the dedicated picker sheets. */
@Composable
private fun PlayerOptionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun ScrubReadoutBubble(
    visible: Boolean,
    targetMs: Long,
    deltaMs: Long,
    thumbCenterPx: Float,
    trackWidthPx: Float,
    isTvForm: Boolean,
) {
    val density = LocalDensity.current
    // Approx bubble half-width so it clamps inside the track instead of
    // clipping at the edges. Two lines of short monospace text ~ 56dp wide.
    val halfWidthPx = with(density) { 40.dp.toPx() }
    val clampedCenter = thumbCenterPx.coerceIn(
        halfWidthPx,
        (trackWidthPx - halfWidthPx).coerceAtLeast(halfWidthPx),
    )
    val bubbleXDp = with(density) { (clampedCenter - halfWidthPx).toDp() }
    // Sits above the track. TV chrome is larger so it lifts higher.
    val liftDp = if (isTvForm) (-78).dp else (-52).dp

    Box(
        modifier = Modifier
            .padding(start = bubbleXDp.coerceAtLeast(0.dp))
            .offset(y = liftDp),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(
                        horizontal = if (isTvForm) 22.dp else 14.dp,
                        vertical = if (isTvForm) 12.dp else 8.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatTime(targetMs.coerceAtLeast(0L)),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = if (isTvForm) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.titleMedium,
                )
                if (deltaMs != 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = signedDelta(deltaMs),
                        color = if (deltaMs < 0L) Color(0xFFFF9800) else Color(0xFF4CAF50),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = if (isTvForm) MaterialTheme.typography.labelLarge
                                else MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** "+2:30" / "-0:45": signed offset of the scrub target from the live
 * position. iOS PlayerView.signedDelta parity (commit b7b7f6387). */
private fun signedDelta(ms: Long): String {
    val sign = if (ms < 0L) "-" else "+"
    return sign + formatTime(abs(ms))
}

@Composable
private fun ScrubberBar(
    positionMs: Long,
    durationMs: Long,
    isDragging: Boolean,
    dragFraction: Float,
    tvScrubberFocused: Boolean = false,
    onDragStart: () -> Unit,
    onDragChanged: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onThumbGeometry: (thumbCenterPx: Float, trackWidthPx: Float) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val durationFloat = max(1L, durationMs).toFloat()
    val raw = if (isDragging) dragFraction else positionMs.toFloat() / durationFloat
    val filledFraction = raw.coerceIn(0f, 1f)
    val active = isDragging || tvScrubberFocused
    val trackHeight = if (active) 6.dp else 3.dp
    val thumbSize = if (active) 18.dp else 12.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            // Measure the track eagerly. The thumb's x = widthPx * fraction,
            // and widthPx used to be set only inside the pointerInput blocks,
            // which never ran while durationMs was 0 and could read size
            // before layout, leaving the thumb pinned to the left edge while
            // the fraction-based fill advanced correctly (user-visible in
            // catch-up playback, task #136).
            .onSizeChanged { widthPx = it.width.toFloat() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.25f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = filledFraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
        val thumbHalf = with(density) { (thumbSize / 2).toPx() }
        val thumbCenterPx = widthPx * filledFraction
        val thumbXPx = thumbCenterPx - thumbHalf
        val thumbX = with(density) { thumbXPx.toDp() }
        // Report the thumb center + active-track width so the floating
        // scrub-readout bubble (owned by BottomChrome) can sit above it.
        // widthPx starts at 1f and is set on first gesture; emit only once
        // it has been measured so the bubble doesn't snap from x=0.
        androidx.compose.runtime.LaunchedEffect(thumbCenterPx, widthPx) {
            if (widthPx > 1f) onThumbGeometry(thumbCenterPx, widthPx)
        }
        Box(
            modifier = Modifier
                .padding(start = thumbX.coerceAtLeast(0.dp))
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(durationMs) {
                    if (durationMs <= 0L) return@pointerInput
                    widthPx = size.width.toFloat()
                    // Track the fraction INSIDE the gesture scope. The
                    // `dragFraction` parameter is frozen at whatever value
                    // it had when this pointerInput coroutine (re)started -
                    // recompositions hand the modifier a NEW lambda but the
                    // OLD one keeps running - so committing it on drag-end
                    // seeks to a stale position (Z Fold field bug
                    // 2026-07-11: first catch-up drag re-tuned to 0s).
                    var lastGestureFraction = 0f
                    detectDragGestures(
                        onDragStart = { offset: Offset ->
                            onDragStart()
                            lastGestureFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                            onDragChanged(lastGestureFraction)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            lastGestureFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                            onDragChanged(lastGestureFraction)
                        },
                        onDragEnd = {
                            onDragEnd(lastGestureFraction)
                        },
                        onDragCancel = {
                            onDragEnd(lastGestureFraction)
                        },
                    )
                }
                .pointerInput(durationMs) {
                    if (durationMs <= 0L) return@pointerInput
                    widthPx = size.width.toFloat()
                    detectTapGestures(onTap = { offset ->
                        val f = (offset.x / widthPx).coerceIn(0f, 1f)
                        onDragStart()
                        onDragChanged(f)
                        onDragEnd(f)
                    })
                },
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSecs = ms / 1000L
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
