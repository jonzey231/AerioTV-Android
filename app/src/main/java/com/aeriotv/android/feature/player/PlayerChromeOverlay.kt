package com.aeriotv.android.feature.player

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.ui.LocalIsDispatcharrAdmin
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.pip.supportsPip
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.ui.theme.LocalAppTheme
import com.aeriotv.android.ui.theme.TextPrimary
import com.aeriotv.android.ui.tv.tvFocusScale
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Player chrome overlay matching iOS canon (PlaybackChromeOverlay.swift).
 *
 *   X-close              ⋯-more  +-add
 *   ┌──────────────────────────────────────┐
 *   │ # · logo · Channel name                │
 *   │           Programme title              │
 *   │           Time range · duration        │
 *   └──────────────────────────────────────┘
 *                  ━━━━━━━━━━━━━━━━━━
 *           Programme title    N min remaining
 *
 * Fades in/out on screen tap; auto-hides after 4s of no interaction. While a
 * menu or sheet is open, the auto-hide pauses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerChromeOverlay(
    channel: M3UChannel?,
    nowProgramme: EPGProgramme?,
    chromeVisible: Boolean,
    pillVisible: Boolean = chromeVisible,
    isTv: Boolean = false,
    showChannelFlipHint: Boolean = false,
    /** Cast Connect (GH #33): phone-only Cast button slot rendered in the top
     *  bar. Null on TV and on any Cast-disabled build. */
    castSlot: (@Composable () -> Unit)? = null,
    onClose: () -> Unit,
    onAddToMultiview: () -> Unit,
    onShowRecord: (ProgramInfoTarget) -> Unit,
    onShowStreamInfo: () -> Unit,
    onShowSwitchStream: () -> Unit,
    onShowSubtitles: () -> Unit,
    onShowAudioTracks: () -> Unit,
    onShowPlaybackSpeed: () -> Unit,
    aspectModeLabel: String,
    onCycleAspect: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    audioOnly: Boolean,
    onSetSleepMinutes: (Int) -> Unit,
    sleepRemainingMillis: Long?,
    onInteractingChange: (Boolean) -> Unit = {},
    // Connection-issue Retry (2026-07-12): shown in the standard controls ONLY
    // while the stream is unavailable, so the remote has a focusable Retry
    // (the center error-card button can't take focus on TV). onRetry re-tunes.
    connectionIssue: Boolean = false,
    onRetry: () -> Unit = {},
    // Live Rewind (task #143). Null state = feature off or no buffer
    // session; the band falls back to the read-only EPG progress bar.
    timeshiftState: com.aeriotv.android.core.timeshift.TimeshiftController.State? = null,
    timeshiftPositionWallMs: Long = 0L,
    /** Live channel with Live Rewind available but the pref OFF: show a hint that
     *  pause/rewind needs it enabled, instead of just an empty transport area. */
    showLiveRewindHint: Boolean = false,
    isPlayerPaused: Boolean = false,
    onRewindTogglePause: () -> Unit = {},
    onRewindSeekWall: (Long) -> Unit = {},
    onGoLive: () -> Unit = {},
    // Task #148 milestone B (tvOS unified-player parity): catch-up
    // transport. catchupMode renders the SAME transport row with a
    // programme-domain timeline instead of the rewind band; the +/-30s
    // pills commit through onCatchupSeekTo.
    catchupMode: Boolean = false,
    catchupTitle: String = "",
    catchupPositionMs: Long = 0L,
    catchupDurationMs: Long = 0L,
    onCatchupSeekTo: (Long) -> Unit = {},
    // Shared D-pad scrub (task #148, tvOS parity). Preview position while
    // a scrub is in flight (host commits the single seek after the
    // presses stop); HUD flag renders the timeline alone while the
    // chrome is hidden.
    scrubPreviewWallMs: Long? = null,
    scrubHudVisible: Boolean = false,
    onScrubStep: (Int, Boolean) -> Unit = { _, _ -> },
    onScrubCommit: () -> Unit = {},
) {
    var moreOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val inPip by PipState.inPictureInPicture
    val pipAvailable = remember { context.supportsPip() }

    // GH #7 follow-up (iOS PlayerView parity): manual fullscreen toggle on the
    // phone control row. Tapping forces landscape and pins it even under a
    // portrait rotation-lock; tapping again releases back to the device's own
    // orientation. The implicit auto bar-hiding (PlayerScreen) already handles
    // the system chrome; this adds the explicit user control iOS has via its
    // force-landscape button. Released on dispose so leaving the player restores
    // the user's orientation. TV is always landscape with no rotation, so it's
    // only wired into the phone branch below.
    var forcedLandscape by remember { mutableStateOf(false) }
    if (!isTv) {
        DisposableEffect(Unit) {
            onDispose {
                context.findActivity()?.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Tell the host the chrome is "busy" (Options menu or Sleep sheet open) so
    // its auto-hide timer pauses while the user is interacting. tvOS keeps the
    // panel up as long as it is open.
    LaunchedEffect(moreOpen, sleepOpen) { onInteractingChange(moreOpen || sleepOpen) }

    // Initial focus target when chrome appears -- the leftmost "Options"
    // pill on the bottom row. Without this, focus stays on PlayerScreen's
    // tap-target Box (which has clickable from gesture handling), so D-pad
    // presses don't traverse to the pills. Fired by the LaunchedEffect
    // below whenever chromeVisible flips to true.
    val optionsFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val closeFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val retryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(chromeVisible, connectionIssue) {
        if (chromeVisible) {
            kotlinx.coroutines.delay(100)
            // During a connection issue the Retry pill is the primary action,
            // so land focus there; otherwise the usual Options / Close target.
            runCatching {
                when {
                    connectionIssue && isTv -> retryFocus.requestFocus()
                    isTv -> optionsFocus.requestFocus()
                    else -> closeFocus.requestFocus()
                }
            }
        }
    }

    // Recording is Dispatcharr-only (server-side scheduling), so gate the
    // Record pill + the Options menu's Record row on it. recordCurrent
    // builds a target from live EPG, falling back to a generic 60-minute
    // window when EPG isn't loaded (Dispatcharr playlists often lack it).
    // iOS parity: a live channel can always be recorded; a non-admin
    // Dispatcharr account is coerced to a local device recording inside
    // RecordProgramSheet. Keep the dispatcharrChannelId gate so M3U/Xtream
    // channels (no recordable id) still hide the pill.
    val canRecord = channel?.dispatcharrChannelId != null
    // Switch Stream needs a Dispatcharr Direct Connect ADMIN account: the streams
    // list + change_stream live behind it, and change_stream is IsAdmin on the
    // server. Gate on the admin signal (LocalIsDispatcharrAdmin, which implies
    // Direct Connect) AND the per-channel int PK, so the option is hidden for
    // XC / M3U playlists AND for standard (non-admin) Dispatcharr sub-accounts
    // that would only get a 403 -- never show an option the user can't use.
    val canSwitchStream =
        LocalIsDispatcharrAdmin.current && channel?.dispatcharrChannelId != null
    val recordCurrent: () -> Unit = {
        val target = nowProgramme?.toInfoTarget(channel?.name.orEmpty(), channel?.dispatcharrChannelId)
            ?: channel?.let {
                val now = System.currentTimeMillis()
                ProgramInfoTarget(
                    channelName = it.name,
                    title = "${it.name} live recording",
                    startMillis = now,
                    endMillis = now + 3_600_000L,
                    description = "",
                    category = "",
                    channelDispatcharrId = it.dispatcharrChannelId,
                )
            }
        target?.let(onShowRecord)
    }

    // Phase 170: one outer Box at root so both AnimatedVisibilities live
    // in the same BoxScope (Modifier.align works) AND so neither one
    // intercepts focus / hit-testing from siblings. Each
    // AnimatedVisibility sizes itself to its content (the info-pill
    // AnimatedVisibility uses wrapContentSize so it doesn't overlap the
    // chrome's top button row).
    Box(modifier = Modifier.fillMaxSize()) {
    AnimatedVisibility(
        visible = chromeVisible && !inPip,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top + bottom gradient scrims so the card and pills stay legible
            // over bright video (matches the tvOS player chrome gradients).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                        ),
                    ),
            )

            if (isTv) {
            // Android TV (tvOS parity): a centered row of action pills at the
            // bottom -- Options | Record | Add Stream. With Live Rewind
            // buffering, a read-only timeline rides above the row and the
            // transport joins the SAME focus row as pills (identical focus
            // visuals; Options keeps initial focus, LEFT reaches transport).
            val tvRewind = timeshiftState?.buffering == true && !catchupMode
            // Task #148 milestone B: catch-up shares the rewind transport row.
            val tvTransport = tvRewind || catchupMode
            // Anchor skips on wall-clock "now" when live: the head in the
            // composed state can lag several seconds (or worse if a
            // recomposition was starved), and System.currentTimeMillis()
            // tracks the true live edge by definition while the tee runs.
            val tvCurrentWall = if (timeshiftState?.timeshifting == true) {
                timeshiftPositionWallMs
            } else {
                System.currentTimeMillis()
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            if (tvTransport) {
                if (catchupMode) {
                    TvCatchupTimeline(
                        positionMs = catchupPositionMs,
                        durationMs = catchupDurationMs,
                        title = catchupTitle.ifBlank { nowProgramme?.title.orEmpty() },
                        previewMs = scrubPreviewWallMs,
                        focusable = true,
                        onScrubStep = onScrubStep,
                        onScrubCommit = onScrubCommit,
                    )
                } else {
                    timeshiftState?.let { ts ->
                        TvRewindTimeline(
                            state = ts,
                            positionWallMs = timeshiftPositionWallMs,
                            programme = nowProgramme,
                            previewWallMs = scrubPreviewWallMs,
                            focusable = true,
                            onScrubStep = onScrubStep,
                            onScrubCommit = onScrubCommit,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(
                modifier = Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Connection-issue Retry: leads the standard control row and
                // auto-focuses (see the focus LaunchedEffect) so the remote has
                // a reachable re-tune while "Channel Unavailable" is showing.
                if (connectionIssue) {
                    PlayerPill(
                        icon = Icons.Filled.Refresh,
                        label = "Retry",
                        onClick = onRetry,
                        modifier = Modifier.focusRequester(retryFocus),
                    )
                }
                if (tvTransport) {
                    PlayerPill(
                        icon = Icons.Filled.Replay30,
                        label = "Rewind",
                        onClick = {
                            if (catchupMode) onCatchupSeekTo(catchupPositionMs - 30_000)
                            else onRewindSeekWall(tvCurrentWall - 30_000)
                        },
                    )
                    PlayerPill(
                        icon = if (isPlayerPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        label = if (isPlayerPaused) "Play" else "Pause",
                        onClick = onRewindTogglePause,
                    )
                    PlayerPill(
                        icon = Icons.Filled.Forward30,
                        label = "Forward",
                        onClick = {
                            if (catchupMode) onCatchupSeekTo(catchupPositionMs + 30_000)
                            else onRewindSeekWall(tvCurrentWall + 30_000)
                        },
                    )
                    if (!catchupMode && timeshiftState?.timeshifting == true) {
                        PlayerPill(
                            icon = Icons.Filled.PlayArrow,
                            label = "Go Live",
                            onClick = onGoLive,
                        )
                    }
                }
                Box {
                    PlayerPill(
                        icon = Icons.Filled.Tune,
                        label = "Options",
                        onClick = { moreOpen = true },
                        modifier = Modifier.focusRequester(optionsFocus),
                    )
                    PlayerMoreMenu(
                        expanded = moreOpen,
                        onDismiss = { moreOpen = false },
                        isTv = true,
                        canRecord = canRecord,
                        audioOnly = audioOnly,
                        sleepActive = sleepRemainingMillis != null,
                        aspectLabel = aspectModeLabel,
                        onCycleAspect = onCycleAspect,
                        onSubtitles = {
                            moreOpen = false
                            onShowSubtitles()
                        },
                        onAudioTracks = {
                            moreOpen = false
                            onShowAudioTracks()
                        },
                        onPlaybackSpeed = {
                            moreOpen = false
                            onShowPlaybackSpeed()
                        },
                        onRecord = {
                            moreOpen = false
                            recordCurrent()
                        },
                        onSleepTimer = {
                            moreOpen = false
                            sleepOpen = true
                        },
                        onStreamInfo = {
                            moreOpen = false
                            onShowStreamInfo()
                        },
                        canSwitchStream = canSwitchStream,
                        onSwitchStream = {
                            moreOpen = false
                            onShowSwitchStream()
                        },
                        onAudioOnly = {
                            moreOpen = false
                            onToggleAudioOnly()
                        },
                    )
                }
                // Task #148 milestone B: an archive replay can't be recorded
                // or joined by live tiles (tvOS parity: catch-up gates both).
                if (canRecord && !catchupMode) {
                    PlayerPill(
                        icon = Icons.Filled.FiberManualRecord,
                        label = "Record",
                        iconTint = Color(0xFFFF4757),
                        onClick = { recordCurrent() },
                    )
                }
                if (!catchupMode) {
                    PlayerPill(
                        icon = Icons.Filled.Add,
                        label = "Add Stream",
                        onClick = onAddToMultiview,
                    )
                }
            }
            }
            } else {
            // Phone / tablet (iOS PlayerView parity): top bar with Close on the
            // left, the channel card inline, and More / PiP / Add on the right;
            // live-progress band along the bottom.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Close",
                    onClick = onClose,
                    modifier = Modifier.focusRequester(closeFocus),
                )
                // On compact widths (portrait phone / folded Fold cover screen)
                // the secondary channel InfoCard would consume the row and push
                // the fixed right-side controls (Cast / PiP / Add) off the edge,
                // so the user couldn't reach them (GH #33 note #3). Drop the pill
                // there; it returns in landscape / on tablets / unfolded where the
                // row has room for both.
                val compactTopBar = LocalConfiguration.current.screenWidthDp < 500
                if (!compactTopBar) {
                    channel?.let { ch ->
                        Spacer(Modifier.width(12.dp))
                        InfoCard(
                            channel = ch,
                            programme = nowProgramme,
                            sleepRemainingMillis = sleepRemainingMillis,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                CircleIconButton(
                    icon = if (forcedLandscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (forcedLandscape) "Exit fullscreen" else "Fullscreen",
                    onClick = {
                        forcedLandscape = !forcedLandscape
                        context.findActivity()?.requestedOrientation = if (forcedLandscape) {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                        } else {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    CircleIconButton(
                        icon = Icons.Filled.MoreHoriz,
                        contentDescription = "More",
                        onClick = { moreOpen = true },
                    )
                    PlayerMoreMenu(
                        expanded = moreOpen,
                        onDismiss = { moreOpen = false },
                        canRecord = canRecord,
                        audioOnly = audioOnly,
                        sleepActive = sleepRemainingMillis != null,
                        aspectLabel = aspectModeLabel,
                        onCycleAspect = onCycleAspect,
                        onSubtitles = {
                            moreOpen = false
                            onShowSubtitles()
                        },
                        onAudioTracks = {
                            moreOpen = false
                            onShowAudioTracks()
                        },
                        onPlaybackSpeed = {
                            moreOpen = false
                            onShowPlaybackSpeed()
                        },
                        onRecord = {
                            moreOpen = false
                            recordCurrent()
                        },
                        onSleepTimer = {
                            moreOpen = false
                            sleepOpen = true
                        },
                        onStreamInfo = {
                            moreOpen = false
                            onShowStreamInfo()
                        },
                        canSwitchStream = canSwitchStream,
                        onSwitchStream = {
                            moreOpen = false
                            onShowSwitchStream()
                        },
                        onAudioOnly = {
                            moreOpen = false
                            onToggleAudioOnly()
                        },
                    )
                }
                if (castSlot != null && !isTv) {
                    Spacer(Modifier.width(8.dp))
                    castSlot()
                }
                if (pipAvailable) {
                    Spacer(Modifier.width(8.dp))
                    CircleIconButton(
                        icon = Icons.Filled.PictureInPicture,
                        contentDescription = "Picture in picture",
                        onClick = { context.findActivity()?.enterPip16x9() },
                    )
                }
                Spacer(Modifier.width(8.dp))
                CircleIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "Add to Multiview",
                    onClick = onAddToMultiview,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 24.dp),
            ) {
                // The transport must render whenever the buffer rolls,
                // INCLUDING on channels with no EPG data (bare M3U
                // playlists): it was nested under nowProgramme?.let, so
                // EPG-less phones got no pause/rewind/Go Live controls
                // at all. RewindTransportBar takes a nullable programme.
                if (timeshiftState?.buffering == true) {
                    // The channel/programme header at the top already
                    // names the show; the transport bar carries the
                    // remaining time inline, so no duplicate footer row.
                    RewindTransportBar(
                        state = timeshiftState,
                        positionWallMs = timeshiftPositionWallMs,
                        paused = isPlayerPaused,
                        programme = nowProgramme,
                        onTogglePause = onRewindTogglePause,
                        onSeekWall = onRewindSeekWall,
                        onGoLive = onGoLive,
                    )
                } else nowProgramme?.let { prog ->
                    run {
                        EpgProgress(programme = prog)
                        Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = prog.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatRemaining(prog),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    }
                } ?: run {
                    Text(
                        text = channel?.name ?: "AerioTV",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (showLiveRewindHint) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Turn on Live Rewind in Settings to pause & rewind live TV",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.65f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            }
        }
    }

    // Top-left channel info card. On TV it's the primary "what am I watching"
    // surface, shown whenever chrome OR the launch hint is up. On phone the
    // card lives inline in the top bar above, so this standalone copy only
    // covers the brief launch hint while the full chrome is hidden.
    AnimatedVisibility(
        visible = if (isTv) (pillVisible && !inPip) else (pillVisible && !chromeVisible && !inPip),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .padding(top = if (isTv) 24.dp else 14.dp, start = if (isTv) 28.dp else 70.dp),
    ) {
        channel?.let {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoCard(
                    channel = it,
                    programme = nowProgramme,
                    sleepRemainingMillis = sleepRemainingMillis,
                )
                if (isTv) {
                    // #10 tvOS parity: gesture hints ride the banner's same
                    // appear/fade window, left-aligned with the info card. Copy
                    // verbatim from HomeView.playerHint (tvOS single-stream live).
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlayerHintChip("Press Menu/Back to return to TV Guide.")
                        PlayerHintChip("Press Select to show player controls.")
                        if (showChannelFlipHint) {
                            PlayerHintChip("Press Up/Down to change channels.")
                        }
                    }
                }
            }
        }
    }

    // Scrub HUD (tvOS DpadScrubHUD parity): the timeline alone over the
    // bottom scrim while a chrome-hidden D-pad scrub is in flight, so
    // the user watches the preview sweep without the pill row sliding
    // in. Mutually exclusive with the full chrome above.
    AnimatedVisibility(
        visible = isTv && scrubHudVisible && !chromeVisible && !inPip &&
            (timeshiftState?.buffering == true || catchupMode),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    ),
                )
                .padding(top = 28.dp, bottom = 32.dp),
        ) {
            if (catchupMode) {
                TvCatchupTimeline(
                    positionMs = catchupPositionMs,
                    durationMs = catchupDurationMs,
                    title = catchupTitle.ifBlank { nowProgramme?.title.orEmpty() },
                    previewMs = scrubPreviewWallMs,
                )
            } else {
                timeshiftState?.let { ts ->
                    TvRewindTimeline(
                        state = ts,
                        positionWallMs = timeshiftPositionWallMs,
                        programme = nowProgramme,
                        previewWallMs = scrubPreviewWallMs,
                    )
                }
            }
        }
    }

    // Dim the video (and the rest of the chrome) behind the Options menu so the
    // panel reads clearly over bright content. tvOS dims the player while its
    // Options panel is open; the menu popup renders above this scrim.
    if (moreOpen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
        )
    }
    }  // close the outer Box added in Phase 170

    if (sleepOpen) {
        SleepTimerSheet(
            current = sleepRemainingMillis,
            onSelect = { minutes ->
                sleepOpen = false
                onSetSleepMinutes(minutes)
            },
            onDismiss = { sleepOpen = false },
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // D-pad focus must be VISIBLE on TV: track focus state and draw a
    // white ring + brightened fill (same treatment as the settings
    // pills' dpadFocusRing, drawn inline here because the button also
    // needs the fill swap). Focus and click share one target.
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(
                if (focused) Color.White.copy(alpha = 0.28f)
                else Color.Black.copy(alpha = 0.55f),
            )
            .then(
                if (focused) {
                    Modifier.border(2.dp, Color.White, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
        )
    }
}

/**
 * tvOS gesture-hint capsule (HomeView.playerHint parity): medium white@0.55 on a
 * black@0.72 pill. 8sp to match the guide hint chips (tvOS draws both at the
 * same size). Non-interactive; rides the banner's appear/fade window.
 */
@Composable
private fun PlayerHintChip(text: String) {
    Text(
        text = text,
        fontSize = 8.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * tvOS-style action pill: a rounded capsule with a leading icon + label and
 * a clear D-pad focus treatment (brighter fill + white border + grow).
 * Mirrors PlaybackBottomChrome_tvOS's Options / Record / Add Stream pills.
 */
@Composable
private fun PlayerPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.White,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    // Android-native focus treatment: a solid dark chip that fills white when
    // focused (high-contrast, the Compose-for-TV convention) plus the shared
    // focus-scale. No translucent "glass" fill or light rim.
    val contentColor = if (focused) Color.Black else Color.White
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .tvFocusScale(focused)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (iconTint == Color.White) contentColor else iconTint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PlayerMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isTv: Boolean = false,
    canRecord: Boolean,
    audioOnly: Boolean,
    sleepActive: Boolean,
    aspectLabel: String,
    onCycleAspect: () -> Unit,
    onSubtitles: () -> Unit,
    onAudioTracks: () -> Unit,
    onPlaybackSpeed: () -> Unit,
    onRecord: () -> Unit,
    onSleepTimer: () -> Unit,
    onStreamInfo: () -> Unit,
    canSwitchStream: Boolean,
    onSwitchStream: () -> Unit,
    onAudioOnly: () -> Unit,
) {
    // Each row uses a leading icon for scannability, mirroring iOS's
    // SwiftUI `Label(text, systemImage:)` pattern in PlayerView.swift
    // line 2098+. Material 3 DropdownMenuItem natively supports the
    // leadingIcon slot, so the visual treatment lines up without a
    // custom row wrapper.
    // Player chrome floats over video: force the Options menu to the active
    // theme's DARK rendition so it stays dark in Light / System-light mode too.
    // Byte-identical in dark mode (surface/onSurface/surfaceVariant/primary
    // already equal the dark scheme there); in light mode this prevents a white
    // menu with dark-on-dark text.
    val moreMenuTheme = LocalAppTheme.current
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = moreMenuTheme.accentPrimary,
            surface = moreMenuTheme.cardBackground,
            onSurface = TextPrimary,
            surfaceVariant = moreMenuTheme.cardBackground,
        ),
    ) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        if (isTv) {
            // #10 tvOS hint C: the Options panel advertises how to dismiss it.
            // Non-interactive header (D-pad focus skips it and lands on the first
            // row); Back closes the dropdown, which the "‹" chevron represents.
            Text(
                text = "Press ‹ to close",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.ClosedCaption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = { Text("Subtitles") },
            onClick = onSubtitles,
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = { Text("Audio Track") },
            onClick = onAudioTracks,
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = { Text("Playback Speed") },
            onClick = onPlaybackSpeed,
        )
        // iOS Issue #26: cycle Fit -> Zoom -> Fill. Stays open so repeated
        // presses cycle; the label reflects the current mode.
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.AspectRatio,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = { Text("Aspect Ratio: $aspectLabel") },
            onClick = onCycleAspect,
        )
        if (canRecord) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = Color(0xFFFF4757),
                    )
                },
                text = { Text("Record Current Program") },
                onClick = onRecord,
            )
        }
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (sleepActive)
                        Icons.Filled.Bedtime
                    else
                        Icons.Outlined.Bedtime,
                    contentDescription = null,
                    tint = if (sleepActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(if (sleepActive) "Sleep Timer (active)" else "Sleep Timer")
            },
            onClick = onSleepTimer,
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = { Text("Stream Info") },
            onClick = onStreamInfo,
        )
        if (canSwitchStream) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                text = { Text("Switch Stream") },
                onClick = onSwitchStream,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (audioOnly)
                        Icons.Filled.MusicNote
                    else
                        Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = if (audioOnly)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                // iOS toggles the label to "Show Video" when in Audio
                // Only mode so the action describes what tapping does,
                // not what state it shows. Port that.
                Text(if (audioOnly) "Show Video" else "Audio Only")
            },
            onClick = onAudioOnly,
        )
    }
    }
}

@Composable
private fun InfoCard(
    channel: M3UChannel,
    programme: EPGProgramme?,
    sleepRemainingMillis: Long?,
) {
    // tvOS-parity info pill (Archie 2026-05-28 reference shot).
    // Layout:
    //   [ LOGO ]  <number> <name>                       [SLEEP]
    //             <programme title>
    //             <time range>  ·  <duration>
    //
    // The logo sits on the left; channel number is inline with the channel
    // name on the first line (not a separate column). All three text rows
    // are white -- programme name doesn't use the accent tint that the
    // earlier Android pass added.
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.tvgLogo.isNotBlank()) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    // Phase 174: force Coil to decode at the source's
                    // original resolution + let GPU filtering scale it
                    // down to the 40dp display target. Coil's default
                    // Precision.AUTOMATIC samples to the View's pixel
                    // bounds (44dp box = ~88px on the Streamer at
                    // density 2.0), which makes a 256x256 logo bitmap
                    // collapse to ~80x80 with noticeable softness. With
                    // Size.ORIGINAL the bitmap arrives in memory at
                    // native resolution and the GPU does the bilinear
                    // downscale -- visibly sharper at the cost of a
                    // few KB extra RAM per cached logo (fine for a
                    // single chrome pill at a time).
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data(channel.tvgLogo)
                            .size(Size.ORIGINAL)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                // Cap the column at a sane width so the pill stays compact
                // (tvOS reference proportions). Without this cap, weight(1f)
                // -> Column would stretch the entire pill to fit any width
                // Compose hands it from the parent.
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                val nameLine = channel.channelNumber?.let { "$it  ${channel.name}" }
                    ?: channel.name
                Text(
                    text = nameLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (programme != null) {
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                    val timeRange = formatTimeRange(programme)
                    val duration = formatDuration(programme.endMillis - programme.startMillis)
                    Text(
                        text = "$timeRange  ·  $duration",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            sleepRemainingMillis?.let { remaining ->
                val mins = (remaining / 60_000L).coerceAtLeast(0L)
                Spacer(Modifier.width(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = "💤 ${mins}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Live Rewind transport (task #143): interactive timeline over the local
 * timeshift buffer. The track spans [tail .. live edge] of the rolling
 * buffer; while live-at-edge the thumb rides the right end. Dragging back
 * (or the skip buttons, which are the D-pad path on TV) re-opens playback
 * inside the buffer; Go Live re-tunes the direct stream.
 */
@Composable
private fun RewindTransportBar(
    state: com.aeriotv.android.core.timeshift.TimeshiftController.State,
    positionWallMs: Long,
    paused: Boolean,
    programme: EPGProgramme?,
    onTogglePause: () -> Unit,
    onSeekWall: (Long) -> Unit,
    onGoLive: () -> Unit,
) {
    val head = maxOf(state.headWallMs, state.tailWallMs + 1)
    val tail = state.tailWallMs
    val span = (head - tail).coerceAtLeast(1)
    val current = if (state.timeshifting) positionWallMs.coerceIn(tail, head) else head
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: ((current - tail).toFloat() / span.toFloat()).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // On TV the timeline is a read-only position display: D-pad
        // seeking goes through the centered skip buttons (the VOD player
        // model), so the slider must not be a focus stop the remote can
        // get trapped in.
        val isTvDevice = com.aeriotv.android.feature.settings.rememberIsTvDevice()
        Slider(
            value = fraction,
            enabled = !isTvDevice,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { f -> onSeekWall(tail + (span * f).toLong()) }
                dragFraction = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                disabledThumbColor = MaterialTheme.colorScheme.primary,
                disabledActiveTrackColor = MaterialTheme.colorScheme.primary,
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.18f),
            ),
        )
        // Status line under the timeline: remaining time on the LEFT,
        // LIVE / behind-live indicator on the RIGHT (under the live edge
        // of the track). Both are computed against the (possibly
        // shifted) playback position so they stay truthful while
        // rewound.
        val behindMs = head - current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Keep the status text off the physical display edge
                // (the timeline track intentionally runs wider).
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            programme?.let { prog ->
                val remMin = ((prog.endMillis - current).coerceAtLeast(0) / 60_000).toInt()
                Text(
                    text = if (remMin >= 60) {
                        "${remMin / 60} h ${remMin % 60} min remaining"
                    } else {
                        "$remMin min remaining"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.timeshifting && behindMs > 5_000) {
                    val totalSec = behindMs / 1000
                    String.format("-%d:%02d", totalSec / 60, totalSec % 60)
                } else {
                    "LIVE"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (state.timeshifting && behindMs > 5_000) {
                    Color.White.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        // Transport buttons centered; the Go Live pill (only while
        // rewound) anchors to the right edge without disturbing the
        // centering.
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CircleIconButton(
                    icon = Icons.Filled.Replay30,
                    contentDescription = "Back 30 seconds",
                    onClick = { onSeekWall(current - 30_000) },
                )
                CircleIconButton(
                    icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "Play" else "Pause",
                    onClick = onTogglePause,
                )
                CircleIconButton(
                    icon = Icons.Filled.Forward30,
                    contentDescription = "Forward 30 seconds",
                    onClick = { onSeekWall(current + 30_000) },
                )
            }
            if (state.timeshifting) {
                var goLiveFocused by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = onGoLive,
                    border = if (goLiveFocused) {
                        androidx.compose.foundation.BorderStroke(2.dp, Color.White)
                    } else {
                        null
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .onFocusChanged { goLiveFocused = it.isFocused },
                ) {
                    Text(
                        text = "Go Live",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Android TV Live Rewind timeline. Read-only display by default; when
 * `focusable` (chrome visible) it is a D-pad focus target: UP from the
 * pill row lands here, LEFT/RIGHT step the shared scrub preview
 * (`onScrubStep`; a held edge accelerates via native key repeats), OK
 * commits the pending scrub immediately, DOWN falls back to the pill
 * row through normal traversal, UP is swallowed (channel-zap declines
 * while the bottom chrome is up anyway). While a preview is in flight
 * `previewWallMs` replaces the playhead so the user watches the thumb
 * sweep BEFORE the single seek commits - every seek is a whole buffer
 * re-open, so previewing per press and committing once is the only
 * smooth model (tvOS `.timeline` focus-target parity).
 */
/** Task #148 milestone B: the catch-up twin of [TvRewindTimeline]. Same
 *  focus/key/scrub behavior, but the domain is PROGRAMME-relative
 *  [0, durationMs] (tvOS CatchupTimelineBand parity): title on the left,
 *  position / duration clock on the right. */
@Composable
private fun TvCatchupTimeline(
    positionMs: Long,
    durationMs: Long,
    title: String,
    previewMs: Long? = null,
    focusable: Boolean = false,
    onScrubStep: (Int, Boolean) -> Unit = { _, _ -> },
    onScrubCommit: () -> Unit = {},
) {
    val dur = durationMs.coerceAtLeast(1L)
    val current = (previewMs ?: positionMs).coerceIn(0L, dur)
    val fraction = (current.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    var focused by remember { mutableStateOf(false) }
    var trackWidthPx by remember { mutableStateOf(0f) }
    val focusModifier = if (focusable) {
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (!focused) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                            val dir = if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1
                            onScrubStep(dir, native.repeatCount > 0)
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    -> {
                        if (native.action == android.view.KeyEvent.ACTION_DOWN) onScrubCommit()
                        true
                    }
                    // Dead-end above the timeline (tvOS: UP is swallowed).
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> true
                    // DOWN falls through -> focus traversal to the pills.
                    else -> false
                }
            }
            .focusable()
    } else {
        Modifier
    }
    fun clock(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp)
            .then(focusModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { trackWidthPx = it.width.toFloat() },
            contentAlignment = Alignment.CenterStart,
        ) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (focused) 7.dp else 5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = if (focused) 0.3f else 0.18f),
                drawStopIndicator = {},
            )
            if (focused) {
                val thumbX = with(LocalDensity.current) {
                    (trackWidthPx * fraction).toDp() - 8.dp
                }
                Box(
                    modifier = Modifier
                        .padding(start = thumbX.coerceAtLeast(0.dp))
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${clock(current)} / ${clock(dur)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun TvRewindTimeline(
    state: com.aeriotv.android.core.timeshift.TimeshiftController.State,
    positionWallMs: Long,
    programme: EPGProgramme?,
    previewWallMs: Long? = null,
    focusable: Boolean = false,
    onScrubStep: (Int, Boolean) -> Unit = { _, _ -> },
    onScrubCommit: () -> Unit = {},
) {
    val head = maxOf(state.headWallMs, state.tailWallMs + 1)
    val tail = state.tailWallMs
    val span = (head - tail).coerceAtLeast(1)
    val current = (previewWallMs ?: if (state.timeshifting) positionWallMs else head)
        .coerceIn(tail, head)
    val fraction = ((current - tail).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    val behindMs = head - current
    // The behind-live label must track the PREVIEW during a scrub, even
    // before the first commit flips `timeshifting`.
    val showBehind = (previewWallMs != null || state.timeshifting) && behindMs > 5_000
    var focused by remember { mutableStateOf(false) }
    var trackWidthPx by remember { mutableStateOf(0f) }
    val focusModifier = if (focusable) {
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (!focused) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                            val dir = if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else +1
                            onScrubStep(dir, native.repeatCount > 0)
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    -> {
                        if (native.action == android.view.KeyEvent.ACTION_DOWN) onScrubCommit()
                        true
                    }
                    // Dead-end above the timeline; also keeps the press
                    // from leaking anywhere surprising.
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> true
                    // DOWN falls through -> focus traversal to the pills.
                    else -> false
                }
            }
            .focusable()
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 56.dp)
            .then(focusModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { trackWidthPx = it.width.toFloat() },
            contentAlignment = Alignment.CenterStart,
        ) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (focused) 7.dp else 5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = if (focused) 0.3f else 0.18f),
                drawStopIndicator = {},
            )
            if (focused) {
                val thumbX = with(LocalDensity.current) {
                    (trackWidthPx * fraction).toDp() - 8.dp
                }
                Box(
                    modifier = Modifier
                        .padding(start = thumbX.coerceAtLeast(0.dp))
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            programme?.let { prog ->
                val remMin = ((prog.endMillis - current).coerceAtLeast(0) / 60_000).toInt()
                Text(
                    text = if (remMin >= 60) {
                        "${remMin / 60} h ${remMin % 60} min remaining"
                    } else {
                        "$remMin min remaining"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (showBehind) {
                    val totalSec = behindMs / 1000
                    String.format("-%d:%02d", totalSec / 60, totalSec % 60)
                } else {
                    "LIVE"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (showBehind) {
                    Color.White.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun EpgProgress(programme: EPGProgramme) {
    val now = System.currentTimeMillis()
    val total = (programme.endMillis - programme.startMillis).coerceAtLeast(1L)
    val elapsed = (now - programme.startMillis).coerceAtLeast(0L).coerceAtMost(total)
    val progress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.White.copy(alpha = 0.18f),
        drawStopIndicator = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    current: Long?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            SLEEP_OPTIONS.forEach { mins ->
                val label = if (mins == 0) "Off" else "$mins minutes"
                val isActive = (mins == 0 && current == null) ||
                        (mins != 0 && current != null && ((current / 60_000L).toInt() in (mins - 1)..mins))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isActive,
                        onClick = { onSelect(mins) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Stream Info ModalBottomSheet, displayed as a stand-alone modal so the user can
 * read the technical details and dismiss. Reads MPV properties at open time;
 * not live-updating per second since codec/format don't change during playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamInfoSheet(
    snapshot: StreamInfoSnapshot,
    onDismiss: () -> Unit,
) {
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = "Stream Info",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            StreamInfoSection("VIDEO", snapshot.videoLines)
            Spacer(Modifier.height(10.dp))
            StreamInfoSection("AUDIO", snapshot.audioLines)
            Spacer(Modifier.height(10.dp))
            StreamInfoSection("CACHE", snapshot.cacheLines)
            Spacer(Modifier.height(10.dp))
            StreamInfoSection(" SYNC", snapshot.syncLines)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StreamInfoSection(label: String, lines: List<String>) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/**
 * Subtitle tracks ModalBottomSheet. Off + one row per MPV `sid` track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlesSheet(
    tracks: List<SubtitleTrack>,
    currentTrackId: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            SubtitleRow(label = "Off", selected = currentTrackId == null, onClick = { onSelect(null) })
            if (tracks.isEmpty()) {
                Text(
                    text = "No subtitle tracks reported by the stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                tracks.forEach { track ->
                    val label = buildString {
                        append(track.title.ifBlank { "Track ${track.id}" })
                        if (track.lang.isNotBlank()) append("  ·  ${track.lang}")
                    }
                    SubtitleRow(
                        label = label,
                        selected = currentTrackId == track.id,
                        onClick = { onSelect(track.id) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Sister sheet to [SubtitlesSheet] for picking the active audio track. Same
 * RadioButton-row layout so it reads identically; difference is no "Off" row
 * (every live stream needs an audio track to play sound; mute lives in the
 * Audio Only / system volume affordance, not here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTracksSheet(
    tracks: List<AudioTrack>,
    currentTrackId: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = "Audio Track",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            if (tracks.isEmpty()) {
                Text(
                    text = "No audio tracks reported by the stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                tracks.forEach { track ->
                    val label = buildString {
                        append(track.title.ifBlank { "Track ${track.id}" })
                        val meta = buildList {
                            if (track.lang.isNotBlank()) add(track.lang)
                            if (track.codec.isNotBlank()) add(track.codec)
                            if (track.channels.isNotBlank()) add(track.channels)
                        }
                        if (meta.isNotEmpty()) append("  ·  ${meta.joinToString("  ·  ")}")
                    }
                    SubtitleRow(
                        label = label,
                        selected = currentTrackId == track.id,
                        onClick = { onSelect(track.id) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Player "Switch Stream" picker (Dispatcharr Direct Connect). Lists the
 * channel's member streams with their probed quality (resolution / fps /
 * bitrate / codec); selecting one POSTs change_stream + re-primes playback.
 * Clones [AudioTracksSheet]'s RadioButton-row layout. Streams Dispatcharr has
 * not probed yet show name-only (stats are null until a source has been played).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchStreamSheet(
    streams: List<StreamOption>,
    currentStreamId: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        // verticalScroll so channels with many streams (users keep 2-20) are all
        // reachable; FormFactorModal caps the modal height, which otherwise just
        // clipped the rows past the fold (only ~7 were selectable). Works for
        // touch and for TV D-pad (focusing an off-screen row scrolls it in).
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Switch Stream",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            if (streams.isEmpty()) {
                Text(
                    text = "No alternate streams available for this channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                streams.forEach { stream ->
                    SubtitleRow(
                        label = stream.label,
                        selected = currentStreamId == stream.id,
                        onClick = { onSelect(stream.id) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Bottom-sheet picker for the mpv `speed` property. Discrete options
 * matching the iOS player (0.5x .. 2.0x). For live streams: faster speeds
 * eventually drain the demuxer buffer and the stream falls behind / catches
 * up to the live edge, which mpv handles automatically. The 1.0 default
 * stays the dominant choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = "Playback Speed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            PLAYBACK_SPEEDS.forEach { (value, label) ->
                SubtitleRow(
                    label = label,
                    selected = kotlin.math.abs(currentSpeed - value) < 0.01f,
                    onClick = { onSelect(value) },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private val PLAYBACK_SPEEDS = listOf(
    0.5f to "0.5x",
    0.75f to "0.75x",
    1.0f to "Normal (1.0x)",
    1.25f to "1.25x",
    1.5f to "1.5x",
    2.0f to "2.0x",
)

@Composable
private fun SubtitleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun formatRemaining(programme: EPGProgramme): String {
    val remainingMs = (programme.endMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val minutes = remainingMs / 60_000L
    if (minutes <= 0L) return "ending"
    if (minutes < 60L) return "$minutes min remaining"
    val hours = minutes / 60L
    val leftover = minutes % 60L
    return if (leftover == 0L) "$hours h remaining" else "$hours h $leftover min remaining"
}

private fun formatTimeRange(programme: EPGProgramme): String {
    val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
    return "${tf.format(Date(programme.startMillis))} – ${tf.format(Date(programme.endMillis))}"
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return ""
    val totalMinutes = ((millis + 30_000L) / 60_000L).toInt()
    if (totalMinutes < 60) return "${totalMinutes}m"
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
}

private val SLEEP_OPTIONS = listOf(0, 30, 60, 90, 120)

// ──────────────────────────────────────────────────────────────────────────
// Models exported for PlayerScreen to populate from MPVPlayerView properties.
// ──────────────────────────────────────────────────────────────────────────

data class SubtitleTrack(
    val id: Int,
    val title: String,
    val lang: String,
)

/** A selectable audio track surfaced from mpv `track-list` (type=audio). The
 *  optional [codec] / [channels] labels surface helpful disambiguation when a
 *  stream carries multiple audio renditions (e.g. AC3 5.1 vs AAC stereo). */
data class AudioTrack(
    val id: Int,
    val title: String,
    val lang: String,
    val codec: String,
    val channels: String,
)

/** A selectable Dispatcharr member stream for the player's Switch Stream sheet.
 *  Quality fields are null until Dispatcharr has probed that source, so [label]
 *  degrades to the stream name / "Stream {id}". */
data class StreamOption(
    val id: Int,
    val name: String,
    val resolution: String?,
    val fps: Double?,
    val bitrateKbps: Double?,
    val videoCodec: String?,
    val audioCodec: String?,
    /** Name of the source M3U in Dispatcharr (resolved from the stream's
     *  m3u_account), so the user can tell which provider each alternate is from. */
    val sourceName: String? = null,
) {
    /** Human row, e.g. "FOX 28  ·  Provider A  ·  1080p  ·  60fps  ·  8.2 Mbps  ·  H.264".
     *  The M3U source leads the meta so it is easy to scan which provider a
     *  stream comes from; quality params follow. */
    val label: String
        get() = buildString {
            append(name.ifBlank { "Stream $id" })
            val meta = buildList {
                sourceName?.takeIf { it.isNotBlank() }?.let { add(it) }
                resolution?.let { add(prettyResolution(it)) }
                fps?.let { add("${it.toInt()}fps") }
                bitrateKbps?.let { add(prettyBitrate(it)) }
                videoCodec?.let { add(prettyCodec(it)) }
                audioCodec?.let { add(it.uppercase()) }
            }
            if (meta.isNotEmpty()) append("  ·  ${meta.joinToString("  ·  ")}")
        }
}

private fun prettyResolution(raw: String): String {
    // Dispatcharr stores "1920x1080" lowercase; show the friendly tier when the
    // height is a known one, else the raw value.
    val h = raw.lowercase().substringAfter('x', "").toIntOrNull()
    return when (h) {
        2160 -> "4K"
        1080 -> "1080p"
        720 -> "720p"
        576 -> "576p"
        480 -> "480p"
        null -> raw
        else -> "${h}p"
    }
}

private fun prettyBitrate(kbps: Double): String =
    if (kbps >= 1000.0) String.format(java.util.Locale.US, "%.1f Mbps", kbps / 1000.0)
    else "${kbps.toInt()} kbps"

private fun prettyCodec(raw: String): String = when (raw.lowercase()) {
    "h264", "avc", "avc1" -> "H.264"
    "hevc", "h265" -> "HEVC"
    "mpeg2video", "mpeg2" -> "MPEG-2"
    else -> raw.uppercase()
}

data class StreamInfoSnapshot(
    val videoLines: List<String>,
    val audioLines: List<String>,
    val cacheLines: List<String>,
    val syncLines: List<String>,
)
