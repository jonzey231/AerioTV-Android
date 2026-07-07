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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timeline
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
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
    watchVm: com.aeriotv.android.feature.watchprogress.WatchProgressViewModel = hiltViewModel(),
    // The PLAYLIST_GRAPH-scoped PlaylistViewModel (Navigation.kt hoists the same
    // instance via hiltViewModel(parent)). Forwarded to the re-entrant
    // AddToMultiviewSheet below so the "Add streams" picker reuses the single
    // graph-attached VM instead of spinning up a 2nd, graph-detached one.
    playlistVm: com.aeriotv.android.feature.playlist.PlaylistViewModel = hiltViewModel(),
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
    // Issue #48: the selectable, persisted grid layout (Default / Even Grid /
    // Spotlight / Hero + Corner). Picked from the tile context menu; one global
    // value for all tile counts, mirroring iOS @AppStorage multiviewLayoutMode.
    val layoutModeKey by settingsVm.multiviewLayoutMode.collectAsState(initial = "auto")
    // Spotlight is a per-tile action only (see MultiviewLayoutMode.available); it is
    // no longer a grid-wide shape. Coerce any legacy persisted "spotlight" value to
    // Default so it can't leave a stuck grid-wide spotlight at counts where the
    // layout picker no longer appears. Per-tile spotlight still works via spotlightId.
    val layoutMode = MultiviewLayoutMode.from(layoutModeKey)
        .let { if (it == MultiviewLayoutMode.Spotlight) MultiviewLayoutMode.Auto else it }

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
    // VOD tiles that have reached end-of-file (Phase 3 Finished overlay). Keyed
    // by tile POSITION (same as tilePlayers). The overlay renders the checkmark
    // + title + Replay/Remove only on a Vod tile in this set. iOS parity:
    // MultiviewTile.isFinished + the "Finished" card.
    var finishedTiles by remember { mutableStateOf(setOf<Int>()) }
    // VOD/DVR tiles whose per-tile scrubber overlay is toggled ON (Item #16).
    // Keyed by tile POSITION, same as tilePlayers/finishedTiles. The overlay
    // reads tilePlayers[idx].currentPosition and seeks that same player. LIVE
    // tiles never enter this set (the menu row is kind-gated below).
    var scrubberTiles by remember { mutableStateOf(setOf<Int>()) }
    // EOF handler. iOS reassignAudioIfFinishedTileWasAudio: when the audio tile
    // finishes, audio promotes to the newest remaining tile (the removeAt
    // newest-tile rule) so sound never goes silent on the grid.
    val onTileFinished: (Int) -> Unit = { idx ->
        finishedTiles = finishedTiles + idx
        if (idx == focused && selected.size > 1) {
            val newest = selected.indices.last { it != idx }
            storeHandle.setAudioFocus(newest)
        }
    }
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
    // "Add streams" reopens the Add-to-Multiview picker OVER the live grid.
    // The picker writes through the same @Singleton MultiviewStore the grid
    // reads (toggle / addTile APPEND, never clear), so newly picked tiles grow
    // the existing grid and BACK out of the picker keeps everything.
    var addPickerOpen by remember { mutableStateOf(false) }
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
    // iOS MultiviewStore.remove(id:) clears spotlightTileID on removal, so a
    // re-added channel never inherits an old spotlight. Android removals can
    // also happen OUTSIDE this screen's remove paths (the re-entrant picker's
    // toggle, the memory-pressure shed), so reconcile against the list itself:
    // once the spotlit id is gone, drop it for good instead of leaving it
    // latent to silently re-engage when the same channel is re-added.
    LaunchedEffect(selected) {
        if (spotlightId != null && selected.none { it.id == spotlightId }) spotlightId = null
    }
    // If the tile count shrinks under an open per-tile sheet, drop the stale
    // index so the sheet cannot re-attach to a future tile at that position.
    LaunchedEffect(selected.size) {
        if ((subtitleTileIndex ?: -1) >= selected.size) subtitleTileIndex = null
        if ((audioTrackTileIndex ?: -1) >= selected.size) audioTrackTileIndex = null
        if ((tileMenuIndex ?: -1) >= selected.size) tileMenuIndex = null
        // Store-side removals (picker toggle, memory shed) bypass removeTileAt:
        // if fullscreen points past the end, clear it so the grid can never
        // wedge in the everything-parked-off-screen state.
        if ((fullscreenIndex ?: -1) >= selected.size) fullscreenIndex = null
        // Drop Finished overlays for tile positions that no longer exist (a
        // removal shifted the grid), so a stale checkmark never paints over a
        // different tile that slid into the slot.
        finishedTiles = finishedTiles.filter { it < selected.size }.toSet()
        scrubberTiles = scrubberTiles.filter { it < selected.size }.toSet()
    }

    // Single removal path for both screen-level removers (the tile menu's
    // Remove and the Finished overlay's Remove). The tiles are POSITIONAL:
    // removing index i slides every higher channel down one slot, so all
    // position-keyed state must slide with it or it re-attaches to whichever
    // tile lands in the slot. iOS removes by stable tile id
    // (MultiviewStore.remove(id:), which also clears fullscreen/spotlight),
    // so this reconciliation is the positional-Android equivalent.
    val removeTileAt: (Int) -> Unit = { idx ->
        if (spotlightId == selected.getOrNull(idx)?.id) spotlightId = null
        relocatingIndex = null
        // Clear fullscreen when the fullscreen tile itself is removed (iOS
        // MultiviewStore.swift remove(id:)); shift it down when a lower tile
        // is removed so the SAME tile stays fullscreen. Without this, the
        // stale index either promotes the wrong tile or -- when the removed
        // tile was last -- parks every tile off-screen (black, silent grid).
        fullscreenIndex = fullscreenIndex?.let { f ->
            when {
                f == idx -> null
                f > idx -> f - 1
                else -> f
            }
        }
        fun shift(set: Set<Int>): Set<Int> = set.mapNotNull {
            when {
                it == idx -> null
                it > idx -> it - 1
                else -> it
            }
        }.toSet()
        finishedTiles = shift(finishedTiles)
        scrubberTiles = shift(scrubberTiles)
        storeHandle.removeAt(idx)
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
            // TV Back is claimed HERE (ancestor of the focused grid host), so it
            // fires before both the grid host's onKeyEvent AND MainActivity's
            // onKeyDown/onKeyLongPress -- neutralizing the 0.3.0 held-Back
            // "Close AerioTV?" hijack that shadowed the multiview exit. Consuming
            // BOTH edges keeps the Activity from ever starting long-press
            // tracking, so exit is press-duration-independent. Deferred while a
            // per-tile scrubber is open (scrubberTiles): that overlay owns Back
            // itself (Key.Back -> hide scrubber). The exit dialog / tile menu /
            // Add-streams picker / track sheets each run in their OWN window, so
            // this main-window handler never fires while one of them is up.
            .onPreviewKeyEvent { ev ->
                if (!isTvDevice || ev.key != Key.Back || scrubberTiles.isNotEmpty()) {
                    return@onPreviewKeyEvent false
                }
                if (ev.type == KeyEventType.KeyUp) {
                    when {
                        relocatingIndex != null -> relocatingIndex = null
                        fullscreenIndex != null -> fullscreenIndex = null
                        selected.isNotEmpty() -> { exitDialogOpen = true; exitGuard.arm() }
                        else -> onClose()
                    }
                }
                true
            }
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
            layoutMode = layoutMode,
            httpHeaders = httpHeaders,
            cachingMs = bufferMillisFor(bufferSize),
            audioFocusStyle = audioFocusStyle,
            tilePadding = tilePadding,
            tileRounded = tileRounded,
            chromeVisible = chromeVisible,
            focusFadedOut = focusFadedOut,
            watchVm = watchVm,
            finishedTiles = finishedTiles,
            scrubberTiles = scrubberTiles,
            tilePlayers = tilePlayers,
            onHideScrubber = { idx -> scrubberTiles = scrubberTiles - idx },
            onTileFinished = onTileFinished,
            onReplayTile = { idx ->
                finishedTiles = finishedTiles - idx
                tilePlayers[idx]?.let { p -> p.seekTo(0L); p.playWhenReady = true }
            },
            onRemoveTile = removeTileAt,
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
                // fullscreen mode -- nothing else on screen to reorder against
                // -- and at N=1 (iOS gates relocate behind the N>1 menu; a
                // sole tile has nothing to swap with, so picking it up only
                // shows a stuck "Tap a tile to swap" and eats a BACK).
                if (fullscreenIndex != null || selected.size <= 1) return@TileGrid
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
                // so the fullscreened tile is the one playing sound. Gated on
                // N>1 like iOS (MultiviewTileView "Gate on N>1: at N=1
                // there's nothing to zoom into"): a sole tile already fills
                // the screen, so latching the mode would only hide the "+"
                // button and eat the next BACK press invisibly.
                if (fullscreenIndex == null && selected.size <= 1) return@TileGrid
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
            // Item #7 phone parity: TV reaches "Add streams" through the BACK
            // exit dialog, but on phone BACK exits outright (no dialog), so the
            // re-entrant picker was unreachable. Surface a small touch "+" in
            // the chrome (phone only) when not mid-relocate/fullscreen and below
            // the tile cap. It opens the SAME picker the TV menu does; the store
            // appends, so the live grid grows in place.
            // Tablet parity: the selectable grid layouts live inside the per-tile
            // context menu, which is TV-only (opened via D-pad long-OK). Touch
            // long-press maps to relocate, so tablets had no way to reach it.
            // Surface a layout button on large-screen non-TV devices; it opens the
            // same tile menu (grid-wide "Layout:" rows appear there at 3/5/6 tiles).
            if (!isTvDevice && LocalConfiguration.current.screenWidthDp >= 600 &&
                relocatingIndex == null && fullscreenIndex == null && selected.size > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(end = 14.dp, top = 94.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = {
                        tileMenuIndex = focused.coerceIn(0, selected.size - 1)
                        tileMenuGuard.arm()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.GridView,
                            contentDescription = "Grid layout",
                            tint = Color.White,
                        )
                    }
                }
            }
            if (!isTvDevice && relocatingIndex == null && fullscreenIndex == null &&
                selected.size < storeHandle.maxTiles) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(end = 14.dp, top = 44.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { addPickerOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add streams",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }

    if (exitDialogOpen) {
        TvActionMenuDialog(
            title = "Leave Multiview?",
            actions = listOf(
                TvMenuAction(
                    label = "Add streams",
                    icon = Icons.Filled.Add,
                    // Reopen the picker over the running grid. Existing tiles
                    // stay in the store and keep playing; the picker APPENDS.
                    onClick = { addPickerOpen = true },
                ),
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

    // Re-entrant Add-to-Multiview picker (from the menu's "Add streams").
    // currentChannel = null: there is no single "now playing" stream to seed
    // or pin here, and nothing must be excluded -- the grid already holds the
    // tiles. All dismissal paths just close the picker; the store keeps the
    // appended tiles (no restore/clear), so the grid grows in place.
    if (addPickerOpen) {
        AddToMultiviewSheet(
            currentChannel = null,
            multiviewStore = storeHandle,
            // Reuse the screen's graph-scoped PlaylistViewModel rather than
            // hiltViewModel()'s fresh, graph-detached instance, matching
            // Navigation.kt's hiltViewModel(parent) wiring.
            playlistVm = playlistVm,
            onLaunch = { addPickerOpen = false },
            onCancel = { addPickerOpen = false },
            onDismiss = { addPickerOpen = false },
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
    val menuTile = menuIdx?.let { selected.getOrNull(it) }
    if (menuIdx != null && menuTile != null) {
        val isSpotlit = spotlightId == menuTile.id
        // Track lists evaluated at menu-open, the same way tvOS hides the
        // rows when the tile reports nothing.
        val menuPlayer = tilePlayers[menuIdx]
        val menuSubtitleTracks = remember(menuIdx, menuTile.id) {
            menuPlayer?.readSubtitleTracks().orEmpty()
        }
        val menuAudioTracks = remember(menuIdx, menuTile.id) {
            // Selecting audio on a non-focused tile is moot (its audio track
            // type is disabled by the budget gate), so only the audio tile
            // offers the row.
            if (menuIdx == focused) menuPlayer?.readAudioTracks().orEmpty() else emptyList()
        }
        TvActionMenuDialog(
            title = menuTile.displayName,
            actions = buildList {
                // Select (long-OK) opens this menu, so "Add streams" must live
                // here too -- it previously existed ONLY behind the Back exit
                // dialog. Same re-entrant picker; the store APPENDS.
                if (selected.size < storeHandle.maxTiles) {
                    add(
                        TvMenuAction(
                            label = "Add streams",
                            icon = Icons.Filled.Add,
                            onClick = { addPickerOpen = true },
                        ),
                    )
                }
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
                        label = if (isSpotlit) "Remove Spotlight" else "Spotlight This Tile",
                        icon = Icons.Filled.ViewSidebar,
                        onClick = {
                            if (isSpotlit) {
                                spotlightId = null
                            } else {
                                spotlightId = menuTile.id
                                // Mutually exclusive with fullscreen, tvOS
                                // parity (MultiviewTileView 452-455).
                                fullscreenIndex = null
                            }
                        },
                    ),
                )
                // Issue #48: grid SHAPE picker. Grid-wide (any tile sets the
                // shared, persisted layout); only appears where a real alternative
                // shape exists (3/5 Even Grid, 6 Hero + Corner). Spotlight is not a
                // shape here -- it is the per-tile "Spotlight This Tile" action
                // above. The active shape shows a checkmark; picking any shape
                // clears the per-tile spotlight so a leftover hero can't override
                // the chosen shape and make the switch a no-op.
                val availableModes = MultiviewLayoutMode.available(selected.size)
                // Checkmark = the layout actually being RENDERED. Hero + Corner
                // away from 6 tiles falls back to the Default table, so mark
                // Default active there. Even Grid renders at any count, so when it
                // is persisted but not offered (e.g. grown to 7 tiles) NO row is
                // checkmarked -- never mislabeling Default while a non-default grid
                // is on screen.
                val effectiveMode =
                    if (layoutMode == MultiviewLayoutMode.HeroCorner &&
                        layoutMode !in availableModes
                    ) MultiviewLayoutMode.Auto else layoutMode
                availableModes.forEach { mode ->
                    val activeMode = effectiveMode == mode
                    add(
                        TvMenuAction(
                            label = "Layout: ${mode.displayName}",
                            icon = if (activeMode) Icons.Filled.Check else mode.menuIcon(),
                            onClick = {
                                settingsVm.setMultiviewLayoutMode(mode.key)
                                spotlightId = null
                            },
                        ),
                    )
                }
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
                // Item #16: scrubber toggle, VOD/DVR only (Live has no finite
                // duration). Toggles this tile's position in scrubberTiles;
                // the overlay (sibling of Tile in TileGrid) reads/seeks the
                // already-hoisted tilePlayers[menuIdx].
                if (menuTile.kind == TileKind.Vod || menuTile.kind == TileKind.Dvr) {
                    val scrubOn = menuIdx in scrubberTiles
                    add(
                        TvMenuAction(
                            label = if (scrubOn) "Hide Scrubber" else "Show Scrubber",
                            icon = Icons.Filled.Timeline,
                            onClick = {
                                scrubberTiles = if (scrubOn) scrubberTiles - menuIdx
                                else scrubberTiles + menuIdx
                            },
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
                            // iOS MultiviewStore.remove(id:) semantics: audio
                            // promotes in removeAt; ALL position-keyed screen
                            // state (spotlight, relocate, fullscreen, overlay
                            // sets) reconciles in removeTileAt.
                            val removingLast = selected.size <= 1
                            removeTileAt(menuIdx)
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

/** Menu icon for each layout mode (the active mode shows a checkmark instead). */
private fun MultiviewLayoutMode.menuIcon(): androidx.compose.ui.graphics.vector.ImageVector =
    when (this) {
        MultiviewLayoutMode.Auto -> Icons.Filled.GridView
        MultiviewLayoutMode.EvenGrid -> Icons.Filled.Apps
        MultiviewLayoutMode.Spotlight -> Icons.Filled.ViewSidebar
        MultiviewLayoutMode.HeroCorner -> Icons.Filled.Dashboard
    }

@Composable
private fun TileGrid(
    tiles: List<MultiviewTile>,
    focusedIndex: Int,
    relocatingIndex: Int?,
    fullscreenIndex: Int?,
    spotlightIndex: Int?,
    layoutMode: MultiviewLayoutMode,
    httpHeaders: Map<String, String>,
    cachingMs: Int,
    audioFocusStyle: String,
    tilePadding: Boolean,
    tileRounded: Boolean,
    chromeVisible: Boolean,
    focusFadedOut: Boolean,
    watchVm: com.aeriotv.android.feature.watchprogress.WatchProgressViewModel,
    finishedTiles: Set<Int>,
    scrubberTiles: Set<Int>,
    tilePlayers: Map<Int, ExoPlayer>,
    onHideScrubber: (Int) -> Unit,
    onTileFinished: (Int) -> Unit,
    onReplayTile: (Int) -> Unit,
    onRemoveTile: (Int) -> Unit,
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
    // Item #16 focus-trap fix: while a per-tile scrubber strip is up it owns
    // focus; once every scrubber is hidden/removed, pull focus back to the grid
    // host so the remote drives tile nav again instead of landing nowhere.
    LaunchedEffect(scrubberTiles.isEmpty()) {
        if (isTv && scrubberTiles.isEmpty()) runCatching { gridFocusRequester.requestFocus() }
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
    // D-pad neighbor topology runs against a fixed 16:9 reference so it is
    // size-independent (iOS physicalNeighbor uses a 1920x1080 default). Unlike
    // iOS it uses the ACTUAL displayed rects (mode + spotlight applied) so
    // navigation always tracks what is on screen.
    val neighborRects = MultiviewGridMath.resolvedRects(
        layoutMode, tiles.size, spotlightIndex, 1920f, 1080f, 0f,
    )
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
                // One neighbor topology for both cursor navigation and the Move
                // Tile stepping, computed geometrically from the ACTUAL layout
                // rects (any mode + spotlight). Nearest edge-adjacent tile with
                // perpendicular overlap wins, so every mode -- even grid,
                // spotlight, hero-corner, the holes in layout5/7/8 -- gets a
                // correct graph without a per-mode special case.
                fun neighborOf(from: Int, key: Key): Int? {
                    val dir = when (key) {
                        Key.DirectionLeft -> NeighborDirection.Left
                        Key.DirectionRight -> NeighborDirection.Right
                        Key.DirectionUp -> NeighborDirection.Up
                        Key.DirectionDown -> NeighborDirection.Down
                        else -> return null
                    }
                    return MultiviewGridMath.neighbor(from, neighborRects, dir)
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
        // Per-tile rects for THIS container -- Dp for on-screen placement, px
        // for pointer hit-testing (drag-to-reorder). Both from the same layout
        // math (mode + spotlight reconciled); spacing 0 because the inter-tile
        // gap is the per-tile .padding below.
        val dpRects = MultiviewGridMath.resolvedRects(
            layoutMode, tiles.size, spotlightIndex, gridW.value, gridH.value, 0f,
        )
        val pxRects = MultiviewGridMath.resolvedRects(
            layoutMode, tiles.size, spotlightIndex,
            constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), 0f,
        )
        val hoverIndex = dragSource?.let { MultiviewGridMath.indexAt(dragPos, pxRects) }

        // Every selected tile is placed absolutely (NOT via a Column/Row that
        // would drop tiles from the composition), so toggling fullscreen never
        // releases an AndroidView / destroys an mpv handle. Normal mode tiles
        // the grid; fullscreen promotes the focused tile to the whole viewport
        // (zIndex on top) and parks the rest just off the right edge, paused.
        tiles.forEachIndexed { index, tile ->
            val isFull = fullscreenIndex == index
            // This tile's rect in the current layout (mode + spotlight already
            // reconciled into dpRects). Fullscreen branch stays OUTERMOST
            // (fullscreen overrides the layout visually, matching iOS render
            // priority); the layout transition deliberately does NOT animate
            // (iOS snaps; snapping also avoids TextureView resize churn).
            val dr = dpRects.getOrNull(index)
            val drX = (dr?.left ?: 0f).dp
            val drY = (dr?.top ?: 0f).dp
            val drW = (dr?.width ?: 0f).dp
            val drH = (dr?.height ?: 0f).dp
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
                    // Parked just off the right edge (paused) at its slot size.
                    tileX = gridW + drX
                    tileY = drY
                    tileW = drW; tileH = drH
                }
                else -> {
                    tileX = drX
                    tileY = drY
                    tileW = drW; tileH = drH
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
                            Modifier.pointerInput(index, tiles.size, pxRects.getOrNull(index)) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        dragSource = index
                                        val pr = pxRects.getOrNull(index)
                                        dragPos = Offset(
                                            (pr?.left ?: 0f) + offset.x,
                                            (pr?.top ?: 0f) + offset.y,
                                        )
                                        onTileLongPress(index)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragPos += amount
                                    },
                                    onDragEnd = {
                                        val from = dragSource
                                        if (from != null) {
                                            val to = MultiviewGridMath.indexAt(dragPos, pxRects)
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
                    tile = tile,
                    isAudioFocused = index == focusedIndex,
                    isRelocating = index == relocatingIndex || index == dragSource,
                    isDropTarget = hoverIndex == index && dragSource != index,
                    paused = anyFullscreen && !isFull,
                    gridHeaders = httpHeaders,
                    cachingMs = cachingMs,
                    audioFocusStyle = audioFocusStyle,
                    tileRounded = if (isFull) false else tileRounded,
                    chromeVisible = chromeVisible,
                    focusFadedOut = focusFadedOut,
                    watchVm = watchVm,
                    // D-pad selection ring (TV only): the tile the remote is
                    // currently on. Suppressed in fullscreen (single tile).
                    isDpadFocused = isTv && !anyFullscreen && index == dpadIndex,
                    onTap = { onTileTap(index) },
                    onDoubleTap = { onTileDoubleTap(index) },
                    onPlayer = { player -> onTilePlayer(index, player) },
                    onFinished = { onTileFinished(index) },
                )
                // Finished overlay (Phase 3). Sibling of the Tile so its
                // Replay / Remove targets sit ON TOP of the video and take real
                // taps / focus. iOS parity: the "Finished" card with a
                // checkmark, the title, and Replay + Remove.
                if (index in finishedTiles && tile.kind == TileKind.Vod) {
                    TileFinishedOverlay(
                        title = tile.displayName,
                        onReplay = { onReplayTile(index) },
                        onRemove = { onRemoveTile(index) },
                    )
                }
                // Item #16: per-tile scrubber overlay. VOD/DVR only (the menu
                // row that fills scrubberTiles is itself kind-gated; the extra
                // guard here is belt-and-suspenders against a stale index after
                // a swap). Reads/seeks the hoisted player for THIS position.
                if (index in scrubberTiles &&
                    (tile.kind == TileKind.Vod || tile.kind == TileKind.Dvr)) {
                    TileScrubberOverlay(
                        player = tilePlayers[index],
                        isTv = isTv,
                        isDvr = tile.kind == TileKind.Dvr,
                        // Item #16 focus-trap fix: the strip must be able to hand
                        // the D-pad back to the single grid host (which owns ALL
                        // nav + the long-OK tile menu where "Hide Scrubber"
                        // lives). UP/DOWN/BACK return focus; BACK also hides the
                        // scrubber so a remote can never get pinned on the strip.
                        gridFocusRequester = gridFocusRequester,
                        onHideScrubber = { onHideScrubber(index) },
                    )
                }
            }
        }
    }
}

/**
 * Black scrim shown over a VOD tile that reached end-of-file. Mirrors iOS's
 * "Finished" card: a checkmark, the title, and Replay (restart this tile) +
 * Remove (drop it from the grid). Rendered as a sibling of the tile's video so
 * the buttons receive real taps / D-pad focus.
 */
@Composable
private fun BoxScope.TileFinishedOverlay(
    title: String,
    onReplay: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.82f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Finished",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onReplay)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Replay",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Remove",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    tile: MultiviewTile,
    isAudioFocused: Boolean,
    isRelocating: Boolean,
    isDropTarget: Boolean,
    paused: Boolean,
    // Grid-level Dispatcharr auth headers; live tiles use these, VOD/DVR tiles
    // use their own per-tile headers (see ExoTile).
    gridHeaders: Map<String, String>,
    cachingMs: Int,
    audioFocusStyle: String,
    tileRounded: Boolean,
    chromeVisible: Boolean,
    focusFadedOut: Boolean,
    watchVm: com.aeriotv.android.feature.watchprogress.WatchProgressViewModel,
    isDpadFocused: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onPlayer: (ExoPlayer?) -> Unit,
    onFinished: () -> Unit,
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
            tile = tile,
            gridHeaders = gridHeaders,
            cachingMs = cachingMs,
            isAudioFocused = isAudioFocused,
            paused = paused,
            onPlayer = onPlayer,
            audioPassthrough = audioPassthrough,
            watchVm = watchVm,
            onFinished = onFinished,
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
                    text = tile.displayName,
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
    tile: MultiviewTile,
    // Grid-level headers used by LIVE tiles; VOD/DVR tiles carry their own.
    gridHeaders: Map<String, String>,
    cachingMs: Int,
    isAudioFocused: Boolean,
    paused: Boolean,
    // Hoists the tile's player handle to the screen (per-tile track sheets).
    // Called with the player once from factory, with null from onRelease.
    onPlayer: (ExoPlayer?) -> Unit,
    // Dolby passthrough pref at player-build time (see aerioRenderersFactory).
    audioPassthrough: Boolean,
    // Phase 3: VOD-tile periodic save (same store as the single VOD player).
    watchVm: com.aeriotv.android.feature.watchprogress.WatchProgressViewModel,
    // Phase 3: fired once a VOD tile reaches end-of-file.
    onFinished: () -> Unit,
) {
    val url = tile.resolvedUrl
    val channelName = tile.displayName
    // Live tiles use the grid's headers; VOD/DVR carry their own per-tile auth.
    val headers = if (tile.kind == TileKind.Live) gridHeaders else tile.httpHeaders
    val isVod = tile.kind == TileKind.Vod
    val currentUrlRef = remember { mutableStateOf("") }
    val playerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    // Once-only resume seek guard (first STATE_READY for a VOD tile).
    val didResumeRef = remember(tile.id) { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // LoadControl by kind. LIVE keeps the 5s-floor live control (a
            // tile that dipped used to stall again immediately once N tiles
            // shared bandwidth). VOD/DVR use the single VOD player's shape
            // (VODPlayerScreen.kt: 15s/50s/2s/5s) for smoother seeking and
            // fewer rebuffers across a long title.
            val loadControl = if (isVod) {
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 2_000, 5_000)
                    .build()
            } else {
                val minBufferMs = maxOf(5_000, cachingMs)
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(minBufferMs, maxOf(15_000, minBufferMs), 1_000, 3_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            }
            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                // Send a real player UA (parity with live path); some panels
                // reject the platform "Dalvik/..." default.
                .setUserAgent("AerioTV/${com.aeriotv.android.BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})")
            if (headers.isNotEmpty()) {
                httpFactory.setDefaultRequestProperties(headers)
                headers.entries
                    .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                    ?.value
                    ?.let(httpFactory::setUserAgent)
            }
            // VOD/DVR: wrap the header-aware HTTP factory in
            // DefaultDataSource.Factory so a completed-recording file:// URL
            // resolves through FileDataSource (a bare HTTP factory cannot open
            // file://, cf VODPlayerScreen.kt). LIVE keeps the bare HTTP factory
            // + TsExtractor routing.
            val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
                if (isVod || tile.kind == TileKind.Dvr) {
                    DefaultDataSource.Factory(ctx, httpFactory)
                } else {
                    httpFactory
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
            // the rest of the session. STATE_READY (VOD) also drives the
            // one-shot resume seek; STATE_ENDED on a VOD tile fires onFinished.
            player.addListener(object : Player.Listener {
                private var lastRetryAtMs = 0L
                private var consecutiveRetries = 0

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        consecutiveRetries = 0
                        // Resume from the pre-resolved position (Phase 2 picker
                        // looked it up via WatchProgressDao), once per tile.
                        if (isVod && !didResumeRef.value) {
                            didResumeRef.value = true
                            val resume = tile.resumePositionMs ?: 0L
                            if (resume > 0L) player.seekTo(resume)
                        }
                    } else if (playbackState == Player.STATE_ENDED && isVod) {
                        onFinished()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val retryUrl = currentUrlRef.value
                    if (retryUrl.isBlank()) return
                    // A format this device's decoders can NEVER play (wrong
                    // codec/profile, or beyond the SoC's capabilities) fails
                    // identically on every re-prepare, so skip the bounded retry
                    // and surface the dead tile immediately instead of burning
                    // 3 attempts x 5s of futile codec init. Decoder-INIT failures
                    // are deliberately NOT treated as fatal here: on a multiview
                    // grid they're usually transient hardware-decoder contention
                    // (another tile holds the codec), which the cooldown-gated
                    // retry below recovers once a tile frees a decoder.
                    if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                    ) {
                        Log.w(TAG, "Tile format unplayable on this device, not retrying: $channelName (${error.errorCodeName})")
                        return
                    }
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
        update = { view ->
            val player = playerRef.value ?: return@AndroidView
            // In-place stream swap: the positional Tile sees a new
            // channel (via MultiviewStore.swap or replaceTile). Don't
            // teardown -- hand the new URL to the same player.
            if (url.isNotBlank() && currentUrlRef.value != url) {
                Log.i(TAG, "Tile ExoPlayer swap: ${currentUrlRef.value} -> $url")
                val swapHttp = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                if (headers.isNotEmpty()) {
                    swapHttp.setDefaultRequestProperties(headers)
                }
                // Same file://-capable wrap as the factory path for VOD/DVR.
                val swapFactory: androidx.media3.datasource.DataSource.Factory =
                    if (isVod || tile.kind == TileKind.Dvr) {
                        DefaultDataSource.Factory(view.context, swapHttp)
                    } else {
                        swapHttp
                    }
                player.setMediaSource(buildTileMediaSource(url, swapFactory))
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

    // Periodic save for VOD tiles with a continue-watching id. Reuses the SAME
    // save() the single VOD player calls (VODPlayerScreen.kt), so the
    // 5-min-from-end isFinished heuristic + advanceUpNext fire for free.
    if (isVod && tile.vodId != null) {
        val player = playerRef.value
        LaunchedEffect(player, tile.id) {
            if (player == null) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                val pos = player.currentPosition
                val dur = player.duration.coerceAtLeast(0L)
                if (pos <= 0L || dur <= 0L) continue
                watchVm.save(
                    videoId = tile.vodId,
                    title = tile.displayName,
                    posterUrl = tile.posterUrl,
                    positionMs = pos,
                    durationMs = dur,
                    vodType = tile.vodType,
                    seriesId = tile.seriesId,
                    seasonNumber = tile.seasonNumber,
                    episodeNumber = tile.episodeNumber,
                )
            }
        }
    }
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

/**
 * Item #16: a thin per-tile transport overlay for VOD / DVR tiles. Pinned to
 * the bottom of the tile; reads [player].currentPosition on a 500ms tick and
 * seeks the same player. TV-D-pad-friendly: a focusable strip captures
 * LEFT/RIGHT to preview +/-10s and commits the seek 650ms after the last step
 * (the same debounced-commit shape as VODPlayerScreen). Touch users drag the
 * bar directly. No new player is created, the handle is the one ExoTile
 * already hoisted into tilePlayers.
 */
@OptIn(UnstableApi::class)
@Composable
private fun BoxScope.TileScrubberOverlay(
    player: ExoPlayer?,
    isTv: Boolean,
    isDvr: Boolean,
    // Item #16 focus-trap fix: the single grid host that owns ALL D-pad nav and
    // the long-OK tile menu (where "Hide Scrubber" lives). The strip hands focus
    // back to it on UP/DOWN/BACK so the remote is never pinned on the scrubber.
    gridFocusRequester: FocusRequester,
    // Hide this tile's scrubber (drops it from scrubberTiles). Called on BACK so
    // a remote escapes the strip without dumping the user into the exit dialog.
    onHideScrubber: () -> Unit,
) {
    if (player == null) return
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    // Pending D-pad scrub target (preview); committed by the debounce below.
    var scrubTargetMs by remember { mutableStateOf<Long?>(null) }
    val focusReq = remember { FocusRequester() }

    // Poll live position/duration every 500ms while the overlay is up.
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            kotlinx.coroutines.delay(500L)
        }
    }
    // Debounced D-pad seek commit (VODPlayerScreen parity, 650ms).
    LaunchedEffect(scrubTargetMs) {
        val target = scrubTargetMs ?: return@LaunchedEffect
        kotlinx.coroutines.delay(650L)
        player.seekTo(target)
        positionMs = target
        scrubTargetMs = null
    }
    // Park focus on the strip so the remote drives it as soon as it appears.
    LaunchedEffect(Unit) { if (isTv) runCatching { focusReq.requestFocus() } }

    val shown = scrubTargetMs ?: positionMs
    val fraction = if (durationMs > 0L)
        (shown.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val maxPos = if (isDvr && durationMs > 0L) (durationMs - 5_000L).coerceAtLeast(0L)
    else durationMs

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            // TV: focusable strip. LEFT/RIGHT preview +/-10s. OK commits a pending
            // seek; a plain OK with nothing pending BUBBLES to the grid host
            // (return false) so its long-OK opens the tile menu where "Hide
            // Scrubber" lives. UP/DOWN/BACK hand focus back to the grid host so
            // the remote is never trapped on the strip (Item #16 focus-trap fix);
            // BACK also hides the scrubber instead of hitting the exit dialog.
            .then(
                if (isTv) Modifier
                    .focusRequester(focusReq)
                    .focusable()
                    .onKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (ev.key) {
                            Key.DirectionRight -> {
                                val base = scrubTargetMs ?: positionMs
                                scrubTargetMs = (base + 10_000L)
                                    .coerceIn(0L, if (maxPos > 0L) maxPos else Long.MAX_VALUE)
                                true
                            }
                            Key.DirectionLeft -> {
                                val base = scrubTargetMs ?: positionMs
                                scrubTargetMs = (base - 10_000L).coerceAtLeast(0L)
                                true
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                // Leave the strip: hand the D-pad back to the grid
                                // host so the user can navigate / re-open the tile
                                // menu (and Hide Scrubber). Fixes the ring desync
                                // where UP/DOWN moved the ring but focus stayed.
                                runCatching { gridFocusRequester.requestFocus() }
                                true
                            }
                            Key.Back -> {
                                // Escape the strip without falling through to the
                                // MultiviewScreen exit dialog: hide this scrubber
                                // and return focus to the grid host.
                                onHideScrubber()
                                runCatching { gridFocusRequester.requestFocus() }
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                val t = scrubTargetMs
                                if (t != null) {
                                    player.seekTo(t); positionMs = t; scrubTargetMs = null
                                    true
                                } else {
                                    // No pending seek: bubble to the grid host so
                                    // its long-OK opens the tile menu.
                                    false
                                }
                            }
                            else -> false
                        }
                    }
                else Modifier,
            ),
    ) {
        // Thin progress bar. Touch: tap/drag to seek by fraction.
        androidx.compose.material3.LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .then(
                    if (!isTv) Modifier.pointerInput(durationMs) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {},
                            onDrag = { change, _ ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val f = (change.position.x / w).coerceIn(0f, 1f)
                                if (durationMs > 0L) {
                                    // Same DVR live-edge clamp as the D-pad path:
                                    // never seek past duration - 5s on a DVR tile.
                                    scrubTargetMs = (f * durationMs).toLong()
                                        .coerceIn(0L, if (maxPos > 0L) maxPos else durationMs)
                                }
                            },
                            onDragEnd = {
                                scrubTargetMs?.let { player.seekTo(it); positionMs = it; scrubTargetMs = null }
                            },
                            onDragCancel = { scrubTargetMs = null },
                        )
                    } else Modifier,
                ),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.25f),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${mvFormatTime(shown)} / ${mvFormatTime(durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/** Local copy of VODPlayerScreen.formatTime (that one is file-private). */
private fun mvFormatTime(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSecs = ms / 1000L
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
