package com.aeriotv.android.feature.player

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.ui.LocalCanRecordToServer
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.pip.supportsPip
import com.aeriotv.android.feature.livetv.RecordProgramSheet
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
    onClose: () -> Unit,
    onAddToMultiview: () -> Unit,
    onShowRecord: (ProgramInfoTarget) -> Unit,
    onShowStreamInfo: () -> Unit,
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
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            kotlinx.coroutines.delay(100)
            runCatching { (if (isTv) optionsFocus else closeFocus).requestFocus() }
        }
    }

    // Recording is Dispatcharr-only (server-side scheduling), so gate the
    // Record pill + the Options menu's Record row on it. recordCurrent
    // builds a target from live EPG, falling back to a generic 60-minute
    // window when EPG isn't loaded (Dispatcharr playlists often lack it).
    val canRecord = channel?.dispatcharrChannelId != null && LocalCanRecordToServer.current
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
            // bottom -- Options | Record | Add Stream. The channel info card is
            // rendered top-left by the sibling AnimatedVisibility below.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        onAudioOnly = {
                            moreOpen = false
                            onToggleAudioOnly()
                        },
                    )
                }
                if (canRecord) {
                    PlayerPill(
                        icon = Icons.Filled.FiberManualRecord,
                        label = "Record",
                        iconTint = Color(0xFFFF4757),
                        onClick = { recordCurrent() },
                    )
                }
                PlayerPill(
                    icon = Icons.Filled.Add,
                    label = "Add Stream",
                    onClick = onAddToMultiview,
                )
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
                channel?.let { ch ->
                    Spacer(Modifier.width(12.dp))
                    InfoCard(
                        channel = ch,
                        programme = nowProgramme,
                        sleepRemainingMillis = sleepRemainingMillis,
                    )
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
                        onAudioOnly = {
                            moreOpen = false
                            onToggleAudioOnly()
                        },
                    )
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
                nowProgramme?.let { prog ->
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
                } ?: run {
                    Text(
                        text = channel?.name ?: "AerioTV",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp),
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
            InfoCard(
                channel = it,
                programme = nowProgramme,
                sleepRemainingMillis = sleepRemainingMillis,
            )
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
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
            )
        }
    }
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
    onAudioOnly: () -> Unit,
) {
    // Each row uses a leading icon for scannability, mirroring iOS's
    // SwiftUI `Label(text, systemImage:)` pattern in PlayerView.swift
    // line 2098+. Material 3 DropdownMenuItem natively supports the
    // leadingIcon slot, so the visual treatment lines up without a
    // custom row wrapper.
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
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

data class StreamInfoSnapshot(
    val videoLines: List<String>,
    val audioLines: List<String>,
    val cacheLines: List<String>,
    val syncLines: List<String>,
)
