package com.aeriotv.android.feature.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.LinkOff
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.cast.CastControl
import com.aeriotv.android.feature.player.AudioTrack
import com.aeriotv.android.feature.player.AudioTracksSheet
import com.aeriotv.android.feature.player.PlaybackSpeedSheet
import com.aeriotv.android.feature.player.SubtitleTrack
import com.aeriotv.android.feature.player.SubtitlesSheet

/**
 * The phone's remote controls for whatever is playing on another screen
 * (Cast card UX, Logan 2026-09-12): ONE sheet, two transports. It is opened by
 * tapping the cast card above the tab bar and never replaces the page the user
 * is on -- the old full-screen presentation inside the player is gone, along
 * with the "pick a channel" cover.
 *
 * Contents: transport (play/pause), channel up/down for live, live-rewind
 * scrubbing when the other screen reports a buffer, the Options the transport
 * supports (audio track, subtitles, speed, aspect, stream info, Switch Stream on
 * a Dispatcharr channel, sleep timer, and Disconnect on the companion transport)
 * plus stop. Driven by
 * [CastControl.RemoteState] the receiver reports and committed back over the
 * control channel; the pickers are the exact local player sheets so they read
 * identically.
 */
@Composable
fun CastRemoteSheet(
    deviceName: String?,
    channelTitle: String,
    programmeTitle: String?,
    remoteState: CastControl.RemoteState,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    /** Ends the session (and hides the card). Never resumes playback locally. */
    onStopCasting: () -> Unit,
    onSetAudioTrack: (String) -> Unit,
    onSetTextTrack: (String?) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetAspect: (CastControl.AspectMode) -> Unit,
    onSetAudioOnly: (Boolean) -> Unit,
    onSwitchStream: () -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekToWall: (Long) -> Unit,
    onGoLive: () -> Unit,
    onDismiss: () -> Unit,
    position: CastControl.PositionSnapshot,
    canSwitchStream: Boolean,
    /** Channel up/down only apply to a live channel on the other screen. */
    canChangeChannel: Boolean = true,
    onRefreshState: () -> Unit = {},
    /** Cast glyph for Google Cast, TV glyph for the AerioTV Remote transport. */
    transportIcon: ImageVector = Icons.Filled.Cast,
    /** "Casting to" (Cast) vs "Controlling" (LAN companion remote). */
    statusVerb: String = "Casting to",
    /** Label for the stop action: "Stop casting" for Cast, "Disconnect" for the
     *  companion transport. */
    stopLabel: String = "Stop casting",
    /** Companion transport only (Logan 2026-09-12): drop the AerioTV Remote link
     *  and hide the card while the TV keeps playing. Null for Google Cast, where
     *  there is nothing to leave behind once the session ends. */
    onDisconnect: (() -> Unit)? = null,
) {
    var optionsOpen by remember { mutableStateOf(false) }
    var audioOpen by remember { mutableStateOf(false) }
    var subsOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }

    // Pull a fresh snapshot when the sheet appears; each command reply keeps it
    // current thereafter, and re-opening Options re-pulls after a channel change.
    androidx.compose.runtime.LaunchedEffect(Unit) { onRefreshState() }

    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = transportIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = channelTitle.ifBlank { "Nothing playing" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            programmeTitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$statusVerb ${deviceName ?: "your TV"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(14.dp))

            // Live-rewind controls: a draggable scrubber + 30s FF/RW + LIVE pill.
            // Shown as soon as the receiver reports a rewind buffer via EITHER the
            // getState echo's canSeek or the ~1Hz position tick, so the buttons
            // never wait a tick to appear; the draggable scrubber needs the tick's
            // window, so it renders once position data arrives.
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
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
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
                if (canChangeChannel) {
                    RemoteButton(Icons.Filled.KeyboardArrowDown, "Channel down", onChannelDown)
                }
                RemoteButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    desc = if (isPlaying) "Pause" else "Play",
                    onClick = onTogglePlayPause,
                    emphasized = true,
                )
                if (canChangeChannel) {
                    RemoteButton(Icons.Filled.KeyboardArrowUp, "Channel up", onChannelUp)
                }
                Spacer(Modifier.width(6.dp))
                RemoteButton(Icons.Filled.Tune, "Options", {
                    onRefreshState()
                    optionsOpen = true
                })
                RemoteButton(Icons.Filled.Close, stopLabel, onStopCasting)
            }
            Spacer(Modifier.height(18.dp))
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
                    text = "Options",
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
                // Companion only: the X above stops the TV, this one just lets go
                // of the remote. Both hide the card.
                onDisconnect?.let { disconnect ->
                    OptionRow(Icons.Filled.LinkOff, "Disconnect", "Leaves the TV playing") {
                        optionsOpen = false
                        disconnect()
                    }
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
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val size = if (emphasized) 60.dp else 48.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (emphasized) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = if (emphasized) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(if (emphasized) 30.dp else 24.dp),
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
    icon: ImageVector,
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
