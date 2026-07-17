package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import com.aeriotv.android.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.miniplayer.MiniPlayerViewModel
import com.aeriotv.android.feature.multiview.AddToMultiviewSheet
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.settings.SettingsViewModel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val TAG = "PlayerScreen"
private const val AUTO_HIDE_MS = 4_000L
private const val SWIPE_THRESHOLD_PX = 120f
// Min gap between two hardware D-pad channel flips. Auto-repeat on a held UP/DOWN
// fires rapidly; this paces it so a hold surfs one channel at a time instead of
// skipping several, while a normal press cadence still flips immediately.
private const val FLIP_DEBOUNCE_MS = 120L

/**
 * Live-stream player screen. Hosts the MPV view + chrome overlay. Tap toggles
 * chrome. While chrome is visible, vertical swipe flips to the next/previous
 * channel without leaving the screen, mirroring iOS PlayerView.swift line 686
 * (`appleTVChannelFlip`).
 */
@Composable
fun PlayerScreen(
    channels: List<M3UChannel>,
    initialChannelId: String,
    isLive: Boolean = true,
    httpHeaders: Map<String, String> = emptyMap(),
    epgByChannel: Map<String, List<EPGProgramme>> = emptyMap(),
    // Task #148 milestone B (tvOS unified-player parity): non-blank
    // catchupUrl + csEnd > csStart puts this screen in CATCH-UP mode - the
    // live prime is skipped, the archive replay plays through
    // exoHolder.playCatchup, and the shared d-pad scrub commits re-tunes in
    // the programme domain [0, duration]. Phone never enters this mode (it
    // keeps VODPlayerScreen for catch-up, mirroring iPhone).
    catchupUrl: String = "",
    catchupTitle: String = "",
    catchupStartMillis: Long = 0L,
    catchupEndMillis: Long = 0L,
    catchupTz: String = "",
    catchupChannelUuid: String = "",
    /** Task #149: mint a fresh native session for a seek re-tune. */
    onRemintCatchup: suspend (channelUuid: String, currentUrl: String, absStartMillis: Long) -> String? =
        { _, _, _ -> null },
    /** Task #149: best-effort revoke of a native session (exit + re-tune). */
    onRevokeCatchup: (playbackUrl: String) -> Unit = {},
    onClose: () -> Unit = {},
    onLaunchMultiview: () -> Unit = {},
    onLoadChannelStreams: suspend (Int) -> List<StreamOption> = { emptyList() },
    onSwitchChannelStream: suspend (String, Int) -> String? = { _, _ -> null },
    onLoadCurrentStreamId: suspend (String) -> Int? = { null },
    onLoadCurrentStreamUrl: suspend (String) -> String? = { null },
    /** LAN/WAN verdict-flip signal (LAN URL key) for mid-stream re-tune. */
    onVerdictFlips: kotlinx.coroutines.flow.SharedFlow<String> =
        kotlinx.coroutines.flow.MutableSharedFlow(),
    /** Rebuild this channel's live proxy URL from the current LAN/WAN base. */
    onRebuildLiveUrl: suspend (String) -> String? = { null },
) {
    // Hold the screen awake while the fullscreen player is mounted. Without
    // this the system screen-timeout fires mid-stream after its idle window
    // (Samsung defaults to 30s in dim mode) and the user has to wake the
    // phone to keep watching. Mirrors iOS IdleTimerRefCount.increment() on
    // playback start (MPVPlayerView.swift line 4422). The DisposableEffect
    // inside KeepScreenOnWhilePlaying cleans the flag up automatically when
    // PlayerScreen leaves composition -- mini-player promotion + back-out
    // both trigger the dispose path naturally.
    KeepScreenOnWhilePlaying()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val miniPlayerVm: MiniPlayerViewModel = hiltViewModel()
    val appleTVChannelFlip by settingsVm.appleTVChannelFlip.collectAsStateWithLifecycle(initialValue = true)
    val streamBufferSize by settingsVm.streamBufferSize.collectAsStateWithLifecycle(initialValue = "default")
    val aspectMode by settingsVm.playerAspectMode.collectAsStateWithLifecycle(initialValue = "fit")
    // Live Rewind pref, to hint (below) that pause/rewind needs it turned on.
    val liveRewindEnabled by settingsVm.liveRewindEnabled.collectAsStateWithLifecycle(initialValue = true)
    val playerEntry = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlayerScreenEntryPoint::class.java,
        )
    }
    val exoHolder = remember { playerEntry.exoPlayerHolder() }
    val exoWindowState = remember { playerEntry.exoWindowState() }
    val timeshiftController = remember { playerEntry.timeshiftController() }
    // Cast Connect (GH #33) sender. isCasting drives the local-vs-remote swap:
    // while a cast session is connected we stop the local codec and mirror the
    // channel identity to the Android-TV receiver instead of playing here.
    val castSender = remember { playerEntry.castSender() }
    val castReceiver = remember { playerEntry.castReceiver() }
    val castState by castSender.state.collectAsStateWithLifecycle()
    val castPosition by castSender.position.collectAsStateWithLifecycle()
    val isCasting = castState is com.aeriotv.android.core.cast.AerioCastSender.State.Connected
    // GH #33 companion remote (second-screen): while connected to an AerioTV TV
    // over the LAN, this screen behaves EXACTLY like the cast flow -- local
    // playback is suspended and the same CastRemoteOverlay drives the TV's native
    // player over the companion socket. A live Cast session wins when both exist.
    val companionRemote = remember { playerEntry.companionRemote() }
    val companionDiscovery = remember { playerEntry.companionDiscovery() }
    val companionConn by companionRemote.connection.collectAsStateWithLifecycle()
    val isCompanion = !isCasting &&
        companionConn is com.aeriotv.android.core.cast.companion.CompanionRemoteController.Conn.Connected
    // "Some remote screen is playing this, not the phone" -- the shared gate for
    // every local-playback suppression below.
    val isRemote = isCasting || isCompanion
    // Own companion mDNS discovery for the player's whole lifetime (phones only;
    // the TV is the host, never a client). The cast button reads the devices flow
    // to decide its own visibility; if the BUTTON owned discovery (it lives inside
    // the auto-hiding chrome) the browse would restart on every chrome show and
    // the button would be invisible for the first seconds each time (device test
    // 2026-07-15). Refcounted with the chooser dialog's own start/stop.
    val uiModeIsTv = (
        androidx.compose.ui.platform.LocalContext.current.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK
        ) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    if (!uiModeIsTv) {
        DisposableEffect(companionDiscovery) {
            companionDiscovery.start()
            onDispose { companionDiscovery.stop() }
        }
    }
    // Set true while the companion "Disconnect" button tears down the remote and
    // exits the player, so the prime effect (which re-fires when isCompanion flips
    // false) does NOT resume LOCAL playback on the phone -- that left the channel
    // playing on BOTH the phone and the TV (device report 2026-07-15).
    var remoteStopping by remember { mutableStateOf(false) }

    // Channel-flip state. The MPV view stays alive across flips; only the
    // current channel index changes and we call playFile again with the new URL.
    // -1 when the id is not in the list yet (hydration race, or an event
    // channel the playlist refresh re-keyed). NEVER coerce a miss to 0: that
    // silently played channels[0] (the user report: picking World Cup #5200
    // played channel #1). getOrNull(-1) renders the loading state instead,
    // and the remember(channels) below re-resolves when the list lands.
    val initialIndex = remember(channels, initialChannelId) {
        channels.indexOfFirst { it.id == initialChannelId }
    }
    var currentIndex by remember(channels) { mutableIntStateOf(initialIndex) }
    val currentChannel = channels.getOrNull(currentIndex)

    // Task #148 milestone B: catch-up mode state. scrubTargetWallMs (below)
    // holds PROGRAMME-relative ms in this mode instead of wall-clock.
    val isCatchupMode = catchupUrl.isNotBlank() && catchupEndMillis > catchupStartMillis
    val isNativeCatchup = isCatchupMode &&
        (catchupChannelUuid.isNotBlank() || catchupUrl.contains("/proxy/catchup/"))
    val catchupDurationMs = (catchupEndMillis - catchupStartMillis).coerceAtLeast(0L)
    var catchupCurrentUrl by remember { mutableStateOf(catchupUrl) }
    var catchupOffsetMs by remember { mutableStateOf(0L) }
    var catchupPositionMs by remember { mutableStateOf(0L) }
    // Task #149: serialized native re-mints (rapid skips coalesce to the
    // latest target; see VODPlayerScreen's twin for the rationale).
    var nativeRemintInFlight by remember { mutableStateOf(false) }
    var nativeRemintPendingMs by remember { mutableStateOf<Long?>(null) }

    // GH #22: a tapped id that is NOT in the active playlist's channel list
    // used to render a silent forever-black player -- no prime, no log lines
    // at all (FractalBoy's 0.3.1 report: repro after switching playlists,
    // Stream Info idle, Dispatcharr shows no client). An EMPTY list is the
    // normal hydration race and keeps the loading state (the remembers above
    // re-resolve when it lands); a NON-empty list that's missing the id is a
    // real miss -- surface it and offer the way out instead of dying quietly.
    if (channels.isNotEmpty() && currentChannel == null) {
        LaunchedEffect(initialChannelId) {
            Log.w(
                TAG,
                "[TUNE] channel id $initialChannelId not in active list " +
                    "(n=${channels.size}) -- surfacing not-found instead of idling",
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Channel Not Available",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = "This channel isn't in the active playlist. If you just " +
                    "switched playlists, go back and pick it again from the " +
                    "refreshed guide.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
            Button(onClick = onClose) {
                Text("Go Back")
            }
        }
        return
    }
    // Same treatment for a channel with no stream URL (event channels whose
    // stream isn't assigned yet). The prime effect already no-ops on a blank
    // url; without this the user sat on a silent black screen. A later
    // channels refresh that fills the url recomposes straight into playback.
    if (currentChannel != null && currentChannel.url.isBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No Stream Assigned",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = "This channel doesn't have a stream yet. Event channels " +
                    "usually get one shortly before air time.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
            Button(onClick = onClose) {
                Text("Go Back")
            }
        }
        return
    }
    // Focus target that holds D-pad focus during fullscreen playback (chrome
    // hidden) so the remote's up/down reaches the channel-flip handler on TV.
    val playbackFocus = remember { FocusRequester() }
    val nowProgramme by remember(epgByChannel, currentChannel) {
        derivedStateOf {
            // Catch-up: the info card / footer must describe the REPLAYED
            // programme, not whatever happens to be airing live on the
            // channel right now (task #148 milestone B polish).
            if (isCatchupMode) {
                EPGProgramme(
                    channelId = currentChannel?.guideMatchKey.orEmpty(),
                    title = catchupTitle.ifBlank { currentChannel?.name.orEmpty() },
                    description = "",
                    startMillis = catchupStartMillis,
                    endMillis = catchupEndMillis,
                    category = "",
                )
            } else {
                currentChannel?.let { epgByChannel[it.guideMatchKey]?.nowPlaying() }
            }
        }
    }

    // Persist last-watched channel for the App Behaviors > Resume Last Channel
    // toggle. Writes whenever the user flips to a new channel; AerioTVNavHost
    // reads this once on cold boot to decide whether to auto-launch into the
    // player. Also seeds the mini-player session so a system back can promote
    // it without losing channel context.
    LaunchedEffect(currentChannel?.id) {
        // Catch-up replays never become the resume target / recents entry /
        // mini-player session (task #148 milestone B).
        if (isCatchupMode) return@LaunchedEffect
        currentChannel?.let { ch ->
            if (ch.id.isNotBlank()) {
                settingsVm.setLastWatchedChannelId(ch.id)
                // Feed the LRU recents list (AddToMultiview "Recent" section,
                // iOS RecentChannelsStore parity).
                settingsVm.recordRecentChannel(ch.id)
            }
            miniPlayerVm.setCurrentChannel(ch)
        }
    }

    // Mount-time hook: if we're resuming from the mini-player the background
    // PlaybackService is still running with the notification surfaced and MPV
    // in audio-only mode. Stop the service (we're foreground again) and flip
    // video output back on.
    //
    // Phase 165: also request the PersistentMpvWindow into Fullscreen mode
    // so the SurfaceView (mounted at MainActivity root) fills the screen
    // beneath our chrome overlays.
    LaunchedEffect(Unit) {
        // Apply Dispatcharr / Xtream auth headers + custom User-Agent
        // before the first setMediaSource so the DataSource picks them
        // up. Replays on every mount so reentering the player after a
        // settings change (e.g. swapping API key) takes effect on the
        // next channel tap.
        exoHolder.httpHeaders = httpHeaders
        exoWindowState.requestFullscreen()
        // LAN/WAN terminal-error failover hook (iOS PlayerSession.failoverRetryCurrent):
        // on a terminal player error the holder asks this to re-probe LAN/WAN and
        // hand back a fresh /proxy/ts/stream/<uuid> URL instead of replaying a
        // possibly dead-host lastPlayUrl. Only Dispatcharr-live channels qualify.
        exoHolder.onTerminalErrorRebuildUrl = {
            currentChannel?.id?.takeIf { it.startsWith("disp:") }
                ?.let { onRebuildLiveUrl(it.removePrefix("disp:")) }
        }
        // Bring up the MediaSessionService so the session is alive before the
        // first frame. Idempotent -- if it's already running this is a no-op.
        // NOT while casting: the phone isn't playing locally, so a media FGS would
        // post a second "Casting to <TV>" notification competing with the
        // standalone cast chip (GH #33 - re-entering the player from the
        // Now-Casting mini controller is the path that hit this).
        if (!isRemote) {
            com.aeriotv.android.core.playback.AerioMediaPlaybackService
                .startBackground(context)
        }
    }

    // Clear the LAN/WAN failover hook when leaving the player so a backgrounded /
    // Auto session never re-tunes through this screen's closed-over state.
    DisposableEffect(Unit) {
        onDispose { exoHolder.onTerminalErrorRebuildUrl = null }
    }

    // Cast Connect (GH #33): free/restore the LOCAL codec + surface as the cast
    // session comes and goes. Stopping (not just pausing) the holder releases the
    // MediaCodec and stops phone-side audio while the TV plays; on disconnect we
    // restore the fullscreen surface and the prime effect above re-tunes locally.
    LaunchedEffect(isRemote) {
        if (isRemote) {
            runCatching { exoHolder.stop() }
            runCatching { exoWindowState.hide() }
            // Also stop the media FGS the LOCAL mount started: the phone isn't
            // playing anything now, and the leftover session (stuck PLAYING with
            // a stale position) kept an "AerioTV" media notification in the shade
            // through the whole remote session AND after Disconnect (device test
            // 2026-07-15). The in-app Controlling/Now-Casting card is the
            // re-entry point; the notification is purely local playback's.
            runCatching {
                com.aeriotv.android.core.playback.AerioMediaPlaybackService.stop(context)
            }
        } else {
            runCatching { exoWindowState.requestFullscreen() }
        }
    }

    // GH #33 (receiver side): when THIS TV is playing a cast and the phone flips
    // the channel, the receiver's Navigation routes the request here instead of
    // re-navigating. Move currentIndex so the persistent ExoPlayer re-primes to
    // the new channel in place -- no nav, no PiP-pop. No-op on the sending phone
    // (the request flow stays null there).
    val castFlipChannels by rememberUpdatedState(channels)
    LaunchedEffect(Unit) {
        castReceiver.castChannelRequest.collect { requestedId ->
            if (requestedId == null) return@collect
            val idx = castFlipChannels.indexOfFirst { it.id == requestedId }
            if (idx >= 0) currentIndex = idx
            castReceiver.consumeCastChannelRequest()
        }
    }

    // Channel-switch / first-mount setMediaItem: when the held Exo
    // player is on a different channel than the user just selected,
    // swap streams via setMediaSource. The PlayerView at MainActivity
    // root holds the surface across this so no reattach is required.
    LaunchedEffect(currentChannel?.id, isCasting, isCompanion) {
        // Task #148 milestone B: catch-up mode never primes the LIVE stream
        // (and never starts a rewind buffer session below).
        if (isCatchupMode) return@LaunchedEffect
        // Companion Disconnect is tearing this player down -- do not resume local
        // playback as isCompanion flips false (GH #33 double-play fix).
        if (remoteStopping) return@LaunchedEffect
        val channelId = currentChannel?.id ?: return@LaunchedEffect
        val ch = currentChannel ?: return@LaunchedEffect
        val url = ch.url
        if (url.isBlank()) return@LaunchedEffect
        // GH #33 companion remote: mirror the channel to the paired TV over the
        // LAN socket instead of priming locally. Dedup against the last channel
        // this phone sent so re-entering the player for the SAME channel (mini
        // card tap) doesn't needlessly re-tune the TV.
        if (isCompanion) {
            if (companionRemote.currentChannelId.value != ch.id) {
                companionRemote.setRemoteChannel(ch.id, ch.name)
            }
            return@LaunchedEffect
        }
        // Cast Connect (GH #33): while casting, don't prime the LOCAL codec.
        // Mirror the channel IDENTITY to the Android-TV receiver, which rebuilds
        // its own /proxy/ts/ URL and plays with its own ExoPlayer. A channel flip
        // re-fires this and re-casts. The suspend effect below frees the local
        // codec so the phone isn't decoding in parallel.
        if (isCasting) {
            // Re-tune the receiver ONLY on a genuine channel change. Re-entering
            // the player from the Now-Casting mini controller re-fires this effect
            // for the SAME channel; without this guard we'd re-issue setContent()
            // (an autoplay load) + setRemoteChannel and make the TV needlessly
            // reload/flicker the feed it is already playing. The sender's current
            // content mediaId is the source of truth for "what the TV is on".
            // A cast resumed after an app restart only recovers the channel TITLE
            // as mediaId (the receiver's bridged MediaSession drops our id), so
            // also treat a title match as "already on this channel" -- otherwise
            // re-entering it would needlessly re-tune the TV (GH #33).
            // On resume mediaId IS the channel name, so a name match on mediaId
            // covers that case. (Do NOT also match cc.title==ch.name: on a normal
            // cast that would wrongly suppress a real switch between two channels
            // sharing a name.)
            val cc = castSender.content.value
            val alreadyCastingThisChannel = cc?.mediaId == ch.id || cc?.mediaId == ch.name
            if (!alreadyCastingThisChannel) {
                castSender.setContent(
                    com.aeriotv.android.core.cast.AerioCastSender.Content(
                        mediaId = ch.id,
                        kind = com.aeriotv.android.core.cast.AerioCastReceiverController.Kind.LIVE,
                        title = ch.name,
                        subtitle = nowProgramme?.title,
                        artUri = ch.tvgLogo.takeIf { it.isNotBlank() },
                        // Web/Styled-receiver path (GH #33): non-Android-TV Cast
                        // targets can't launch Cast Connect, so hand them a directly-
                        // playable Dispatcharr fMP4 (H.264+AAC) URL. The ATV receiver
                        // ignores it and rebuilds its own raw-TS URL. Null for non-
                        // proxy channels (Cast Connect stays the only path there).
                        webCastUrl = com.aeriotv.android.core.cast.webReceiverCastUrl(url),
                    ),
                )
                // The initial load launches the receiver via the entity deep link,
                // but Cast Connect won't re-deliver a second load() to an already-
                // running receiver -- so also push the channel over the reliable
                // control channel, which re-tunes the TV in place on every flip.
                castSender.setRemoteChannel(ch.id)
            }
            return@LaunchedEffect
        }
        // GH #22: also re-prime when the holder claims this channel but is
        // actually IDLE (a stop path that missed clearing currentChannelId).
        // Skipping the prime against a dead player was the silent-black-
        // screen failure: no logs, Stream Info idle, no server client.
        if (exoHolder.currentChannelId != channelId || exoHolder.isIdle()) {
            Log.i(TAG, "Channel switch on Exo persistent player -> $url")
            // Refresh headers each switch -- some Dispatcharr deployments
            // rotate the API key per stream.
            exoHolder.httpHeaders = httpHeaders
            // Pass title / subtitle / logo to the MediaItem so
            // MediaSessionService renders the right notification +
            // lock-screen art (task #64). The mediaMetadata fields
            // flow through Player.currentMediaItem.mediaMetadata to
            // the session.
            val artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            exoHolder.playUrl(
                url = url,
                title = ch.name,
                subtitle = nowProgramme?.title.orEmpty(),
                artworkUri = artworkUri,
                // GH #27: #KODIPROP DRM signalling for encrypted DASH.
                drmLicenseType = ch.drmLicenseType,
                drmLicenseKey = ch.drmLicenseKey,
            )
            exoHolder.currentChannelId = channelId
        } else {
            // GH #22 diagnosability: a skipped prime used to be invisible
            // in logs. The idle case above re-primes; this remaining skip
            // is the legitimate "already playing this channel" path.
            Log.i(TAG, "[TUNE] prime skipped: holder already playing $channelId")
        }
        // Live Rewind: (re)start the timeshift buffer for this channel.
        // No-op when the pref is off. Fullscreen single-stream only per
        // the locked v1 scope; leaving this screen stops the session
        // (DisposableEffect below), so mini-player, multiview, and PiP
        // handoffs all drop back to pure live.
        // Gate on the tee's own URL test: only raw MPEG-TS is mirrored
        // into the buffer, so an HLS/DASH live channel must not start a
        // session (it would show a transport over a permanently empty
        // buffer and error-loop on the first pause/rewind).
        if (exoHolder.canBufferLiveRewind(url)) {
            timeshiftController.onFullscreenLiveStarted(channelId, ch.name, url, httpHeaders)
        } else {
            timeshiftController.onFullscreenLiveStopped()
        }
    }

    // Live Rewind: end the buffer session when fullscreen live ends.
    // Buffered segments stay on disk until the retention reaper runs.
    DisposableEffect(Unit) {
        onDispose {
            if (exoHolder.isTimeshifting) exoHolder.goLive()
            timeshiftController.onFullscreenLiveStopped()
        }
    }

    // Live Rewind keeps buffering THROUGH PiP (user directive 2026-07-11,
    // Z Fold field test: "I'd rather keep the buffer going in PiP" -
    // reversing the earlier stop-on-PiP-enter). PiP itself has no rewind
    // transport, but the buffer keeps growing so returning to fullscreen
    // can rewind across the PiP stretch like a cable box. The session
    // still ends when this screen unmounts (DisposableEffect above);
    // rewind PLAYBACK from PiP stays out of scope (v1: fullscreen only).

    // System back intercept. Two flavours:
    //   - Phone: promote to the bottom MiniPlayerRow above the nav, kill
    //     video output (vid=no -> mpv folds vo=null) to free the GPU, and
    //     start the foreground PlaybackService so audio survives the
    //     activity going to background. The held MPV stays running.
    //   - TV: keep video enabled (the TvMiniPlayerOverlay shows the live
    //     stream in a top-right window, tvOS PlayerSession parity). Toggling
    //     vid off here would force vo=null and the mini would be black even
    //     after re-attaching the surface. PlaybackService isn't needed
    //     either because the app stays foregrounded behind the mini.
    val isTvForm = (
        context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_TYPE_MASK
        ) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    // GH #7 (True Android Fullscreen): on phone/tablet, hide the status + nav
    // bars in LANDSCAPE so playback is genuinely edge-to-edge instead of
    // letterboxed under the system chrome the rest of the app draws (we run
    // enableEdgeToEdge app-wide). In PORTRAIT we keep the status bar so the top
    // control banner never slides under a camera cutout: in portrait the OS
    // always reserves the cutout area with the status bar, which is reliable on
    // every device, whereas the DisplayCutout inset is not always exposed to
    // apps (e.g. the Samsung Z Fold cover screen reports none). The banners also
    // pad by statusBars union displayCutout. Keyed on orientation so a rotation
    // re-applies the right mode; bars restored on dispose. TV has no system bars.
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
            }
        }
    }

    // UHD judder fix (seamless content frame-rate matching) now lives in
    // PersistentExoWindow, which owns the SurfaceView whose Surface the
    // refresh-rate match is requested on. The old window-level
    // preferredDisplayModeId pin lived HERE but was a NON-seamless HDMI
    // re-handshake that destroyed the SurfaceView mid-stream and blacked out
    // the video on TV boxes (GOTCHA 23) -- replaced with the seamless
    // Surface.setFrameRate path.

    // Chrome auto-starts visible on phone (user can immediately reach
    // controls + close) but hidden on TV (the user just pressed a
    // channel and wants to watch -- 1st Back press surfaces chrome,
    // tvOS-style). See BackHandler below for the three-press TV flow.
    // Declared HERE (above BackHandler) so the BackHandler closure
    // can read + mutate it.
    var chromeVisible by remember { mutableStateOf(!isTvForm) }
    // (chromeFromFlip removed in task #148: flips no longer raise the
    // bottom chrome at all - only the top program card via the
    // launch-hint window - so the latch had nothing left to track.)
    // Last user interaction timestamp. Bumped on D-pad presses while
    // chrome is visible so the auto-hide timer re-arms instead of firing
    // mid-traversal. Phase 172.
    var lastInteractionAt by remember { mutableStateOf(0L) }
    androidx.activity.compose.BackHandler {
        if (isCatchupMode) {
            // Task #148 milestone B: Back on a catch-up replay exits to where
            // the user came from (tvOS parity: Menu on the catch-up player
            // returns to the guide). No mini for a replay - the mini is a
            // LIVE affordance. The native session revoke runs in onDispose.
            exoHolder.stop()
            onClose()
        } else if (isTvForm) {
            // #10 back model (Archie 2026-07-02): a SINGLE Back minimizes the
            // fullscreen player straight to the corner mini. OK/Select is now
            // what raises the media controls (the tap-target toggles
            // chromeVisible), so Back no longer needs the old reveal-chrome
            // first step -- it matches tvOS, where Menu with chrome visible
            // minimizes, just without the extra press when chrome is hidden.
            //
            // Persistent-SurfaceView mini path: flip the root-level
            // PersistentExoWindow into Mini mode (top-right), promote
            // MiniPlayerSession to Active. The SurfaceView never reparents --
            // only its size + position. No reload, no ANR, no fresh-handle
            // race. The stream just keeps playing. From the mini, Back =
            // expand / double-Back = top channel (see TvMiniPlayerOverlay);
            // playback only ever ends by playing something else (tvOS parity:
            // there is no explicit Stop in fullscreen or the mini).
            exoWindowState.requestMini()
            miniPlayerVm.showMiniPlayer()
            onClose()
        } else if (isRemote) {
            // GH #33: while casting / companion-controlling there is NO local
            // playback to keep alive (LaunchedEffect(isRemote) stopped the local
            // codec). Back just returns to the scaffold, where the persistent
            // mini controller (Now Casting / Controlling <TV>) is the re-entry
            // point. Arming the local audio-only mini here would both start
            // phone-side background audio AND stack a second card.
            onClose()
        } else {
            // Phone Back: promote to bottom-bar audio-only mini chip,
            // hide the persistent video window, keep playback going
            // via the AerioMediaPlaybackService. The MediaItem metadata
            // already carries title / subtitle / logo (set in the
            // channel-switch LaunchedEffect above) so the service's
            // notification renders correctly the moment we
            // foreground it.
            miniPlayerVm.showMiniPlayer()
            currentChannel?.let { _ ->
                exoWindowState.hide()
                com.aeriotv.android.core.playback.AerioMediaPlaybackService
                    .startBackground(context)
            }
            onClose()
        }
    }

    // Chrome + ad-hoc sub-modal state.
    // chromeVisible declared above (before the BackHandler).

    // Launch-hint: the info pill appears briefly when the user just opened
    // a channel, then auto-hides. Independent of chromeVisible so the
    // initial "what am I watching" hint doesn't drag the rest of the
    // chrome (close button, control bar, dim scrim) with it. After the
    // first auto-hide the pill follows chromeVisible (i.e. the Back-press
    // path surfaces it alongside the full chrome).
    var launchHintActive by remember { mutableStateOf(true) }
    LaunchedEffect(currentChannel?.id) {
        // Re-arm whenever the user channel-flips to a new id.
        launchHintActive = true
        kotlinx.coroutines.delay(AUTO_HIDE_MS)
        launchHintActive = false
    }
    val pillVisible = chromeVisible || launchHintActive
    var audioOnly by remember { mutableStateOf(false) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var streamInfo by remember { mutableStateOf<StreamInfoSnapshot?>(null) }
    var subtitles by remember { mutableStateOf<SubtitlesState?>(null) }
    var audioTracks by remember { mutableStateOf<AudioTracksState?>(null) }
    var switchStream by remember { mutableStateOf<SwitchStreamState?>(null) }
    // Marked-current stream id for the Switch Stream sheet. We don't cheaply
    // know the proxy's active stream, so track the last one the user switched
    // to (reset on channel change) to radio-mark it on re-open.
    var switchedStreamId by remember(currentChannel?.id) { mutableStateOf<Int?>(null) }

    // Follow EXTERNAL upstream switches we didn't initiate: a stream changed from
    // the Dispatcharr WebUI Stats page, OR Dispatcharr's automatic server-side
    // failover when the playing stream dies. Both keep our /proxy/ts/stream
    // connection open and only mutate the channel's metadata.url (surfaced by
    // /proxy/ts/status), so the deep live buffer absorbs the splice and ExoPlayer
    // never self-flushes. Poll status.url while steadily playing a Dispatcharr
    // channel in the foreground; on a confirmed divergence re-prime (keepalive-held)
    // onto the new stream. Gated on reachedSteadyPlayback so it can never overlap
    // the cold-start no-data watchdog, and parked during a manual switch / any
    // in-flight re-prime. repeatOnLifecycle(RESUMED) pauses it when backgrounded/PiP.
    val followLifecycleOwner = LocalLifecycleOwner.current
    val isDispatcharrLive = !isCatchupMode && currentChannel?.dispatcharrChannelId != null &&
        currentChannel?.id?.startsWith("disp:") == true
    LaunchedEffect(currentChannel?.id, isDispatcharrLive) {
        if (!isDispatcharrLive) return@LaunchedEffect
        val ch = currentChannel ?: return@LaunchedEffect
        val uuid = ch.id.removePrefix("disp:")
        val proxyUrl = ch.url
        followLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var baseline: String? = null     // last-known status.url for this channel/foreground session
            var backoffMs = 4_000L
            var deadStatusCount = 0           // consecutive 404/dead-session polls (GH #33 freeze recovery)
            while (isActive) {
                delay(backoffMs)
                if (currentChannel?.id != ch.id) break
                // a manual switch, any re-prime, or an active rewind owns the
                // player -> park, re-seed after (a re-prime mid-rewind would
                // silently yank playback to live)
                if (switchStream != null || exoHolder.isReprimeInFlight ||
                    exoHolder.isTimeshifting) { baseline = null; continue }
                // only while steady: mutually exclusive with the cold-start no-data watchdog
                if (!exoHolder.reachedSteadyPlayback.value) { baseline = null; continue }
                // OFF the main thread: the GET + JSON parse + auth-retry must never run on
                // Main or it drops frames every tick (a periodic judder with no rebuffer).
                // Tri-state (review 2026-07-15): null = transport/auth failure
                // (UNKNOWN -- don't count it as a dead session; a Wi-Fi blip or a
                // server restart while we coast on the live buffer is not a wedge),
                // "" = Dispatcharr answered "no active session" (CONFIRMED dead),
                // url = alive.
                val statusUrl = withContext(Dispatchers.IO) { runCatching { onLoadCurrentStreamUrl(uuid) }.getOrNull() }
                if (statusUrl == null) {
                    // Unknown: transport error. Back off and RE-READ, but never
                    // reprime on this alone -- reset the dead counter so a real
                    // outage doesn't accumulate across transient failures.
                    backoffMs = (backoffMs + 4_000L).coerceAtMost(12_000L)
                    deadStatusCount = 0
                    continue
                }
                if (statusUrl.isBlank()) {
                    // Confirmed dead session: Dispatcharr has no active connection
                    // for this channel. One blank is transient (mid-switch blip) ->
                    // back off. A SUSTAINED dead session while we still intend to
                    // PLAY means our read wedged and the proxy dropped us (Shield
                    // field freeze 2026-07-15: status 404 for >1min, no recovery).
                    // Hand Dispatcharr a fresh connection via a keepalive re-prime.
                    // Gated on playWhenReady: a user pause legitimately stops our
                    // read and drops the session -- do NOT force it back to life.
                    backoffMs = (backoffMs + 4_000L).coerceAtMost(12_000L)
                    val stillPlaying = withContext(Dispatchers.Main.immediate) {
                        exoHolder.player?.playWhenReady == true
                    }
                    if (stillPlaying && ++deadStatusCount >= 3 && currentChannel?.id == ch.id &&
                        switchStream == null && !exoHolder.isReprimeInFlight &&
                        !exoHolder.isTimeshifting
                    ) {
                        android.util.Log.w(
                            "DispatcharrSwitch",
                            "[FOLLOW] dead session (status 404 x$deadStatusCount) ch=${ch.id}; reconnecting",
                        )
                        val ran = exoHolder.reprimeWithKeepalive(
                            url = proxyUrl,
                            title = ch.name,
                            subtitle = nowProgramme?.title.orEmpty(),
                            artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                                ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
                        )
                        if (ran) { deadStatusCount = 0; baseline = null }
                    }
                    continue
                }
                backoffMs = 4_000L
                deadStatusCount = 0
                if (baseline == null) { baseline = statusUrl; continue }   // seed
                if (statusUrl != baseline) {
                    // confirm with one re-read so a momentary mid-switch blip can't trip us
                    val confirm = withContext(Dispatchers.IO) { runCatching { onLoadCurrentStreamUrl(uuid) }.getOrNull() }
                    if (confirm != statusUrl) continue
                    if (switchStream != null || exoHolder.isReprimeInFlight ||
                        exoHolder.isTimeshifting ||
                        !exoHolder.reachedSteadyPlayback.value || currentChannel?.id != ch.id) continue
                    android.util.Log.w(
                        "DispatcharrSwitch",
                        "[FOLLOW] external switch ch=${ch.id} re-priming onto $statusUrl",
                    )
                    val ran = exoHolder.reprimeWithKeepalive(
                        url = proxyUrl,
                        title = ch.name,
                        subtitle = nowProgramme?.title.orEmpty(),
                        artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
                    )
                    if (ran) baseline = statusUrl    // adopt new baseline only after a real re-prime
                }
            }
        }
    }

    // Mid-stream LAN/WAN re-tune failover (iOS PlayerSession.retuneCurrentToActiveURL,
    // commit e6ca1d207). When the reachability probe flips a verdict while a
    // Dispatcharr-live channel is playing (the leaving-home-WiFi / WiFi-drop
    // case), rebuild the /proxy/ts/stream/<uuid> URL from the now-reachable base
    // and re-prime onto it (keepalive-held) instead of freezing on the dead host
    // and waiting for the watchdog to replay the stale lastPlayUrl. Scoped to the
    // RESUMED player; parked during a manual switch or any in-flight re-prime.
    LaunchedEffect(currentChannel?.id, isDispatcharrLive) {
        if (!isDispatcharrLive) return@LaunchedEffect
        val ch = currentChannel ?: return@LaunchedEffect
        val uuid = ch.id.removePrefix("disp:")
        followLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            onVerdictFlips.collect {
                if (currentChannel?.id != ch.id) return@collect
                if (switchStream != null || exoHolder.isReprimeInFlight ||
                    exoHolder.isTimeshifting) return@collect
                val newUrl = withContext(Dispatchers.IO) {
                    runCatching { onRebuildLiveUrl(uuid) }.getOrNull()
                } ?: return@collect
                // Only re-prime when the base actually changed; a no-op flip
                // (same host) costs nothing (mirrors iOS "primary != current" guard).
                if (newUrl == exoHolder.currentPlayUrl) return@collect
                if (currentChannel?.id != ch.id || switchStream != null ||
                    exoHolder.isReprimeInFlight || exoHolder.isTimeshifting) return@collect
                Log.w(TAG, "[RETUNE] LAN/WAN flip -> re-priming ch=${ch.id} onto $newUrl")
                exoHolder.reprimeWithKeepalive(
                    url = newUrl,
                    title = ch.name,
                    subtitle = nowProgramme?.title.orEmpty(),
                    artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                        ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
                    bypassCooldown = true,
                )
            }
        }
    }
    var playbackSpeedSheet by remember { mutableStateOf<Float?>(null) }
    var multiviewPickerOpen by remember { mutableStateOf(false) }
    // True while the chrome's Options menu or Sleep sheet is open; pauses the
    // auto-hide timer so the chrome does not fade mid-interaction.
    var chromeMenuOpen by remember { mutableStateOf(false) }
    // GH #33: the cast/companion device chooser (rendered inside the auto-hiding
    // chrome's castSlot) pins the chrome open via interactionLocked below.
    var castChooserOpen by remember { mutableStateOf(false) }

    // Sleep timer: stores the wall-clock millis at which the player should close.
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    var sleepRemainingMillis by remember { mutableStateOf<Long?>(null) }

    // Phase 165: mpvView is now derived from mpvHolder.view (single
    // persistent View at root). Kept as a local val inside the chrome
    // Box below for parity with the old factory-captured reference.

    // Publish playback state for the activity's leave-the-app handling: video
    // (not audio-only) auto-enters PiP; audio-only instead keeps a background
    // media notification (no PiP). Cleared when the player leaves composition.
    DisposableEffect(audioOnly, isRemote, currentChannel?.id, nowProgramme?.title) {
        PipState.nowPlayingTitle = currentChannel?.name ?: "AerioTV"
        PipState.nowPlayingSubtitle = nowProgramme?.title.orEmpty()
        PipState.nowPlayingLogo = currentChannel?.tvgLogo?.takeIf { it.isNotBlank() }
        // GH #33: while casting, the local player is stopped and the TV is
        // playing, so leaving the phone app must NOT auto-enter PiP (nor arm a
        // local audio notification) -- the Now-Casting mini controller is the
        // re-entry path instead.
        PipState.videoPlaybackActive.value = !audioOnly && !isRemote
        PipState.audioPlaybackActive.value = audioOnly && !isRemote
        onDispose {
            PipState.videoPlaybackActive.value = false
            PipState.audioPlaybackActive.value = false
        }
    }

    val streamUrl = currentChannel?.url.orEmpty()

    // Dead-upstream net: the holder flips this true when a freshly-tuned live
    // stream produced no data even after a reconnect (AerioExoPlayerHolder
    // no-data watchdog). Drives the "Channel unavailable" overlay below instead
    // of an endless black screen. A channel flip / re-tap clears it.
    val streamUnavailable by exoHolder.streamUnavailable.collectAsStateWithLifecycle()
    // Task #150: escalation counter for the unavailable overlay's auto-retry
    // (5s doubling to a 30s cap). Bumped per retry; reset on channel change.
    var unavailableRetrySerial by remember(currentChannel?.id) { mutableIntStateOf(0) }

    // Returning to the foreground player must always restore video unless the
    // user explicitly chose Audio Only. A media-session controller (or the old
    // car-audio path) could have disabled the video track while we were
    // backgrounded; without this the user came back to a black screen with
    // sound and the Audio Only toggle's state did not match the real track
    // (user report). audioOnly stays the single source of truth.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, audioOnly) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // A companion remote's Audio Only is an equally explicit user
                // choice -- the resume restore must not clobber it (GH #33).
                exoHolder.setVideoTrackEnabled(!(audioOnly || exoHolder.remoteAudioOnly))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Blank-screen guard after a Picture-in-Picture close. MainActivity's
    // onPictureInPictureModeChanged tears playback down on an X-dismiss
    // (exoHolder.stop() + exoWindowState.hide()) but has no NavController to
    // pop THIS route, so the app is left sitting on a live PlayerScreen whose
    // video window is now Hidden (0dp) and whose chrome has auto-hidden -- a
    // blank screen that only a force-stop cleared (Z Fold 5 tester repro:
    // play -> HOME/auto-PiP -> close PiP with its X -> reopen app = blank).
    // Every IN-APP teardown (explicit chrome X, phone Back-to-mini, multiview
    // launch) already pairs hide() with onClose(); this catches the one path
    // that can't reach the NavController. Guarded by windowWasShown so the
    // initial mount -- Hidden until LaunchedEffect(Unit) requestFullscreen()s
    // -- never self-pops, and scoped to ON_RESUME so it only fires when we
    // return to a screen whose window was pulled out from under it while away.
    var windowWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        exoWindowState.mode.collect { if (it != ExoWindowState.Mode.Hidden) windowWasShown = true }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                windowWasShown &&
                exoWindowState.mode.value == ExoWindowState.Mode.Hidden &&
                // GH #33: while this TV is a live Cast Connect receiver, a channel
                // change arrives as a cast LOAD that relaunches MainActivity; the
                // transient Hidden window during that relaunch is NOT a PiP-X
                // dismiss, so don't pop to the guide -- the incoming deep link
                // re-tunes the persistent player in place.
                !castReceiver.isReceivingCast()
            ) {
                Log.i(TAG, "resumed onto a hidden player window (PiP X-dismiss) -> popping player")
                // GH #15: this pop lands on a fresh guide composition with the
                // mini already dismissed, so no effect restores D-pad focus and
                // Google TV devices drop Compose focus across the stop/restart,
                // deadening the remote. Hand the guide a one-shot restore
                // request before popping.
                miniPlayerVm.session.requestGuideFocusRestore()
                onClose()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Live Rewind transport state (declared before the chrome canvas so
    // the root key handler below can reach it).
    val tsState by timeshiftController.state.collectAsStateWithLifecycle()
    var tsPositionWallMs by remember { mutableStateOf(0L) }
    var tsPaused by remember { mutableStateOf(false) }
    var livePauseWallMs by remember { mutableStateOf(0L) }

    // Shared D-pad LEFT/RIGHT scrub for the live rewind buffer (task
    // #148, tvOS parity; catch-up joins when it unifies into this
    // player). Each press/repeat steps a PREVIEW position - no seek per
    // press, because a seek is a whole buffer re-open - and the single
    // seek commits 650ms after the presses stop. Holding accelerates
    // 1x..12x on native key repeats (10s base step). Active with the
    // chrome hidden (band-only HUD renders) or with the timeline band
    // focused (UP from the pill row).
    var scrubTargetWallMs by remember { mutableStateOf<Long?>(null) }
    var scrubHudVisible by remember { mutableStateOf(false) }
    var scrubAccelCount by remember { mutableStateOf(0) }
    var scrubLastDirection by remember { mutableStateOf(0) }
    var scrubLastStepAt by remember { mutableStateOf(0L) }
    // Bumped on EVERY step so the commit debounce restarts even when the
    // preview VALUE stops changing (held key clamped at the buffer tail
    // or head). Without it the value-keyed effect committed once per
    // ~750ms for the whole hold - 8 identical buffer re-opens in the
    // 2026-07-11 Streamer field log.
    var scrubStepSerial by remember { mutableStateOf(0) }
    // Commit through the SAME fresh-window logic the transport pills
    // use (the composed state snapshot can lag; Streamer field lesson).
    val commitScrubWall: (Long) -> Unit = { target ->
        val w = timeshiftController.activeWriter
        val head = w?.headWallMs ?: tsState.headWallMs
        val tail = w?.tailWallMs ?: tsState.tailWallMs
        if (target >= head - 5_000) {
            exoHolder.goLive()
        } else {
            exoHolder.playTimeshift(target.coerceAtLeast(tail))
        }
    }

    // Task #148 milestone B: tune the archive replay + drive the position
    // ticker. The live prime effect above is fully gated off in this mode.
    LaunchedEffect(Unit) {
        if (!isCatchupMode) return@LaunchedEffect
        exoHolder.httpHeaders = httpHeaders
        exoHolder.playCatchup(
            url = catchupUrl,
            title = catchupTitle.ifBlank { currentChannel?.name },
            subtitle = currentChannel?.name,
        )
        exoHolder.currentChannelId = null
        while (true) {
            catchupPositionMs = (catchupOffsetMs + (exoHolder.player?.contentPosition ?: 0L))
                .coerceIn(0L, catchupDurationMs)
            tsPaused = exoHolder.isPaused()
            delay(500)
        }
    }
    // Task #149: revoke the native session when the replay screen leaves
    // composition (Back, X, nav-away). Re-tunes revoke their outgoing
    // session inline in commitScrubCatchup.
    val catchupUrlForRevoke by rememberUpdatedState(catchupCurrentUrl)
    DisposableEffect(Unit) {
        onDispose {
            if (isNativeCatchup) onRevokeCatchup(catchupUrlForRevoke)
        }
    }
    // Re-tune helper: swap the window URL on the same held player.
    val retuneCatchup: (String, Long) -> Unit = { newUrl, offsetMs ->
        catchupOffsetMs = offsetMs
        catchupPositionMs = offsetMs
        catchupCurrentUrl = newUrl
        exoHolder.playCatchup(
            url = newUrl,
            title = catchupTitle.ifBlank { currentChannel?.name },
            subtitle = currentChannel?.name,
        )
    }
    // Commit a catch-up scrub: native sessions mint at the EXACT second
    // (serialized; rapid skips coalesce to the latest target - see
    // VODPlayerScreen's twin); XC rebuilds the wall-clock URL at the floored
    // minute (the URL format has nothing finer).
    val commitScrubCatchup: (Long) -> Unit = { target ->
        val clamped = target.coerceIn(0L, (catchupDurationMs - 5_000L).coerceAtLeast(0L))
        if (isNativeCatchup) {
            if (nativeRemintInFlight) {
                nativeRemintPendingMs = clamped
                catchupPositionMs = clamped
            } else scope.launch {
                nativeRemintInFlight = true
                var t = clamped
                while (true) {
                    val outgoing = catchupCurrentUrl
                    val minted = onRemintCatchup(catchupChannelUuid, outgoing, catchupStartMillis + t)
                    val pending = nativeRemintPendingMs
                    if (pending != null) {
                        nativeRemintPendingMs = null
                        if (minted != null) onRevokeCatchup(minted)
                        t = pending
                        continue
                    }
                    if (minted == null) {
                        Log.w(TAG, "[CATCHUP] native re-mint failed; keeping current window")
                        break
                    }
                    onRevokeCatchup(outgoing)
                    retuneCatchup(minted, t)
                    Log.i(TAG, "[CATCHUP] native re-tune to ${t / 1000}s")
                    break
                }
                nativeRemintInFlight = false
            }
        } else {
            val absFlooredStart = ((catchupStartMillis + clamped) / 60_000L) * 60_000L
            val windowOffset = (absFlooredStart - catchupStartMillis).coerceAtLeast(0L)
            val newUrl = com.aeriotv.android.core.playback.CatchupUrlBuilder.rebuildForOffset(
                url = catchupUrl,
                panelTimeZoneId = catchupTz.ifBlank { "UTC" },
                programmeStartMillis = catchupStartMillis,
                programmeEndMillis = catchupEndMillis,
                offsetMillis = windowOffset,
            )
            if (newUrl == null) {
                Log.w(TAG, "[CATCHUP] re-tune URL rebuild failed; ignoring seek")
            } else {
                retuneCatchup(newUrl, windowOffset)
                Log.i(TAG, "[CATCHUP] re-tune to window ${windowOffset / 1000}s")
            }
        }
    }
    val scrubStep: (Int, Boolean) -> Unit = step@{ dir, isRepeat ->
        if (!tsState.buffering && !isCatchupMode) return@step
        val now = android.os.SystemClock.uptimeMillis()
        // Native autorepeat arrives ~every 50ms on some remotes; 250ms
        // throttle keeps held-scrub speed device-independent (VOD
        // scrubStep parity).
        if (isRepeat && now - scrubLastStepAt < 250L) return@step
        if (dir == scrubLastDirection && now - scrubLastStepAt < 1_000L) {
            scrubAccelCount += 1
        } else {
            scrubAccelCount = 0
        }
        scrubLastDirection = dir
        scrubLastStepAt = now
        val mult = minOf(12, 1 + scrubAccelCount / 2)
        if (isCatchupMode) {
            // Catch-up domain: PROGRAMME-relative ms in [0, duration].
            val base = scrubTargetWallMs ?: catchupPositionMs
            scrubTargetWallMs = (base + dir * 10_000L * mult).coerceIn(0L, catchupDurationMs)
        } else {
            val w = timeshiftController.activeWriter
            val head = w?.headWallMs ?: tsState.headWallMs
            val tail = w?.tailWallMs ?: tsState.tailWallMs
            val base = scrubTargetWallMs
                ?: if (tsState.timeshifting) tsPositionWallMs else head
            scrubTargetWallMs = (base + dir * 10_000L * mult).coerceIn(tail, head)
        }
        scrubStepSerial += 1
        scrubHudVisible = true
        lastInteractionAt = android.os.SystemClock.uptimeMillis()
    }
    // Deferred single commit; the null branch runs after a commit (or
    // cancel) and lets the HUD linger briefly so the user sees where
    // playback landed. Keyed on the step serial too so a clamped-value
    // hold keeps deferring instead of committing mid-hold.
    LaunchedEffect(scrubTargetWallMs, scrubStepSerial) {
        val target = scrubTargetWallMs
        if (target == null) {
            delay(1_500)
            scrubHudVisible = false
        } else {
            delay(650)
            if (isCatchupMode) commitScrubCatchup(target) else commitScrubWall(target)
            scrubTargetWallMs = null
        }
    }

    // The video PlayerView is mounted at MainActivity root via
    // PersistentExoWindow (state-driven Fullscreen / Mini / Hidden).
    // Our chrome (controls, tap-target, dim, sheets) renders ABOVE
    // it automatically because the surface uses Android's default
    // SurfaceView z-order (window UI layer above the dedicated
    // surface). Box below is the chrome canvas -- transparent
    // background so the video shows through.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Phase 172: bump lastInteractionAt on every key event while
            // chrome is visible so the auto-hide timer keeps re-arming.
            // onPreviewKeyEvent returns false -> doesn't consume; the
            // event still reaches the chrome buttons below.
            .onPreviewKeyEvent { event ->
                if (chromeVisible) {
                    lastInteractionAt = android.os.SystemClock.uptimeMillis()
                }
                // Chrome hidden + rewind buffering: LEFT/RIGHT scrubs the
                // timeline directly (band-only HUD renders; the single
                // seek commits after the presses stop). Consume both
                // actions so the release can't click anything behind.
                val native = event.nativeKeyEvent
                if (isTvForm && !chromeVisible && (tsState.buffering || isCatchupMode) &&
                    (
                        native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        )
                ) {
                    if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                        val dir = if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1
                        scrubStep(dir, native.repeatCount > 0)
                    }
                    return@onPreviewKeyEvent true
                }
                // TV D-pad UP/DOWN channel-flip is handled by MainActivity
                // .dispatchKeyEvent via exoWindowState.onLiveChannelFlip (see the
                // DisposableEffect below). Routing it at the activity level makes
                // it win over the chrome pill row + Options DropdownMenu popup,
                // which used to swallow UP/DOWN when the controls were visible.
                // Non-flip keys (LEFT / RIGHT / OK / Back) still fall through here
                // to operate the visible controls: return false, don't consume.
                false
            },
    ) {

        // Transparent tap-target above the video to toggle chrome. Vertical drag
        // on the same layer (while chrome is visible) flips to next/prev channel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(playbackFocus)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { chromeVisible = !chromeVisible }
                .pointerInput(channels.size, chromeVisible, appleTVChannelFlip) {
                    if (!chromeVisible || !appleTVChannelFlip || channels.size < 2) return@pointerInput
                    var totalDy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDy = 0f },
                        onDragEnd = {
                            val abs = abs(totalDy)
                            if (abs > SWIPE_THRESHOLD_PX && currentIndex >= 0 && channels.isNotEmpty()) {
                                val direction = if (totalDy < 0f) +1 else -1
                                val next = (currentIndex + direction)
                                    .coerceIn(0, channels.lastIndex)
                                if (next != currentIndex) {
                                    currentIndex = next
                                    chromeVisible = true
                                }
                            }
                            totalDy = 0f
                        },
                        onVerticalDrag = { _, dy -> totalDy += dy },
                    )
                },
        )

        // Dead-upstream net: the holder's no-data watchdog reconnected once and
        // still got zero bytes, so it flagged the channel unavailable + stopped.
        // Task #150 (iOS parity): show WHAT failed, keep auto-retrying on an
        // escalating 5s->30s delay, and offer a manual Retry button. D-pad
        // up/down still bubbles to the root key handler so channel flips keep
        // working (a flip clears the flag and resets the escalation).
        if (streamUnavailable && isCatchupMode) {
            // Task #148 milestone B: a catch-up 4xx means the provider has no
            // archive for this window (flag-but-no-archive class). Retrying
            // can't conjure one, so no auto-reconnect - show why + Go Back.
            val lastErrorText by exoHolder.lastErrorText.collectAsStateWithLifecycle()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Catch-up Unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                // A 4xx really means "no archive"; anything else (decoder,
                // network) gets neutral copy so we don't blame the provider
                // for a local failure.
                val noArchive = lastErrorText.orEmpty().let {
                    it.contains("404") || it.contains("Not Found", ignoreCase = true) ||
                        it.contains("BAD_HTTP_STATUS")
                }
                Text(
                    text = if (noArchive) {
                        "Your provider doesn't have an archive for this programme."
                    } else {
                        "Playback of this programme's archive failed."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
                if (!lastErrorText.isNullOrBlank()) {
                    Text(
                        text = lastErrorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
                Button(onClick = {
                    exoHolder.stop()
                    onClose()
                }) {
                    Text("Go Back")
                }
            }
        } else if (streamUnavailable) {
            val lastErrorText by exoHolder.lastErrorText.collectAsStateWithLifecycle()
            var retryCountdown by remember { mutableIntStateOf(0) }
            var reconnecting by remember { mutableStateOf(false) }
            LaunchedEffect(unavailableRetrySerial) {
                reconnecting = false
                // Flat 5s between every auto-retry (Archie, 2026-07-12).
                var remaining = 5
                while (remaining > 0) {
                    retryCountdown = remaining
                    kotlinx.coroutines.delay(1_000)
                    remaining -= 1
                }
                retryCountdown = 0
                reconnecting = true
                unavailableRetrySerial += 1
                exoHolder.retryUnavailable()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Channel Unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                if (!lastErrorText.isNullOrBlank()) {
                    Text(
                        text = lastErrorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = when {
                        reconnecting -> "Reconnecting…"
                        retryCountdown > 0 -> "Retrying in ${retryCountdown}s"
                        else -> " "
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
                // Phone/tablet: tappable Retry on the card itself. On TV the
                // Retry lives in the standard controls (focusable via the
                // remote) - the card stays informational there.
                if (!isTvForm) {
                    Button(onClick = {
                        reconnecting = true
                        unavailableRetrySerial += 1
                        exoHolder.retryUnavailable()
                    }) {
                        Text("Retry Now")
                    }
                }
                Text(
                    text = if (isTvForm) {
                        "Retry is highlighted below - press Select. Back to exit, or D-pad up/down to change channels."
                    } else {
                        "Press Back to exit, or use the D-pad up/down to change channels."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Live Rewind: the ticker that keeps the buffer window and
        // playback wall-position fresh while a session is rolling. Wall
        // position = the wall time playback entered the buffer + the
        // player's position within that open (each re-open resets
        // contentPosition to 0, so the sum stays correct across scrub
        // re-opens). State declarations moved above the chrome-canvas
        // Box so the root key handler can drive the D-pad scrub.
        val tickerLifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(tsState.buffering) {
            if (!tsState.buffering) return@LaunchedEffect
            // STARTED-gated: without this the 500ms wakeup ran for the
            // whole time the app sat backgrounded behind PiP.
            tickerLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (tsState.buffering) {
                timeshiftController.refreshWindow()
                tsPaused = exoHolder.isPaused()
                if (tsState.timeshifting) {
                    val pos = exoHolder.player?.currentPosition ?: 0L
                    tsPositionWallMs = tsState.baseWallMs + pos
                    // Cable-DVR catch-up snap: riding the write head keeps
                    // ExoPlayer in perpetual BUFFERING (it can never build
                    // its minimum buffer against a source that grows in
                    // real time). When playback closes to within a few
                    // seconds of the head, return to the direct stream.
                    // READY gate: a freshly-prepared buffer source reports
                    // meaningless positions while BUFFERING, which made
                    // the snap bounce straight back to live on entry
                    // (Streamer field test).
                    if (!exoHolder.isPaused() &&
                        exoHolder.player?.playbackState == androidx.media3.common.Player.STATE_READY &&
                        tsPositionWallMs >= tsState.headWallMs - 4_000
                    ) {
                        exoHolder.goLive()
                    }
                }
                kotlinx.coroutines.delay(500)
            }
            }
        }

        PlayerChromeOverlay(
            channel = currentChannel,
            nowProgramme = nowProgramme,
            timeshiftState = if (tsState.buffering) tsState else null,
            timeshiftPositionWallMs = tsPositionWallMs,
            // Live TV with pause/rewind OFF: the transport comes from Live Rewind,
            // so hint that it's a setting rather than silently showing no controls.
            // Only when the channel COULD buffer (raw TS) -- not HLS/DASH live.
            showLiveRewindHint = !isCatchupMode && !liveRewindEnabled &&
                currentChannel?.url?.takeIf { it.isNotBlank() }
                    ?.let { exoHolder.canBufferLiveRewind(it) } == true,
            // Task #148 milestone B: catch-up transport context.
            catchupMode = isCatchupMode,
            catchupTitle = catchupTitle,
            catchupPositionMs = catchupPositionMs,
            catchupDurationMs = catchupDurationMs,
            onCatchupSeekTo = { target -> commitScrubCatchup(target) },
            isPlayerPaused = tsPaused,
            onRewindTogglePause = {
                when {
                    exoHolder.isTimeshifting -> {
                        exoHolder.setPaused(!exoHolder.isPaused())
                    }
                    tsState.buffering && !exoHolder.isPaused() -> {
                        // Cable-seamless pause: nothing switches, the frame
                        // just freezes. The controller quietly brings up the
                        // independent filler so the buffer keeps growing
                        // underneath a long pause.
                        livePauseWallMs = System.currentTimeMillis()
                        exoHolder.setPaused(true)
                        timeshiftController.onLivePaused()
                    }
                    tsState.buffering && exoHolder.isPaused() && livePauseWallMs > 0 -> {
                        val pausedForMs = System.currentTimeMillis() - livePauseWallMs
                        if (pausedForMs <= 6_000) {
                            // Short pause: resume the untouched live
                            // pipeline. Zero switch, zero glitch.
                            exoHolder.setPaused(false)
                            timeshiftController.onLiveResumedAtEdge()
                        } else {
                            // Long pause: one switch onto the buffer at the
                            // pause point; the filler covered the gap.
                            exoHolder.setPaused(false)
                            exoHolder.playTimeshift(livePauseWallMs - 1_000)
                        }
                        livePauseWallMs = 0L
                    }
                    else -> exoHolder.setPaused(!exoHolder.isPaused())
                }
            },
            onRewindSeekWall = { target ->
                // Read the buffer window FRESH from the writer at action
                // time: the composed state snapshot can lag (the Streamer
                // test turned a -30s skip into -122s off a stale head).
                val w = timeshiftController.activeWriter
                val head = w?.headWallMs ?: tsState.headWallMs
                val tail = w?.tailWallMs ?: tsState.tailWallMs
                if (target >= head - 5_000) {
                    exoHolder.goLive()
                } else {
                    exoHolder.playTimeshift(target.coerceAtLeast(tail))
                }
            },
            onGoLive = { exoHolder.goLive() },
            scrubPreviewWallMs = scrubTargetWallMs,
            scrubHudVisible = scrubHudVisible,
            onScrubStep = scrubStep,
            onScrubCommit = {
                // OK on the focused timeline: commit the pending scrub
                // immediately instead of waiting out the debounce.
                scrubTargetWallMs?.let { target ->
                    if (isCatchupMode) commitScrubCatchup(target) else commitScrubWall(target)
                    scrubTargetWallMs = null
                }
            },
            chromeVisible = chromeVisible,
            pillVisible = pillVisible,
            isTv = isTvForm,
            // Cast Connect (GH #33): phone/tablet Cast button, only on a build
            // with a registered Cast App ID. Live channels cast their identity to
            // the Android-TV receiver.
            castSlot = if (!isTvForm && castSender.castConfigured) {
                {
                    com.aeriotv.android.feature.cast.CastIconButton(
                        sender = castSender,
                        companionRemote = companionRemote,
                        companionDiscovery = companionDiscovery,
                        onChooserOpenChange = { castChooserOpen = it },
                    )
                }
            } else {
                null
            },
            // Focusable Retry in the standard controls while the stream is
            // unavailable (the center card's button can't take remote focus).
            connectionIssue = streamUnavailable,
            onRetry = {
                unavailableRetrySerial += 1
                exoHolder.retryUnavailable()
            },
            // #10 player gesture hints: only advertise Up/Down channel-flip when
            // it can actually do something (setting on + more than one channel).
            showChannelFlipHint = appleTVChannelFlip && channels.size >= 2,
            // Explicit X tap = user is done with this channel; clear the mini-player
            // session, destroy the held MPV instance, and stop the background
            // PlaybackService so the notification disappears. System back keeps
            // the session + service alive instead (handled by BackHandler above).
            // Phase 172: stop() rather than destroy() so the persistent
            // SurfaceView mounted at MainActivity root stays alive --
            // the next channel tap plays instantly on the existing
            // handle. destroy() set holder.view=null and the next
            // LaunchedEffect(currentChannel.id) couldn't find a view to
            // playFile on, leaving subsequent channels permanently
            // stuck.
            onClose = {
                miniPlayerVm.dismiss()
                exoWindowState.hide()
                exoHolder.stop()
                com.aeriotv.android.core.playback.AerioMediaPlaybackService
                    .stop(context)
                onClose()
            },
            onAddToMultiview = { multiviewPickerOpen = true },
            onShowRecord = { target -> recordTarget = target },
            onShowStreamInfo = {
                streamInfo = exoHolder.player?.captureStreamInfo() ?: StreamInfoSnapshot(
                    videoLines = listOf("(player not ready)"),
                    audioLines = emptyList(),
                    cacheLines = emptyList(),
                    syncLines = emptyList(),
                )
            },
            onShowSwitchStream = {
                val ch = currentChannel ?: return@PlayerChromeOverlay
                val chPk = ch.dispatcharrChannelId ?: return@PlayerChromeOverlay
                val uuid = ch.id.removePrefix("disp:")
                scope.launch {
                    val streams = onLoadChannelStreams(chPk)
                    // Prefer the in-session selection for the radio mark: after an
                    // event-apply switch the server leaves /proxy/ts/status's stream_id
                    // stale (it only refreshes url), so switchedStreamId is the truthful
                    // "what we last switched to". Fall back to status stream_id when we
                    // haven't switched anything this session (correct on first read).
                    val current = onLoadCurrentStreamId(uuid)
                    switchStream = SwitchStreamState(
                        streams = streams,
                        currentStreamId = switchedStreamId ?: current,
                    )
                }
            },
            onShowSubtitles = {
                val player = exoHolder.player ?: return@PlayerChromeOverlay
                subtitles = SubtitlesState(
                    tracks = player.readSubtitleTracks(),
                    currentSid = player.readCurrentSid(),
                )
            },
            onShowAudioTracks = {
                val player = exoHolder.player ?: return@PlayerChromeOverlay
                audioTracks = AudioTracksState(
                    tracks = player.readAudioTracks(),
                    currentAid = player.readCurrentAid(),
                )
            },
            onShowPlaybackSpeed = {
                val player = exoHolder.player ?: return@PlayerChromeOverlay
                playbackSpeedSheet = player.readSpeed()
            },
            aspectModeLabel = when (aspectMode) {
                "zoom" -> "Zoom"
                "fill" -> "Fill"
                else -> "Fit"
            },
            onCycleAspect = { settingsVm.cyclePlayerAspectMode(aspectMode) },
            onToggleAudioOnly = {
                audioOnly = !audioOnly
                val player = exoHolder.player
                if (audioOnly) {
                    // Disable the video track on the current selection.
                    // The audio renderer keeps running -- this is the
                    // Media3 equivalent of libmpv `vid=no` without the
                    // need to reload the stream when toggling back.
                    player?.trackSelectionParameters = player?.trackSelectionParameters
                        ?.buildUpon()
                        ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                        ?.build() ?: return@PlayerChromeOverlay
                } else {
                    player?.trackSelectionParameters = player?.trackSelectionParameters
                        ?.buildUpon()
                        ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
                        ?.build() ?: return@PlayerChromeOverlay
                }
            },
            audioOnly = audioOnly,
            onSetSleepMinutes = { minutes ->
                sleepEndsAt = if (minutes == 0) null else System.currentTimeMillis() + minutes * 60_000L
            },
            sleepRemainingMillis = sleepRemainingMillis,
            onInteractingChange = { chromeMenuOpen = it },
        )

        // GH #33 full-parity cast remote: while a Cast Connect session is live the
        // local codec is stopped, so replace the (black) player with the phone
        // remote -- transport, channel up/down, stop, and the same audio/subtitle/
        // speed/aspect controls, all driving the Android-TV receiver over the
        // custom control channel. Drawn last = on top of the (now-idle) chrome.
        if (isRemote) {
            // One overlay, two transports (GH #33): the SAME full remote drives a
            // Cast Connect session or a LAN companion-paired AerioTV TV; only the
            // command sink + state source switch.
            val remoteState by (if (isCompanion) companionRemote.remoteState else castSender.remoteState)
                .collectAsStateWithLifecycle()
            val remoteIsPlaying by (if (isCompanion) companionRemote.isPlaying else castSender.isPlaying)
                .collectAsStateWithLifecycle()
            val companionPosition by companionRemote.position.collectAsStateWithLifecycle()
            com.aeriotv.android.feature.cast.CastRemoteOverlay(
                deviceName = if (isCompanion) {
                    (companionConn as? com.aeriotv.android.core.cast.companion.CompanionRemoteController.Conn.Connected)?.name
                } else {
                    (castState as? com.aeriotv.android.core.cast.AerioCastSender.State.Connected)?.deviceName
                },
                channelTitle = currentChannel?.name.orEmpty(),
                programmeTitle = nowProgramme?.title,
                remoteState = remoteState,
                isPlaying = remoteIsPlaying,
                statusVerb = if (isCompanion) "Controlling" else "Casting to",
                stopLabel = if (isCompanion) "Disconnect" else "Stop casting",
                onTogglePlayPause = {
                    if (isCompanion) companionRemote.togglePlayPause() else castSender.togglePlayPause()
                },
                onChannelUp = {
                    if (channels.isNotEmpty() && currentIndex >= 0) {
                        currentIndex = (currentIndex + 1).coerceIn(0, channels.lastIndex)
                    }
                },
                onChannelDown = {
                    if (channels.isNotEmpty() && currentIndex >= 0) {
                        currentIndex = (currentIndex - 1).coerceIn(0, channels.lastIndex)
                    }
                },
                onStopCasting = {
                    if (isCompanion) {
                        // Companion Disconnect: stop controlling the TV and LEAVE
                        // the player. Do NOT resume local playback (remoteStopping
                        // gates the prime effect) -- resuming here left the channel
                        // playing on BOTH the phone and the TV (device report). The
                        // TV keeps playing (it's the user's own device); the
                        // scaffold's card is gone once disconnected.
                        remoteStopping = true
                        companionRemote.disconnect()
                        onClose()
                    } else {
                        // Cast: end the session; local playback resumes via the
                        // isRemote effects (standard "bring it back to my phone").
                        castSender.stopCasting()
                    }
                },
                onSetAudioTrack = { id ->
                    if (isCompanion) companionRemote.setRemoteAudioTrack(id) else castSender.setRemoteAudioTrack(id)
                },
                onSetTextTrack = { id ->
                    if (isCompanion) companionRemote.setRemoteTextTrack(id) else castSender.setRemoteTextTrack(id)
                },
                onSetSpeed = { s ->
                    if (isCompanion) companionRemote.setRemoteSpeed(s) else castSender.setRemoteSpeed(s)
                },
                onSetAspect = { mode ->
                    if (isCompanion) companionRemote.setRemoteAspect(mode) else castSender.setRemoteAspect(mode)
                },
                onSetAudioOnly = { on ->
                    if (isCompanion) companionRemote.setRemoteAudioOnly(on) else castSender.setRemoteAudioOnly(on)
                },
                onSwitchStream = {
                    // Reuse the live Switch Stream flow: it POSTs change_stream
                    // server-side (works while casting); the sheet's onSelect also
                    // re-tunes the receiver when casting (see below).
                    val ch = currentChannel
                    val chPk = ch?.dispatcharrChannelId
                    if (ch != null && chPk != null) {
                        val uuid = ch.id.removePrefix("disp:")
                        scope.launch {
                            val streams = onLoadChannelStreams(chPk)
                            val current = onLoadCurrentStreamId(uuid)
                            switchStream = SwitchStreamState(
                                streams = streams,
                                currentStreamId = switchedStreamId ?: current,
                            )
                        }
                    }
                },
                onRecord = {
                    // Server-side scheduling: works whether playing locally or cast.
                    // A default 1-hour live window (the sheet lets the user adjust);
                    // the current programme title is used when known.
                    currentChannel?.let { ch ->
                        val now = System.currentTimeMillis()
                        recordTarget = ProgramInfoTarget(
                            channelName = ch.name,
                            title = nowProgramme?.title?.takeIf { it.isNotBlank() }
                                ?: "${ch.name} live recording",
                            startMillis = now,
                            endMillis = now + 3_600_000L,
                            description = "",
                            category = "",
                            channelDispatcharrId = ch.dispatcharrChannelId,
                        )
                    }
                },
                onSleepMinutes = { minutes ->
                    sleepEndsAt = if (minutes == 0) null else System.currentTimeMillis() + minutes * 60_000L
                },
                onSeekBy = { delta ->
                    if (isCompanion) companionRemote.seekBy(delta) else castSender.seekBy(delta)
                },
                onSeekToWall = { target ->
                    if (isCompanion) companionRemote.seekToWall(target) else castSender.seekToWall(target)
                },
                onGoLive = {
                    if (isCompanion) companionRemote.goLiveRemote() else castSender.goLiveRemote()
                },
                // GH #33: minimize returns to the tabs (the session stays alive;
                // the Now-Casting / Controlling mini controller is the re-entry).
                onMinimize = { onClose() },
                position = if (isCompanion) companionPosition else castPosition,
                canSwitchStream = isDispatcharrLive,
                canRecord = currentChannel?.dispatcharrChannelId != null,
                onRefreshState = {
                    if (isCompanion) companionRemote.requestRemoteState() else castSender.requestRemoteState()
                },
            )
        }
    }

    // Auto-hide chrome after AUTO_HIDE_MS of inactivity. Phase 172:
    // re-arms whenever the user interacts (lastInteractionAt advances),
    // so D-pad navigation through the chrome buttons resets the timer
    // instead of letting it fire mid-traversal. The key is
    // lastInteractionAt -- changing that restarts the LaunchedEffect.
    // While a menu or sheet is open, pause the auto-hide so the chrome (and the
    // open panel) stay put while the user interacts. tvOS keeps the Options
    // panel up until it is dismissed.
    val interactionLocked = chromeMenuOpen || recordTarget != null || streamInfo != null ||
        subtitles != null || audioTracks != null || playbackSpeedSheet != null ||
        switchStream != null || multiviewPickerOpen || castChooserOpen
    // streamUnavailable is a KEY (not just a guard): when it clears on
    // recovery, this effect must re-fire so the chrome that was pinned open
    // during the outage auto-hides again. Without it in the keys, the
    // controls stayed stuck up after the stream came back (Streamer test
    // 2026-07-12).
    LaunchedEffect(chromeVisible, lastInteractionAt, interactionLocked, streamUnavailable) {
        // Never auto-hide while the stream is unavailable: the chrome hosts the
        // Retry control the user needs, so it must stay put during an outage.
        if (chromeVisible && !interactionLocked && !streamUnavailable) {
            delay(AUTO_HIDE_MS)
            chromeVisible = false
        }
    }
    // Auto-summon the controls on TV when the stream drops so the focusable
    // Retry pill is immediately reachable (and re-summon on each retry cycle).
    LaunchedEffect(streamUnavailable, unavailableRetrySerial) {
        if (streamUnavailable && isTvForm) chromeVisible = true
    }


    // TV live channel surf via the hardware-key path. MainActivity.dispatchKeyEvent
    // invokes exoWindowState.onLiveChannelFlip on D-pad UP/DOWN while THIS player
    // is the frontmost Fullscreen window, so channel flip works even with the
    // controls overlay visible (Compose focus traversal otherwise consumed UP/DOWN
    // among the chrome pills / Options popup). currentIndex stays the single source
    // of truth here. rememberUpdatedState so the long-lived lambda always sees
    // fresh state without re-registering. Debounced so a held key surfs one channel
    // per ~120ms instead of skipping wildly. Declines (returns false) while a
    // menu/sheet is open (interactionLocked) so UP/DOWN navigate those instead.
    // Task #148 milestone B: no channel flips during a catch-up replay
    // (tvOS parity: the catch-up tile gates channel-flip off entirely).
    val flipEnabled by rememberUpdatedState(
        isTvForm && appleTVChannelFlip && channels.size >= 2 && !isCatchupMode,
    )
    val flipLocked by rememberUpdatedState(interactionLocked)
    val flipIndex by rememberUpdatedState(currentIndex)
    val flipChannels by rememberUpdatedState(channels)
    // Task #148 (tvOS parity): UP/DOWN zap only rides fullscreen video
    // (or the flip's own top-card window). With the bottom chrome
    // summoned, declining hands the press back to Compose so UP reaches
    // the focusable timeline and DOWN walks the pills; mid-scrub the
    // HUD reads as player controls, so zapping would yank the channel
    // out from under the user.
    val flipBlockedByChrome by rememberUpdatedState(
        chromeVisible || scrubHudVisible || scrubTargetWallMs != null,
    )
    var lastFlipAt by remember { mutableStateOf(0L) }
    DisposableEffect(exoWindowState) {
        exoWindowState.onLiveChannelFlip = flip@{ delta ->
            if (!flipEnabled || flipLocked) return@flip false
            if (flipBlockedByChrome) return@flip false
            val list = flipChannels
            val cur = flipIndex
            if (cur < 0 || list.isEmpty()) return@flip false
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFlipAt < FLIP_DEBOUNCE_MS) return@flip true // eat repeats, stay responsive
            val next = (cur + delta).coerceIn(0, list.lastIndex)
            if (next != cur) {
                lastFlipAt = now
                currentIndex = next
                // NO chromeVisible = true here (task #148, user
                // directive): a flip surfaces only the top program card
                // (the launch-hint window re-arms on the channel-id
                // change), never the bottom player controls - and the
                // hidden chrome is what keeps follow-up UP/DOWN presses
                // flipping.
            }
            true
        }
        onDispose { exoWindowState.onLiveChannelFlip = null }
    }

    // On TV, when the chrome hides (fullscreen video), pull D-pad focus to the
    // playback surface so the remote's up/down reaches the channel-flip handler
    // instead of being swallowed by a stale focus target.
    LaunchedEffect(chromeVisible, isTvForm) {
        if (isTvForm && !chromeVisible) {
            delay(100)
            runCatching { playbackFocus.requestFocus() }
        }
    }

    // GH #33: read cast state LIVE at expiry. The effect is keyed only on
    // sleepEndsAt, so a timer armed before the cast state changes (start/stop
    // casting after arming) would otherwise branch on the stale captured value
    // -- closing the player mid-cast, or no-op'ing stopCasting on a dead session
    // and never closing the resumed local player.
    val isCastingAtExpiry by rememberUpdatedState(isCasting)
    val isCompanionAtExpiry by rememberUpdatedState(isCompanion)
    LaunchedEffect(sleepEndsAt) {
        val target = sleepEndsAt
        if (target == null) {
            sleepRemainingMillis = null
            return@LaunchedEffect
        }
        while (true) {
            val remaining = target - System.currentTimeMillis()
            if (remaining <= 0L) {
                sleepRemainingMillis = null
                sleepEndsAt = null
                // GH #33: a sleep timer set from the cast remote ends the CAST
                // (the local player is already suspended); from the companion
                // remote it pauses the TV (the TV keeps running -- it's the
                // user's own device, unlike a cast session); otherwise close.
                when {
                    isCastingAtExpiry -> castSender.stopCasting()
                    isCompanionAtExpiry -> { companionRemote.pause(); onClose() }
                    else -> onClose()
                }
                break
            }
            sleepRemainingMillis = remaining
            delay(1_000L)
        }
    }

    recordTarget?.let { target ->
        RecordProgramSheet(
            target = target,
            onDismiss = { recordTarget = null },
        )
    }
    if (multiviewPickerOpen) {
        val multiviewStore = com.aeriotv.android.feature.multiview.rememberMultiviewStoreHandle()
        // Snapshot the pre-open staged set so Cancel restores it EXACTLY
        // (protects any Guide-staged selection from being wiped). Captured at
        // composition time, i.e. BEFORE the seed LaunchedEffect below runs.
        val mvSnapshot = remember { multiviewStore.selected.value }
        val mvSnapshotFocus = remember { multiviewStore.audioFocusedIndex.value }
        // Seed the now-playing channel as Tile 1 (index 0 = audio focus),
        // preserving any already-staged tiles behind it. Keyed on Unit so it
        // runs once per open, never re-clobbering the user's picks.
        LaunchedEffect(Unit) { currentChannel?.let { multiviewStore.seedCurrent(it) } }
        AddToMultiviewSheet(
            currentChannel = currentChannel,
            multiviewStore = multiviewStore,
            onLaunch = {
                multiviewPickerOpen = false
                // Stop the single-stream player BEFORE navigating so only the
                // multiview tiles produce audio (no double-audio). Same teardown
                // as the proven X-close path; onLaunchMultiview() is LAST because
                // it pops PLAYER (this very composable) off the back stack.
                miniPlayerVm.dismiss()
                exoWindowState.hide()
                exoHolder.stop()
                com.aeriotv.android.core.playback.AerioMediaPlaybackService
                    .stop(context.applicationContext)
                onLaunchMultiview()
            },
            onCancel = {
                multiviewPickerOpen = false
                // Restore the exact pre-open selection (NOT clear()), so a
                // Guide-staged set survives an opened-then-cancelled picker.
                multiviewStore.restore(mvSnapshot, mvSnapshotFocus)
            },
            // BACK / scrim / swipe KEEPS whatever the user just toggled (mirrors
            // MultiviewScreen's re-entrant picker). Only the explicit "Cancel"
            // text button (onCancel) reverts to the pre-open snapshot. Without
            // this, onDismiss defaulted to onCancel and BACK silently discarded
            // the picks the user added while watching.
            onDismiss = { multiviewPickerOpen = false },
        )
    }
    streamInfo?.let { snapshot ->
        StreamInfoSheet(
            snapshot = snapshot,
            onDismiss = { streamInfo = null },
        )
    }
    subtitles?.let { state ->
        SubtitlesSheet(
            tracks = state.tracks,
            currentTrackId = state.currentSid,
            onSelect = { sid ->
                exoHolder.player?.selectSubtitleTrack(sid)
                subtitles = null
            },
            onDismiss = { subtitles = null },
        )
    }
    audioTracks?.let { state ->
        AudioTracksSheet(
            tracks = state.tracks,
            currentTrackId = state.currentAid,
            onSelect = { aid ->
                exoHolder.player?.selectAudioTrack(aid)
                audioTracks = null
            },
            onDismiss = { audioTracks = null },
        )
    }
    switchStream?.let { state ->
        SwitchStreamSheet(
            streams = state.streams,
            currentStreamId = state.currentStreamId,
            onSelect = { id ->
                val ch = currentChannel
                switchStream = null
                if (ch != null) {
                    switchedStreamId = id
                    val uuid = ch.id.removePrefix("disp:")
                    val proxyUrl = ch.url
                    scope.launch {
                        // --- Why this dance (all verified against Dispatcharr source) ---
                        // change_stream applies the switch to the LIVE session, usually
                        // ASYNCHRONOUSLY (owner:false -> Redis event, applied by the owner
                        // worker). The owner swaps the upstream IN PLACE on the running
                        // stream_manager -- a mid-stream TS discontinuity, no EOF -- and the
                        // deep live buffer (ca07882) absorbs the splice so ExoPlayer's
                        // ProgressiveMediaSource never flushes and won't follow it. To make
                        // it follow we must re-prepare (flush) the same proxy URL. BUT a
                        // bare re-prepare drops our only TCP connection, and with the
                        // server default channel_shutdown_delay=0 that fires stop_channel,
                        // which DELETES channel_stream:{id} and makes the reconnect cold-
                        // resolve to the channel's DEFAULT (first-ordered) stream -- worse
                        // than doing nothing. So:
                        //   1. Confirm the switch actually landed: poll /status until
                        //      status.url == the change_stream url. We gate on URL, never
                        //      stream_id (the event-apply path refreshes metadata.url but
                        //      leaves stream_id stale 20+s, so stream_id false-negatives).
                        //   2. Hold a SECOND AllowAny GET to the same /proxy/ts/stream URL
                        //      open across the re-prime so the channel never drops to 0
                        //      clients -> stream_manager survives -> the reconnect re-attaches
                        //      to the already-switched session instead of cold-resolving.
                        //      Best-effort: if the keepalive can't connect we re-prime anyway.
                        val newUrl = runCatching { onSwitchChannelStream(uuid, id) }.getOrNull()
                        if (newUrl.isNullOrBlank()) {
                            Toast.makeText(context, "Stream switch failed", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        // confirm: poll status.url == target within budgetMs (strict equality)
                        suspend fun confirm(target: String, budgetMs: Long): Boolean {
                            val end = android.os.SystemClock.elapsedRealtime() + budgetMs
                            while (android.os.SystemClock.elapsedRealtime() < end) {
                                if (currentChannel?.id != ch.id || switchedStreamId != id) return false
                                if (onLoadCurrentStreamUrl(uuid) == target) return true
                                delay(150)
                            }
                            return false
                        }
                        var targetUrl = newUrl
                        var confirmed = confirm(newUrl, 6_000L)
                        if (!confirmed) {
                            // Safe retry: re-issue once (may now hit the owner:true direct
                            // path), brief re-confirm. Never re-prime blind on timeout.
                            val u2 = runCatching { onSwitchChannelStream(uuid, id) }.getOrNull()
                            if (!u2.isNullOrBlank()) { targetUrl = u2; confirmed = confirm(u2, 3_000L) }
                        }
                        if (currentChannel?.id != ch.id || switchedStreamId != id) return@launch
                        if (!confirmed) {
                            Toast.makeText(
                                context,
                                "Stream switch not confirmed; staying on current feed",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }

                        Toast.makeText(context, "Switching stream...", Toast.LENGTH_SHORT).show()
                        // GH #33: while casting, the phone must NOT start a local decode.
                        // change_stream already landed server-side (confirmed above), so the
                        // channel's /proxy/ts/ URL now serves the switched upstream -- re-tune
                        // the RECEIVER to the same channel and let the TV follow. Re-priming
                        // the local player here would spin up a parallel decode (and phone-side
                        // audio) alongside the cast. The keepalive re-prime below is local-only.
                        if (isCasting) {
                            castSender.setRemoteChannel(ch.id)
                            return@launch
                        }
                        if (isCompanion) {
                            // Same reasoning for the companion path: the switch
                            // landed server-side; re-tune the TV, never the phone.
                            companionRemote.setRemoteChannel(ch.id, ch.name)
                            return@launch
                        }
                        // Re-prime onto the switched upstream with a keepalive held across the
                        // flush (see AerioExoPlayerHolder.reprimeWithKeepalive). bypassCooldown:
                        // a user-initiated switch always re-primes, even if an auto-reload or the
                        // follow-poller fired within the shared cooldown window.
                        exoHolder.reprimeWithKeepalive(
                            url = proxyUrl,
                            title = ch.name,
                            subtitle = nowProgramme?.title.orEmpty(),
                            artworkUri = ch.tvgLogo.takeIf { it.isNotBlank() }
                                ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
                            bypassCooldown = true,
                        )
                    }
                }
            },
            onDismiss = { switchStream = null },
        )
    }
    playbackSpeedSheet?.let { current ->
        PlaybackSpeedSheet(
            currentSpeed = current,
            onSelect = { speed ->
                exoHolder.player?.applySpeed(speed)
                playbackSpeedSheet = null
            },
            onDismiss = { playbackSpeedSheet = null },
        )
    }

    DisposableEffect(Unit) {
        onDispose { /* AndroidView.onRelease handles native cleanup. */ }
    }
}

private data class SubtitlesState(
    val tracks: List<SubtitleTrack>,
    val currentSid: Int?,
)

private data class AudioTracksState(
    val tracks: List<AudioTrack>,
    val currentAid: Int?,
)

private data class SwitchStreamState(
    val streams: List<StreamOption>,
    val currentStreamId: Int?,
)

/**
 * EntryPoint accessor so this Composable can grab the holder + window
 * state singletons without routing through hiltViewModel (which would
 * create them per-instance).
 *
 * The capture-stream-info / read-tracks / read-speed extension
 * functions that used to live here moved to ExoPlayerReaders.kt
 * (task #66). All chrome callbacks now read from / write to the
 * ExoPlayer directly via those extensions.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerScreenEntryPoint {
    fun exoPlayerHolder(): com.aeriotv.android.core.playback.AerioExoPlayerHolder
    fun exoWindowState(): ExoWindowState
    fun timeshiftController(): com.aeriotv.android.core.timeshift.TimeshiftController
    fun castSender(): com.aeriotv.android.core.cast.AerioCastSender
    fun castReceiver(): com.aeriotv.android.core.cast.AerioCastReceiverController
    fun companionRemote(): com.aeriotv.android.core.cast.companion.CompanionRemoteController
    fun companionDiscovery(): com.aeriotv.android.core.cast.companion.CompanionDiscovery
    fun companionHost(): com.aeriotv.android.core.cast.companion.CompanionHostController
}
