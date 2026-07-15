package com.aeriotv.android.feature.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.cast.CastControl
import com.aeriotv.android.feature.player.AudioTrack
import com.aeriotv.android.feature.player.AudioTracksSheet
import com.aeriotv.android.feature.player.PlaybackSpeedSheet
import com.aeriotv.android.feature.player.SubtitleTrack
import com.aeriotv.android.feature.player.SubtitlesSheet

/**
 * The phone's "Now Casting" remote (GH #33 full-parity). Shown over the (locally
 * suspended) player while a Cast Connect session is live, it turns the phone into
 * a remote for the TV: transport (play/pause), channel up/down, stop casting, and
 * the same audio-track / subtitle / playback-speed / aspect controls as the local
 * player -- driven by [CastControl.RemoteState] the receiver reports and committed
 * back over the custom channel. The pickers are the exact local sheets, fed from
 * the remote state, so they read identically.
 */
@Composable
fun CastRemoteOverlay(
    deviceName: String?,
    channelTitle: String,
    programmeTitle: String?,
    remoteState: CastControl.RemoteState,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    onStopCasting: () -> Unit,
    onSetAudioTrack: (String) -> Unit,
    onSetTextTrack: (String?) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetAspect: (CastControl.AspectMode) -> Unit,
    onSetAudioOnly: (Boolean) -> Unit,
    onSwitchStream: () -> Unit,
    onRecord: () -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekToWall: (Long) -> Unit,
    onGoLive: () -> Unit,
    onMinimize: () -> Unit,
    position: CastControl.PositionSnapshot,
    canSwitchStream: Boolean,
    canRecord: Boolean,
    onRefreshState: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** "Casting to" (Cast) vs "Controlling" (LAN companion remote, GH #33). */
    statusVerb: String = "Casting to",
) {
    var optionsOpen by remember { mutableStateOf(false) }
    var audioOpen by remember { mutableStateOf(false) }
    var subsOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }

    // Pull a fresh snapshot when the remote appears; each command reply keeps it
    // current thereafter, and re-opening Options re-pulls after a channel change.
    androidx.compose.runtime.LaunchedEffect(Unit) { onRefreshState() }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Top bar: minimize back to browsing, by tap OR a downward drag. The
        // cast keeps playing; the Now-Casting mini controller reappears on the
        // tabs so a new channel can be picked (GH #33 - Stop below ends the cast).
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .pointerInput(Unit) {
                    var draggedDown = 0f
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (draggedDown > 100f) onMinimize()
                            draggedDown = 0f
                        },
                        onDragCancel = { draggedDown = 0f },
                    ) { _, dy -> if (dy > 0) draggedDown += dy }
                }
                .clickable(onClick = onMinimize)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Tap/Drag here to return to Guide",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        // Centered "casting to" panel.
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Cast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = channelTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            programmeTitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "$statusVerb ${deviceName ?: "your TV"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }

        // Bottom transport + options bar.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Live-rewind controls (GH #33): a draggable scrubber + 30s FF/RW +
            // LIVE pill. Shown as soon as the receiver reports a rewind buffer via
            // EITHER the getState echo's canSeek or the ~1Hz position tick, so the
            // buttons never wait a tick to appear; the draggable scrubber needs the
            // tick's window, so it renders once position data arrives.
            val rewindActive = position.canSeek || remoteState.canSeek
            val atLive = if (position.canSeek) position.isLive else remoteState.isLive
            if (rewindActive) {
                if (position.canSeek) {
                    val span = (position.windowEndMs - position.windowStartMs).coerceAtLeast(1L)
                    var dragFraction by remember { mutableStateOf<Float?>(null) }
                    var pendingSeekWall by remember { mutableStateOf<Long?>(null) }
                    // After release, HOLD the dragged thumb until the receiver's
                    // reported position reaches the seek target, so it doesn't snap
                    // back to the pre-seek position for the ~1s+re-buffer gap. The
                    // convergence effect is re-keyed on each tick to read the fresh
                    // position; a 6s timeout releases the hold if it never converges.
                    androidx.compose.runtime.LaunchedEffect(pendingSeekWall, position.positionWallMs) {
                        val target = pendingSeekWall ?: return@LaunchedEffect
                        if (kotlin.math.abs(position.positionWallMs - target) < 4_000L) {
                            dragFraction = null
                            pendingSeekWall = null
                        }
                    }
                    androidx.compose.runtime.LaunchedEffect(pendingSeekWall) {
                        if (pendingSeekWall == null) return@LaunchedEffect
                        kotlinx.coroutines.delay(6_000L)
                        dragFraction = null
                        pendingSeekWall = null
                    }
                    val liveFraction =
                        ((position.positionWallMs - position.windowStartMs).toFloat() / span).coerceIn(0f, 1f)
                    val shownFraction = dragFraction ?: liveFraction
                    val behindMs = (span - (shownFraction * span).toLong()).coerceAtLeast(0L)
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Slider(
                            value = shownFraction,
                            onValueChange = { dragFraction = it },
                            onValueChangeFinished = {
                                dragFraction?.let { f ->
                                    val target = position.windowStartMs + (f * span).toLong()
                                    onSeekToWall(target)
                                    pendingSeekWall = target
                                }
                            },
                        )
                        Text(
                            text = if (position.isLive && dragFraction == null) {
                                "LIVE"
                            } else {
                                "-${formatBehindLive(behindMs)} behind live"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                ) {
                    RemoteButton(Icons.Filled.Replay30, "Back 30 seconds", { onSeekBy(-30_000L) })
                    if (!atLive) {
                        GoLivePill(onClick = onGoLive)
                    }
                    RemoteButton(Icons.Filled.Forward30, "Forward 30 seconds", { onSeekBy(30_000L) })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteButton(Icons.Filled.KeyboardArrowDown, "Channel down", onChannelDown)
                RemoteButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    desc = if (isPlaying) "Pause" else "Play",
                    onClick = onTogglePlayPause,
                    emphasized = true,
                )
                RemoteButton(Icons.Filled.KeyboardArrowUp, "Channel up", onChannelUp)
                Spacer(Modifier.width(6.dp))
                RemoteButton(Icons.Filled.Tune, "Options", {
                    onRefreshState()
                    optionsOpen = true
                })
                RemoteButton(Icons.Filled.Close, "Stop casting", onStopCasting)
            }
        }
    }

    if (optionsOpen) {
        com.aeriotv.android.ui.FormFactorModal(onDismiss = { optionsOpen = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Cast controls",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                if (canSwitchStream) {
                    OptionRow(Icons.Filled.SwapHoriz, "Switch Stream", null) {
                        optionsOpen = false
                        onSwitchStream()
                    }
                }
                OptionRow(Icons.Outlined.MusicNote, "Audio Track", remoteState.audio.firstOrNull { it.selected }?.label) {
                    optionsOpen = false
                    audioOpen = true
                }
                OptionRow(Icons.Filled.Subtitles, "Subtitles", if (remoteState.textOff) "Off" else remoteState.text.firstOrNull { it.selected }?.label ?: "On") {
                    optionsOpen = false
                    subsOpen = true
                }
                OptionRow(Icons.Filled.Speed, "Playback Speed", speedLabel(remoteState.speed)) {
                    optionsOpen = false
                    speedOpen = true
                }
                OptionRow(Icons.Outlined.AspectRatio, "Aspect Ratio", remoteState.aspect.label) {
                    onSetAspect(remoteState.aspect.next())
                }
                if (canRecord) {
                    OptionRow(Icons.Filled.FiberManualRecord, "Record Current Program", null) {
                        optionsOpen = false
                        onRecord()
                    }
                }
                OptionRow(Icons.Filled.Timer, "Sleep Timer", null) {
                    optionsOpen = false
                    sleepOpen = true
                }
                OptionRow(Icons.Filled.Info, "Stream Info", null) {
                    optionsOpen = false
                    infoOpen = true
                }
                OptionRow(Icons.Filled.VideocamOff, "Audio Only", if (remoteState.audioOnly) "On" else "Off") {
                    onSetAudioOnly(!remoteState.audioOnly)
                }
            }
        }
    }

    if (sleepOpen) {
        com.aeriotv.android.ui.FormFactorModal(onDismiss = { sleepOpen = false }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = "Sleep Timer",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                listOf(0 to "Off", 30 to "30 minutes", 60 to "1 hour", 90 to "1.5 hours", 120 to "2 hours")
                    .forEach { (minutes, label) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSleepMinutes(minutes)
                                    sleepOpen = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (infoOpen) {
        com.aeriotv.android.ui.FormFactorModal(onDismiss = { infoOpen = false }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = "Stream Info",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = remoteState.streamInfo.ifBlank { "No stream details available" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (audioOpen) {
        AudioTracksSheet(
            tracks = remoteState.audio.map { it.toAudioTrack() },
            currentTrackId = remoteState.audio.firstOrNull { it.selected }?.id?.toIntOrNull(),
            onSelect = { id ->
                onSetAudioTrack(id.toString())
                audioOpen = false
            },
            onDismiss = { audioOpen = false },
        )
    }
    if (subsOpen) {
        SubtitlesSheet(
            tracks = remoteState.text.map { it.toSubtitleTrack() },
            currentTrackId = if (remoteState.textOff) null else remoteState.text.firstOrNull { it.selected }?.id?.toIntOrNull(),
            onSelect = { id ->
                onSetTextTrack(id?.toString())
                subsOpen = false
            },
            onDismiss = { subsOpen = false },
        )
    }
    if (speedOpen) {
        PlaybackSpeedSheet(
            currentSpeed = remoteState.speed,
            onSelect = { s ->
                onSetSpeed(s)
                speedOpen = false
            },
            onDismiss = { speedOpen = false },
        )
    }
}

private fun CastControl.Track.toAudioTrack(): AudioTrack =
    AudioTrack(id = id.toIntOrNull() ?: id.hashCode(), title = label, lang = "", codec = "", channels = "")

private fun CastControl.Track.toSubtitleTrack(): SubtitleTrack =
    SubtitleTrack(id = id.toIntOrNull() ?: id.hashCode(), title = label, lang = "")

private fun speedLabel(speed: Float): String =
    if (kotlin.math.abs(speed - 1f) < 0.01f) "Normal" else "${speed}x"

@Composable
private fun RemoteButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val size = if (emphasized) 64.dp else 52.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (emphasized) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = if (emphasized) MaterialTheme.colorScheme.onPrimary else Color.White,
                modifier = Modifier.size(if (emphasized) 32.dp else 26.dp),
            )
        }
    }
}

/** Red "LIVE" pill shown while the cast is rewound; tap returns to the live edge. */
@Composable
private fun GoLivePill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFD32F2F))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
        Text(
            text = "LIVE",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Format a "behind live" duration as M:SS (or H:MM:SS past an hour). */
private fun formatBehindLive(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            value?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
