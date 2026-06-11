package com.aeriotv.android.feature.multiview

import android.content.res.Configuration
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.R
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aeriotv.android.feature.player.AudioTracksSheet
import com.aeriotv.android.feature.player.SubtitlesSheet
import com.aeriotv.android.feature.player.readAudioTracks
import com.aeriotv.android.feature.player.readCurrentAid
import com.aeriotv.android.feature.player.readCurrentSid
import com.aeriotv.android.feature.player.readSubtitleTracks
import com.aeriotv.android.feature.player.selectAudioTrack
import com.aeriotv.android.feature.player.selectSubtitleTrack
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.settings.bufferMillisFor

private const val TAG = "MultiviewScreen"

/**
 * Multiview tile grid. Mirrors iOS MultiviewContainerView (Aerio/Features/
 * Multiview/MultiviewContainerView.swift) — one MPVPlayerView per selected
 * channel, only the audio-focused tile plays sound (others get aid=no), tap a
 * tile to swap audio focus.
 *
 * Phase 11b ships the 1/2/4/9-tile shape. 3/5/6/7/8 reuse the same column
 * grid logic with empty tail tiles. Audio-focus visual indicator (the
 * configurable centerIcon / grayPersistent / themeFading modes from iOS
 * MultiviewAudioFocusStyle) is the default centerIcon variant only;
 * Phase 11c surfaces the Settings toggle and other modes.
 */
@Composable
fun MultiviewScreen(
    onClose: () -> Unit,
    httpHeaders: Map<String, String> = emptyMap(),
    storeHandle: MultiviewStoreHandle = rememberMultiviewStoreHandle(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    // Keep the screen on while multiview is active. Same reason as
    // PlayerScreen -- watching 2-9 live streams without the screen
    // sleeping mid-grid. iOS parity via IdleTimerRefCount.
    com.aeriotv.android.feature.player.KeepScreenOnWhilePlaying()

    val isTvDevice = (
        LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK
        ) == Configuration.UI_MODE_TYPE_TELEVISION

    val selected by storeHandle.selected.collectAsState()
    val focused by storeHandle.audioFocusedIndex.collectAsState()
    val bufferSize by settingsVm.streamBufferSize.collectAsState(initial = "default")
    val audioFocusStyle by settingsVm.multiviewAudioFocusStyle.collectAsState(initial = "centerIcon")
    val tilePadding by settingsVm.multiviewTilePadding.collectAsState(initial = false)
    val tileRounded by settingsVm.multiviewTileCornersRounded.collectAsState(initial = false)

    var chromeVisible by remember { mutableStateOf(true) }
    // Bumped whenever the user navigates between tiles (D-pad focus change)
    // so the auto-hide timer re-arms instead of fading the channel names
    // mid-navigation. Mirrors PlayerScreen's lastInteractionAt pattern.
    var lastInteractionAt by remember { mutableStateOf(0L) }
    // Long-press a tile -> relocate mode. The next tap swaps positions with
    // the relocating tile. Tap the same tile again (or the close X) to cancel.
    var relocatingIndex by remember { mutableStateOf<Int?>(null) }
    // Double-tap a tile -> fullscreen that single tile (other tiles hidden +
    // their MPV instances paused). Double-tap the same tile or the close X
    // to exit. Mirrors iOS MultiviewStore.fullscreenTileID --
    // architecture spec section F: "Full-screen mode hides all but
    // fullscreenTileID."
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
    // Spotlight (tvOS MultiviewStore.spotlightTileID): the spotlit tile takes
    // the left 2/3 of the viewport at full height; the others stack in the
    // right 1/3 column in list order, all still PLAYING (no pause). Keyed by
    // channel id (iOS keys by tile id) so swaps/moves never re-aim it; the
    // index is derived below with a contains-guard, mirroring iOS.
    var spotlightId by remember { mutableStateOf<String?>(null) }
    // Long-OK on a tile (TV) opens the tile context menu, the Android port of
    // tvOS MultiviewTileView.tileContextMenu. Gated to N > 1 like tvOS
    // (no actions at all on a single tile).
    var tileMenuIndex by remember { mutableStateOf<Int?>(null) }
    val tileMenuGuard = rememberTvMenuGuard()
    // Per-tile track sheets (indices into `selected`). Set from the tile menu;
    // the sheet reads/writes THAT tile's hoisted ExoPlayer.
    var subtitleTileIndex by remember { mutableStateOf<Int?>(null) }
    var audioTrackTileIndex by remember { mutableStateOf<Int?>(null) }
    // Hoisted per-tile player handles, keyed by tile POSITION (the tiles are
    // positional: a removal shifts channels through them, but each ExoTile
    // instance keeps its index for life). Registered by ExoTile's factory,
    // unregistered in onRelease.
    val tilePlayers = remember { mutableStateMapOf<Int, ExoPlayer>() }
    // For the themeFading mode: track the last time audio focus changed so the
    // accent border can auto-hide after 5s. Resets when the user taps a new tile.
    var focusActivityAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(focused) { focusActivityAt = System.currentTimeMillis() }
    var focusFadedOut by remember { mutableStateOf(false) }
    LaunchedEffect(focusActivityAt) {
        focusFadedOut = false
        kotlinx.coroutines.delay(5_000L)
        focusFadedOut = true
    }

    // User report (v0.1.6, Amlogic S905X4): "there is an X near the top left
    // but no matter what I press on the remote, it doesn't let me exit
    // Multiview." The grid host is the single focusable and consumes every
    // D-pad arrow for tile navigation, so the touch-only X is never reachable
    // by a remote -- leaving no exit. BACK is the Android-TV exit convention;
    // wire it explicitly with the same cascade the X uses (cancel relocate ->
    // exit fullscreen -> leave multiview) so a sub-mode is backed out of
    // first instead of dumping the user straight to the guide.
    // TV BACK opens a "Leave Multiview?" choice instead of exiting outright:
    // the @Singleton MultiviewStore survives the pop, so "Back to TV Guide"
    // resumes later via the guide's staged banner while "Exit Multiview"
    // clears the staged set. `enabled = !exitDialogOpen` hands BACK to the
    // Dialog window while it is up (same pattern as the guide exit dialog).
    var exitDialogOpen by remember { mutableStateOf(false) }
    val exitGuard = rememberTvMenuGuard()
    BackHandler(enabled = !exitDialogOpen) {
        when {
            relocatingIndex != null -> relocatingIndex = null
            fullscreenIndex != null -> fullscreenIndex = null
            isTvDevice && selected.isNotEmpty() -> {
                exitDialogOpen = true
                exitGuard.arm()
            }
            else -> onClose()
        }
    }

    // Derived spotlight index (null when the spotlit channel is no longer
    // tiled -- the contains-guard iOS applies to spotlightTileID).
    val spotlightIndex = selected.indexOfFirst { it.id == spotlightId }.takeIf { it >= 0 }
    // If the tile count shrinks under an open per-tile sheet, drop the stale
    // index so the sheet cannot re-attach to a future tile at that position.
    LaunchedEffect(selected.size) {
        if ((subtitleTileIndex ?: -1) >= selected.size) subtitleTileIndex = null
        if ((audioTrackTileIndex ?: -1) >= selected.size) audioTrackTileIndex = null
        if ((tileMenuIndex ?: -1) >= selected.size) tileMenuIndex = null
    }

    if (selected.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No tiles selected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CloseButton(onClose = onClose)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { chromeVisible = !chromeVisible },
    ) {
        TileGrid(
            tiles = selected,
            focusedIndex = focused,
            relocatingIndex = relocatingIndex,
            fullscreenIndex = fullscreenIndex,
            spotlightIndex = spotlightIndex,
            httpHeaders = httpHeaders,
            cachingMs = bufferMillisFor(bufferSize),
            audioFocusStyle = audioFocusStyle,
            tilePadding = tilePadding,
            tileRounded = tileRounded,
            chromeVisible = chromeVisible,
            focusFadedOut = focusFadedOut,
            onTileFocused = {
                // D-pad moved onto a tile: re-show the channel names + count
                // chip and re-arm the auto-hide so they stay up while the
                // user is actively navigating the grid.
                chromeVisible = true
                lastInteractionAt = android.os.SystemClock.uptimeMillis()
            },
            onTileTap = { idx ->
                val r = relocatingIndex
                if (r != null && r != idx) {
                    storeHandle.swap(r, idx)
                    relocatingIndex = null
                } else if (r == idx) {
                    relocatingIndex = null
                } else {
                    storeHandle.setAudioFocus(idx)
                }
            },
            onTileLongPress = { idx ->
                // Long-press picks up the tile (drag-to-reorder start, or the
                // tap-to-swap fallback if released in place). No-op in
                // fullscreen mode -- nothing else on screen to reorder against.
                if (fullscreenIndex != null) return@TileGrid
                relocatingIndex = idx
            },
            onReorder = { from, to ->
                // Committed on drag-drop. Insert semantics (move, not swap) to
                // match iOS drag-reorder. Clears the pickup highlight.
                storeHandle.move(from, to)
                relocatingIndex = null
            },
            onTileDoubleTap = { idx ->
                // Toggle fullscreen for this tile. Audio focus rides along
                // so the fullscreened tile is the one playing sound.
                fullscreenIndex = if (fullscreenIndex == idx) null else idx
                if (fullscreenIndex != null) {
                    storeHandle.setAudioFocus(idx)
                    relocatingIndex = null
                }
            },
            onTileMenu = { idx ->
                // tvOS parity (MultiviewTileView line 355): no context menu
                // at N=1. Suppressed mid-move so a held OK can't pop the
                // menu over the relocate machinery.
                if (relocatingIndex == null && selected.size > 1) {
                    tileMenuIndex = idx
                    tileMenuGuard.arm()
                }
            },
            onRelocateStep = { from, to ->
                // Move Tile (tvOS parity): each arrow swaps the relocating
                // tile with its neighbor immediately; swaps commit as they
                // happen, BACK/OK merely finishes.
                storeHandle.swap(from, to)
                relocatingIndex = to
            },
            onTilePlayer = { idx, player ->
                if (player != null) tilePlayers[idx] = player else tilePlayers.remove(idx)
            },
        )

        if (chromeVisible) {
            CloseButton(onClose = {
                // Close X cascades through transient modes before fully
                // exiting multiview: cancel relocate -> exit fullscreen ->
                // exit multiview. Mirrors iOS Menu-button cascade.
                when {
                    relocatingIndex != null -> relocatingIndex = null
                    fullscreenIndex != null -> fullscreenIndex = null
                    else -> onClose()
                }
            })
            val countLabel = when {
                // On TV the tile gestures are D-pad: arrows move, OK picks
                // audio, BACK exits. Surface that so a remote user isn't
                // hunting for the touch-only X.
                relocatingIndex != null -> if (isTvDevice) "Move Tile · arrows to move · OK or Back to finish" else "Tap a tile to swap"
                fullscreenIndex != null -> if (isTvDevice) "Back to exit fullscreen" else "Double-tap to exit fullscreen"
                isTvDevice -> "${selected.size} / ${storeHandle.maxTiles} · Back to exit"
                else -> "${selected.size} / ${storeHandle.maxTiles}"
            }
            val labelHighlighted = relocatingIndex != null || fullscreenIndex != null
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (labelHighlighted)
                    MaterialTheme.colorScheme.primary
                else
                    Color.White.copy(alpha = 0.85f),
                fontWeight = if (labelHighlighted) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 18.dp, top = 18.dp),
            )
        }
    }

    if (exitDialogOpen) {
        TvActionMenuDialog(
            title = "Leave Multiview?",
            actions = listOf(
                TvMenuAction(
                    label = "Back to TV Guide",
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    onClick = { onClose() },
                ),
                TvMenuAction(
                    label = "Exit Multiview",
                    icon = Icons.Outlined.Close,
                    destructive = true,
                    // onClose() BEFORE clear() so the recompose never lands
                    // on the "No tiles selected." placeholder for a frame.
                    onClick = {
                        onClose()
                        storeHandle.clear()
                    },
                ),
            ),
            guard = exitGuard,
            onDismiss = { exitDialogOpen = false },
        )
    }

    // Tile context menu: the Android port of tvOS MultiviewTileView.
    // tileContextMenu (MultiviewTileView.swift 428-496). Same action order:
    // Make Audio (hidden on the audio tile), Full-Screen in Grid, Spotlight,
    // Audio Track (audio tile only, >1 track), Subtitle Track (only when
    // tracks exist), Move Tile, Remove (destructive). Opened by long-OK on a
    // tile; only reachable from the grid (the key handler is gated off in
    // fullscreen), so no "Exit Full-Screen" row is needed -- BACK exits.
    val menuIdx = tileMenuIndex
    val menuChannel = menuIdx?.let { selected.getOrNull(it) }
    if (menuIdx != null && menuChannel != null) {
        val isSpotlit = spotlightId == menuChannel.id
        // Track lists evaluated at menu-open, the same way tvOS hides the
        // rows when the tile reports nothing.
        val menuPlayer = tilePlayers[menuIdx]
        val menuSubtitleTracks = remember(menuIdx, menuChannel.id) {
            menuPlayer?.readSubtitleTracks().orEmpty()
        }
        val menuAudioTracks = remember(menuIdx, menuChannel.id) {
            // Selecting audio on a non-focused tile is moot (its audio track
            // type is disabled by the budget gate), so only the audio tile
            // offers the row.
            if (menuIdx == focused) menuPlayer?.readAudioTracks().orEmpty() else emptyList()
        }
        TvActionMenuDialog(
            title = menuChannel.name,
            actions = buildList {
                if (menuIdx != focused) {
                    add(
                        TvMenuAction(
                            label = "Make Audio",
                            icon = Icons.Filled.VolumeUp,
                            onClick = { storeHandle.setAudioFocus(menuIdx) },
                        ),
                    )
                }
                add(
                    TvMenuAction(
                        label = "Full-Screen in Grid",
                        icon = Icons.Filled.Fullscreen,
                        onClick = {
                            // First D-pad entry point to the existing mode
                            // (touch enters via double-tap). Audio rides
                            // along, matching the double-tap path; BACK exits.
                            fullscreenIndex = menuIdx
                            storeHandle.setAudioFocus(menuIdx)
                            relocatingIndex = null
                        },
                    ),
                )
                add(
                    TvMenuAction(
                        label = if (isSpotlit) "Remove Spotlight" else "Spotlight",
                        icon = Icons.Filled.ViewSidebar,
                        onClick = {
                            if (isSpotlit) {
                                spotlightId = null
                            } else {
                                spotlightId = menuChannel.id
                                // Mutually exclusive with fullscreen, tvOS
                                // parity (MultiviewTileView 452-455).
                                fullscreenIndex = null
                            }
                        },
                    ),
                )
                if (menuAudioTracks.size > 1) {
                    add(
                        TvMenuAction(
                            label = "Audio Track",
                            icon = Icons.Filled.Audiotrack,
                            onClick = { audioTrackTileIndex = menuIdx },
                        ),
                    )
                }
                if (menuSubtitleTracks.isNotEmpty()) {
                    add(
                        TvMenuAction(
                            label = "Subtitle Track",
                            icon = Icons.Filled.Subtitles,
                            onClick = { subtitleTileIndex = menuIdx },
                        ),
                    )
                }
                add(
                    TvMenuAction(
                        label = "Move Tile",
                        icon = Icons.Filled.OpenWith,
                        onClick = {
                            relocatingIndex = menuIdx
                            chromeVisible = true
                        },
                    ),
                )
                add(
                    TvMenuAction(
                        label = "Remove",
                        icon = Icons.Outlined.Close,
                        destructive = true,
                        onClick = {
                            // iOS MultiviewStore.remove(id:) semantics live in
                            // removeAt (audio promotes to the newest remaining
                            // tile). Spotlight/relocate that pointed at the
                            // removed tile are cleared here.
                            val removingLast = selected.size <= 1
                            if (spotlightId == menuChannel.id) spotlightId = null
                            relocatingIndex = null
                            storeHandle.removeAt(menuIdx)
                            if (removingLast) onClose()
                        },
                    ),
                )
            },
            guard = tileMenuGuard,
            onDismiss = { tileMenuIndex = null },
        )
    }

    // Per-tile Subtitle Track sheet. selectSubtitleTrack only writes
    // TEXT-type overrides, so it cannot disturb the critical audio-budget
    // gate, and ExoTile.update's buildUpon() preserves it across
    // recompositions. The tile PlayerView renders captions via its built-in
    // SubtitleView.
    val subtitlePlayer = subtitleTileIndex?.let { tilePlayers[it] }
    if (subtitlePlayer != null) {
        SubtitlesSheet(
            tracks = subtitlePlayer.readSubtitleTracks(),
            currentTrackId = subtitlePlayer.readCurrentSid(),
            onSelect = { sid ->
                subtitlePlayer.selectSubtitleTrack(sid)
                subtitleTileIndex = null
            },
            onDismiss = { subtitleTileIndex = null },
        )
    }
    // Per-tile Audio Track sheet (audio-focused tile only; see the menu row).
    val audioTrackPlayer = audioTrackTileIndex?.let { tilePlayers[it] }
    if (audioTrackPlayer != null) {
        AudioTracksSheet(
            tracks = audioTrackPlayer.readAudioTracks(),
            currentTrackId = audioTrackPlayer.readCurrentAid(),
            onSelect = { aid ->
                audioTrackPlayer.selectAudioTrack(aid)
                audioTrackTileIndex = null
            },
            onDismiss = { audioTrackTileIndex = null },
        )
    }

    // Auto-hide stands down while a tile is being moved so the instruction
    // label cannot fade mid-move; the key restarts the timer when the move
    // finishes.
    LaunchedEffect(chromeVisible, lastInteractionAt, relocatingIndex) {
        if (chromeVisible && relocatingIndex == null) {
            kotlinx.coroutines.delay(4_000L)
            chromeVisible = false
        }
    }

    DisposableEffect(Unit) { onDispose { /* per-tile views own their lifecycle */ } }
}

@Composable
private fun BoxScope.CloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(start = 14.dp, top = 14.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Exit multiview",
                tint = Color.White,
            )
        }
    }
}

/**
 * Compute (rows, cols) for an N-tile grid. iOS reference values:
 *   1 -> 1x1, 2 -> 1x2, 3 -> 2x2 (one empty),
 *   4 -> 2x2, 5/6 -> 2x3, 7/8/9 -> 3x3.
 */
private fun gridShapeFor(count: Int): Pair<Int, Int> = when {
    count <= 1 -> 1 to 1
    count == 2 -> 1 to 2
    count <= 4 -> 2 to 2
    count <= 6 -> 2 to 3
    else -> 3 to 3
}

@Composable
private fun TileGrid(
    tiles: List<M3UChannel>,
    focusedIndex: Int,
    relocatingIndex: Int?,
    fullscreenIndex: Int?,
    spotlightIndex: Int?,
    httpHeaders: Map<String, String>,
    cachingMs: Int,
    audioFocusStyle: String,
    tilePadding: Boolean,
    tileRounded: Boolean,
    chromeVisible: Boolean,
    focusFadedOut: Boolean,
    onTileFocused: () -> Unit,
    onTileTap: (Int) -> Unit,
    onTileLongPress: (Int) -> Unit,
    onTileDoubleTap: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onTileMenu: (Int) -> Unit,
    onRelocateStep: (Int, Int) -> Unit,
    onTilePlayer: (Int, ExoPlayer?) -> Unit,
) {
    val isTv = (
        LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK
        ) == Configuration.UI_MODE_TYPE_TELEVISION

    // D-pad navigation index (TV only). The tiles are placed with
    // Modifier.offset for the persistent-composition architecture, which
    // breaks Compose's spatial focus search across them (offset siblings
    // don't yield a clean left/right/up/down graph). So we own focus
    // ourselves: a single focusable host on the grid captures the D-pad
    // keys and moves this index through the rows x cols arithmetic, and
    // each tile draws its ring from `index == dpadIndex`. OK/Center acts
    // on the selected tile.
    var dpadIndex by remember(tiles.size) {
        mutableStateOf(focusedIndex.coerceIn(0, (tiles.size - 1).coerceAtLeast(0)))
    }
    val gridFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (isTv) runCatching { gridFocusRequester.requestFocus() }
    }
    // Re-arm the chrome (channel names + count chip) on every navigation.
    LaunchedEffect(dpadIndex) { if (isTv) onTileFocused() }
    // tvOS parity (MultiviewTileView.shouldPause): "fullscreen a tile" only
    // changes which tile is drawn full size -- NO tile is ever unmounted or
    // destroyed. Every tile's MPVPlayerView stays in the composition so its
    // libmpv handle survives; the focused tile fills the viewport while the
    // others stay mounted (just parked off-screen) with their mpv PAUSED to
    // save decode/GPU/network. Exiting fullscreen un-pauses them and they
    // resume instantly -- no factory rebuild, no reload, no black flash. (The
    // old code early-returned with only the focused Tile composed, which
    // dropped every other AndroidView and fired its onRelease { destroy() }.)
    val (rows, cols) = gridShapeFor(tiles.size)
    val pad = if (tilePadding) 4.dp else 0.dp
    val anyFullscreen = fullscreenIndex != null

    // Drag-to-reorder state. The MPV tiles are positional (never moved); a
    // long-press picks a tile up and we only commit the reorder on drop, so no
    // SurfaceView is relocated mid-drag (avoids the black-flash the in-place
    // swap architecture is built to prevent). dragPos is an absolute pixel
    // coordinate within the grid; it maps to the hovered cell for the
    // drop-target highlight + the final move.
    var dragSource by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    // Long-OK latch for the tile context menu: set when KeyDown auto-repeat
    // fires the menu so the eventual KeyUp does not ALSO fire the tap.
    var centerLongFired by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Single focusable host for the whole grid (TV). It captures
            // the D-pad and we move dpadIndex by hand; returning true
            // consumes the event so Compose's own focus search never
            // pulls focus into an individual (offset-placed) tile, which
            // is what made the ring vanish on first navigation.
            .focusRequester(gridFocusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (!isTv || anyFullscreen) return@onKeyEvent false
                // OK/Center is handled on BOTH edges: KeyDown auto-repeat
                // (repeatCount >= 1, ~500ms in, the same signal TvMenuGuard's
                // docs describe) opens the tile context menu once; KeyUp
                // without the long-latch is the plain tap. The non-KeyDown
                // gate therefore lives below, after this branch.
                if (ev.key == Key.DirectionCenter || ev.key == Key.Enter) {
                    when (ev.type) {
                        KeyEventType.KeyDown -> {
                            if (ev.nativeKeyEvent.repeatCount == 0) {
                                centerLongFired = false
                            } else if (!centerLongFired) {
                                centerLongFired = true
                                onTileMenu(dpadIndex)
                            }
                        }
                        KeyEventType.KeyUp -> {
                            if (!centerLongFired) onTileTap(dpadIndex)
                            centerLongFired = false
                        }
                        else -> Unit
                    }
                    return@onKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                // One neighbor topology for both cursor navigation and the
                // Move Tile stepping. Spotlight layout (left 2/3 tile + right
                // stack) gets its own graph: on a small tile Left jumps to the
                // spotlight and Up/Down step the stack; on the spotlight tile
                // Right enters the top of the stack.
                fun neighborOf(from: Int, key: Key): Int? {
                    if (spotlightIndex != null && tiles.size > 1) {
                        val others = tiles.indices.filter { it != spotlightIndex }
                        if (from == spotlightIndex) {
                            return if (key == Key.DirectionRight) others.firstOrNull() else null
                        }
                        val k = others.indexOf(from)
                        return when (key) {
                            Key.DirectionLeft -> spotlightIndex
                            Key.DirectionUp -> others.getOrNull(k - 1)
                            Key.DirectionDown -> others.getOrNull(k + 1)
                            else -> null
                        }
                    }
                    val (gr, gc) = gridShapeFor(tiles.size)
                    val r = from / gc
                    val c = from % gc
                    return when (key) {
                        Key.DirectionRight ->
                            (from + 1).takeIf { c + 1 < gc && it < tiles.size }
                        Key.DirectionLeft ->
                            (from - 1).takeIf { c > 0 }
                        Key.DirectionDown ->
                            (from + gc).takeIf { r + 1 < gr && it < tiles.size }
                        Key.DirectionUp ->
                            (from - gc).takeIf { r > 0 }
                        else -> null
                    }
                }
                when (ev.key) {
                    Key.DirectionRight, Key.DirectionLeft,
                    Key.DirectionDown, Key.DirectionUp -> {
                        val reloc = relocatingIndex
                        if (reloc != null) {
                            // Move Tile mode (tvOS MultiviewContainerView
                            // .onMoveCommand): each arrow swaps the relocating
                            // tile with its neighbor immediately; the cursor
                            // stays pinned on the moving tile.
                            val n = neighborOf(reloc, ev.key)
                            if (n != null) {
                                onRelocateStep(reloc, n)
                                dpadIndex = n
                            }
                            // Consume even at an edge so the cursor cannot
                            // escape mid-move.
                            true
                        } else {
                            val n = neighborOf(dpadIndex, ev.key)
                            if (n != null) { dpadIndex = n; true } else false
                        }
                    }
                    else -> false
                }
            },
    ) {
        val gridW = maxWidth
        val gridH = maxHeight
        val cellWDp = gridW / cols
        val cellHDp = gridH / rows
        val cellW = (constraints.maxWidth.toFloat() / cols).coerceAtLeast(1f)
        val cellH = (constraints.maxHeight.toFloat() / rows).coerceAtLeast(1f)
        fun cellIndexAt(pos: Offset): Int {
            val c = (pos.x / cellW).toInt().coerceIn(0, cols - 1)
            val r = (pos.y / cellH).toInt().coerceIn(0, rows - 1)
            return (r * cols + c).coerceIn(0, tiles.size - 1)
        }
        val hoverIndex = dragSource?.let { cellIndexAt(dragPos) }

        // Every selected tile is placed absolutely (NOT via a Column/Row that
        // would drop tiles from the composition), so toggling fullscreen never
        // releases an AndroidView / destroys an mpv handle. Normal mode tiles
        // the grid; fullscreen promotes the focused tile to the whole viewport
        // (zIndex on top) and parks the rest just off the right edge, paused.
        val spotIdx = spotlightIndex
        tiles.forEachIndexed { index, channel ->
            val isFull = fullscreenIndex == index
            val col = index % cols
            val row = index / cols
            // Per-tile rect. Fullscreen branch stays OUTERMOST (fullscreen
            // overrides spotlight visually, matching iOS render priority).
            // Spotlight mirrors iOS MultiviewGridMath.spotlightRects: the
            // spotlit tile takes the left 2/3 at full height; the remaining
            // N-1 tiles stack equally in the right 1/3 column in list order.
            // The spotlight toggle deliberately does NOT animate (iOS snaps;
            // snapping also avoids TextureView resize churn).
            val tileX: Dp
            val tileY: Dp
            val tileW: Dp
            val tileH: Dp
            when {
                isFull -> {
                    tileX = 0.dp; tileY = 0.dp
                    tileW = gridW; tileH = gridH
                }
                anyFullscreen -> {
                    tileX = gridW + cellWDp * col // parked off-screen (right)
                    tileY = cellHDp * row
                    tileW = cellWDp; tileH = cellHDp
                }
                spotIdx != null && tiles.size > 1 -> {
                    val bigW = gridW * (2f / 3f)
                    if (index == spotIdx) {
                        tileX = 0.dp; tileY = 0.dp
                        tileW = bigW; tileH = gridH
                    } else {
                        val k = if (index < spotIdx) index else index - 1
                        val smallH = gridH / (tiles.size - 1)
                        tileX = bigW; tileY = smallH * k
                        tileW = gridW - bigW; tileH = smallH
                    }
                }
                else -> {
                    tileX = cellWDp * col
                    tileY = cellHDp * row
                    tileW = cellWDp; tileH = cellHDp
                }
            }
            Box(
                modifier = Modifier
                    .offset(x = tileX, y = tileY)
                    .size(width = tileW, height = tileH)
                    .zIndex(if (isFull) 1f else 0f)
                    .padding(pad)
                    .then(
                        if (!anyFullscreen) {
                            Modifier.pointerInput(index, cols, rows, tiles.size, cellW, cellH) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        dragSource = index
                                        dragPos = Offset(col * cellW + offset.x, row * cellH + offset.y)
                                        onTileLongPress(index)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragPos += amount
                                    },
                                    onDragEnd = {
                                        val from = dragSource
                                        if (from != null) {
                                            val to = cellIndexAt(dragPos)
                                            if (to != from) onReorder(from, to)
                                        }
                                        dragSource = null
                                    },
                                    onDragCancel = { dragSource = null },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Tile(
                    channel = channel,
                    isAudioFocused = index == focusedIndex,
                    isRelocating = index == relocatingIndex || index == dragSource,
                    isDropTarget = hoverIndex == index && dragSource != index,
                    paused = anyFullscreen && !isFull,
                    httpHeaders = httpHeaders,
                    cachingMs = cachingMs,
                    audioFocusStyle = audioFocusStyle,
                    tileRounded = if (isFull) false else tileRounded,
                    chromeVisible = chromeVisible,
                    focusFadedOut = focusFadedOut,
                    // D-pad selection ring (TV only): the tile the remote is
                    // currently on. Suppressed in fullscreen (single tile).
                    isDpadFocused = isTv && !anyFullscreen && index == dpadIndex,
                    onTap = { onTileTap(index) },
                    onDoubleTap = { onTileDoubleTap(index) },
                    onPlayer = { player -> onTilePlayer(index, player) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    channel: M3UChannel,
    isAudioFocused: Boolean,
    isRelocating: Boolean,
    isDropTarget: Boolean,
    paused: Boolean,
    httpHeaders: Map<String, String>,
    cachingMs: Int,
    audioFocusStyle: String,
    tileRounded: Boolean,
    chromeVisible: Boolean,
    focusFadedOut: Boolean,
    isDpadFocused: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onPlayer: (ExoPlayer?) -> Unit,
    // Same activity-scoped Settings VM the screen already uses; the tile
    // only needs the passthrough flag at player-build time.
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val audioPassthrough by settingsVm.audioPassthroughEnabled
        .collectAsState(initial = false)
    val shape = if (tileRounded) RoundedCornerShape(8.dp) else RoundedCornerShape(0.dp)
    // D-pad focus (which tile the remote is currently on) is owned by the
    // grid host and passed in as [isDpadFocused]. Distinct from AUDIO focus
    // (which tile is playing sound): the user moves the ring around the grid
    // and presses OK to promote the ringed tile to audio focus.
    //
    // The bright white ring FADES OUT with the chrome (4s after the last D-pad
    // move; onTileFocused re-arms `chromeVisible`) instead of persisting, so a
    // resting grid isn't dominated by a hard white box. The persistent
    // "which tile is active" cue is the audio-focus border configured in
    // Settings > Multiview (Gray Outline / themeFading), which is unaffected.
    val dpadFocused = isDpadFocused && chromeVisible
    // Resolve the AUDIO-focus indicator for this tile. iOS canon:
    //   centerIcon: speaker icon fades with the chrome
    //   grayPersistent: muted gray border always around the active tile
    //   themeFading: cyan border that auto-hides after 5s of inactivity
    val showCenterIcon = isAudioFocused && audioFocusStyle == "centerIcon" && chromeVisible
    // Border priority: transient drag states first, then the D-pad focus
    // ring (bright, always visible while navigating), then the audio-focus
    // border styles, then the resting hairline. The D-pad ring deliberately
    // outranks the audio-focus border so the navigation cursor is never
    // ambiguous; the speaker icon still distinguishes the audio tile.
    val targetBorderColor = when {
        isDropTarget -> MaterialTheme.colorScheme.tertiary
        isRelocating -> MaterialTheme.colorScheme.primary
        dpadFocused -> Color.White
        isAudioFocused && audioFocusStyle == "grayPersistent" ->
            Color.White.copy(alpha = 0.5f)
        isAudioFocused && audioFocusStyle == "themeFading" && !focusFadedOut ->
            MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = 0.08f)
    }
    val targetBorderWidth = when {
        isDropTarget -> 4.dp
        dpadFocused -> 4.dp
        isRelocating -> 3.dp
        isAudioFocused && (audioFocusStyle == "grayPersistent" ||
                (audioFocusStyle == "themeFading" && !focusFadedOut)) -> 2.dp
        else -> 1.dp
    }
    // Animate the border so the white D-pad ring EASES out (and back in) with
    // the chrome rather than popping. 350ms matches the chrome cross-fade feel.
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = androidx.compose.animation.core.tween(350),
        label = "tileBorderColor",
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = targetBorderWidth,
        animationSpec = androidx.compose.animation.core.tween(350),
        label = "tileBorderWidth",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .combinedClickable(
                onClick = onTap,
                onDoubleClick = onDoubleTap,
            ),
    ) {
        ExoTile(
            url = channel.url,
            channelName = channel.name,
            httpHeaders = httpHeaders,
            cachingMs = cachingMs,
            isAudioFocused = isAudioFocused,
            paused = paused,
            onPlayer = onPlayer,
            audioPassthrough = audioPassthrough,
        )
        // Channel-name overlay (top-left). Fades with the chrome: it's up
        // on launch + whenever the user interacts (tap toggles chrome,
        // D-pad navigation re-arms it) and auto-hides after 4s so the tile
        // is an unobstructed video cell at rest. AnimatedVisibility gives
        // a soft cross-fade rather than a hard pop.
        androidx.compose.animation.AnimatedVisibility(
            visible = chromeVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // Audio-focus indicator. centerIcon mode shows the speaker icon over
        // the active tile while the chrome is visible. grayPersistent and
        // themeFading are border-only — handled above on the outer Box.
        if (showCenterIcon) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
    }
}

/**
 * Per-tile Media3 ExoPlayer + PlayerView. Direct counterpart of the
 * libmpv tile we deleted (task #63).
 *
 * Lifetime: factory builds one ExoPlayer per tile. update() handles
 * channel swap (setMediaItem on the same player -- no rebuild),
 * audio-focus changes (volume), and paused flag (playWhenReady).
 * onRelease releases the player.
 *
 * Audio strategy: libmpv used `mute` because `aid=no` didn't survive
 * the async file-load. Media3 exposes a direct `volume` (0f..1f) on
 * Player that applies immediately and persists across setMediaItem,
 * so the two-knob libmpv dance collapses to one knob here.
 *
 * Resource budget: each tile is one ExoPlayer = one MediaCodec
 * instance + one AudioRenderer. The Streamer (MediaTek, modest 32-bit
 * SoC) caps at ~8 concurrent MediaCodec instances; the existing
 * Multiview UI already exposes N=1..9 tiles. If we hit
 * MediaCodec.CONFIGURE_ERROR / INSUFFICIENT_RESOURCE we surface
 * the failure via the player's Listener; the existing N-aware audio
 * strategy + thermal/soft-limit/OOM guards (task #35) still apply.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ExoTile(
    url: String,
    channelName: String,
    httpHeaders: Map<String, String>,
    cachingMs: Int,
    isAudioFocused: Boolean,
    paused: Boolean,
    // Hoists the tile's player handle to the screen (per-tile track sheets).
    // Called with the player once from factory, with null from onRelease.
    onPlayer: (ExoPlayer?) -> Unit,
    // Dolby passthrough pref at player-build time (see aerioRenderersFactory).
    audioPassthrough: Boolean,
) {
    val currentUrlRef = remember { mutableStateOf("") }
    val playerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Live-stream tuning. minBuffer honors the Settings stream-buffer
            // choice with a 5s floor; the old 500ms resume threshold made a
            // tile that dipped stall again immediately once N tiles shared
            // bandwidth (periodic brief freezes).
            val minBufferMs = maxOf(5_000, cachingMs)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBufferMs, maxOf(15_000, minBufferMs), 1_000, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
            if (httpHeaders.isNotEmpty()) {
                dataSourceFactory.setDefaultRequestProperties(httpHeaders)
                httpHeaders.entries
                    .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                    ?.value
                    ?.let(dataSourceFactory::setUserAgent)
            }
            val renderersFactory =
                com.aeriotv.android.core.playback.aerioRenderersFactory(ctx, audioPassthrough)
            // Route raw .ts / /proxy/ts via TsExtractor like the Live
            // TV holder; HLS via HlsMediaSource; everything else via
            // the default factory. Same routing logic as
            // AerioExoPlayerHolder.buildMediaSource. The factory is
            // constructed per-prepare in buildTileMediaSource below
            // rather than being passed as a top-level MediaSource.Factory,
            // because we route by URL shape at call time.
            val player = ExoPlayer.Builder(ctx)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setHandleAudioBecomingNoisy(false) // tile is muted-by-default; don't react
                // Event-driven playback loop instead of the fixed 10ms
                // doSomeWork cadence; up to 9 busy-scheduling loops are
                // measurable on a TV SoC. Tiles only, not the main holder.
                .experimentalSetDynamicSchedulingEnabled(true)
                .build()
                .apply {
                    // CRITICAL multiview audio-budget gate. Each tile is a
                    // separate ExoPlayer; if every tile decodes audio, the
                    // device's audio HAL runs out of AudioTrack sinks and
                    // throws "Audio sink error" -> ExoPlaybackException,
                    // which is FATAL to that tile's player (it stops and the
                    // surface goes black). On the Streamer, 4 concurrent
                    // AC-3 5.1 decoders is already over the limit.
                    //
                    // Setting volume=0 is NOT enough -- it mutes output but
                    // still allocates the decoder + sink. We must disable the
                    // audio TRACK entirely so the renderer never selects it
                    // and no sink is created. This is the Media3 equivalent
                    // of the libmpv `aid=no` knob the original multiview used
                    // (the migration wrongly collapsed aid+mute to volume).
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !isAudioFocused)
                        .build()
                    volume = 1f
                    playWhenReady = !paused
                }
            playerRef.value = player
            onPlayer(player)

            // Bounded re-prepare on fatal error, copying the cooldown shape
            // of AerioExoPlayerHolder.forceReload (5s cooldown, 3 attempts,
            // counter reset on recovery). Without it a tile hitting an HTTP
            // error or MediaCodec INSUFFICIENT_RESOURCE freezes silently for
            // the rest of the session.
            player.addListener(object : Player.Listener {
                private var lastRetryAtMs = 0L
                private var consecutiveRetries = 0

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) consecutiveRetries = 0
                }

                override fun onPlayerError(error: PlaybackException) {
                    val retryUrl = currentUrlRef.value
                    if (retryUrl.isBlank()) return
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastRetryAtMs < 5_000L) return
                    if (consecutiveRetries >= 3) {
                        Log.w(TAG, "Tile giving up after $consecutiveRetries retries: $channelName (${error.errorCodeName})")
                        return
                    }
                    lastRetryAtMs = now
                    consecutiveRetries++
                    Log.w(TAG, "Tile re-prepare on error: $channelName ${error.errorCodeName} attempt=$consecutiveRetries")
                    player.setMediaSource(buildTileMediaSource(retryUrl, dataSourceFactory))
                    player.prepare()
                }
            })

            Log.i(TAG, "Tile ExoPlayer loading: $channelName")
            if (url.isNotBlank()) {
                player.setMediaSource(buildTileMediaSource(url, dataSourceFactory))
                player.prepare()
                currentUrlRef.value = url
            }

            // Inflated (not PlayerView(ctx)) because surface_type is a
            // constructor-time XML attr; the layout pins it to texture_view
            // so N tiles share the app window layer instead of N
            // punch-through SurfaceViews (TV HWC overlay-budget stutter).
            val playerView = LayoutInflater.from(ctx)
                .inflate(R.layout.multiview_tile_player, null) as PlayerView
            playerView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setPlayer(player)
            }
        },
        update = { _ ->
            val player = playerRef.value ?: return@AndroidView
            // In-place stream swap: the positional Tile sees a new
            // channel (via MultiviewStore.swap or replaceTile). Don't
            // teardown -- hand the new URL to the same player.
            if (url.isNotBlank() && currentUrlRef.value != url) {
                Log.i(TAG, "Tile ExoPlayer swap: ${currentUrlRef.value} -> $url")
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                if (httpHeaders.isNotEmpty()) {
                    dataSourceFactory.setDefaultRequestProperties(httpHeaders)
                }
                player.setMediaSource(buildTileMediaSource(url, dataSourceFactory))
                player.prepare()
                currentUrlRef.value = url
            }
            // Flip audio on focus change. Disabling the track releases the
            // AC-3 decoder + AudioTrack sink for non-focused tiles; enabling
            // it on the newly-focused tile re-selects audio. Single-knob
            // (track enable/disable) keeps exactly one audio sink alive
            // across the whole grid, which is what the audio HAL can sustain.
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !isAudioFocused)
                .build()
            player.playWhenReady = !paused
        },
        onRelease = { _ ->
            Log.i(TAG, "Tile ExoPlayer releasing: $channelName")
            onPlayer(null)
            playerRef.value?.release()
            playerRef.value = null
        },
    )
}

@OptIn(UnstableApi::class)
private fun buildTileMediaSource(
    url: String,
    dataSourceFactory: androidx.media3.datasource.DataSource.Factory,
): androidx.media3.exoplayer.source.MediaSource {
    val mediaItem = MediaItem.fromUri(url)
    return when {
        url.endsWith(".m3u8", ignoreCase = true) ->
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        url.endsWith(".ts", ignoreCase = true) ||
            url.contains("/proxy/ts/", ignoreCase = true) -> {
            val extractors = DefaultExtractorsFactory()
                .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
                .createMediaSource(mediaItem)
        }
        else -> DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
    }
}
