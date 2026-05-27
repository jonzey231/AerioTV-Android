package com.aeriotv.android.feature.multiview

import android.util.Log
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.player.MPVPlayerView
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.settings.bufferMillisFor
import `is`.xyz.mpv.Utils

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

    val selected by storeHandle.selected.collectAsState()
    val focused by storeHandle.audioFocusedIndex.collectAsState()
    val bufferSize by settingsVm.streamBufferSize.collectAsState(initial = "default")
    val audioFocusStyle by settingsVm.multiviewAudioFocusStyle.collectAsState(initial = "centerIcon")
    val tilePadding by settingsVm.multiviewTilePadding.collectAsState(initial = false)
    val tileRounded by settingsVm.multiviewTileCornersRounded.collectAsState(initial = false)

    var chromeVisible by remember { mutableStateOf(true) }
    // Long-press a tile -> relocate mode. The next tap swaps positions with
    // the relocating tile. Tap the same tile again (or the close X) to cancel.
    var relocatingIndex by remember { mutableStateOf<Int?>(null) }
    // Double-tap a tile -> fullscreen that single tile (other tiles hidden +
    // their MPV instances paused). Double-tap the same tile or the close X
    // to exit. Mirrors iOS MultiviewStore.fullscreenTileID --
    // architecture spec section F: "Full-screen mode hides all but
    // fullscreenTileID."
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
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
            httpHeaders = httpHeaders,
            cachingMs = bufferMillisFor(bufferSize),
            audioFocusStyle = audioFocusStyle,
            tilePadding = tilePadding,
            tileRounded = tileRounded,
            chromeVisible = chromeVisible,
            focusFadedOut = focusFadedOut,
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
                relocatingIndex != null -> "Tap a tile to swap"
                fullscreenIndex != null -> "Double-tap to exit fullscreen"
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

    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
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
    httpHeaders: Map<String, String>,
    cachingMs: Int,
    audioFocusStyle: String,
    tilePadding: Boolean,
    tileRounded: Boolean,
    chromeVisible: Boolean,
    focusFadedOut: Boolean,
    onTileTap: (Int) -> Unit,
    onTileLongPress: (Int) -> Unit,
    onTileDoubleTap: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
) {
    // Fullscreen branch: render exactly the focused tile filling the whole
    // viewport. We deliberately skip the grid recomposition path so the
    // other tiles' MPV instances continue running in the background (audio
    // muted via storeHandle) -- exiting fullscreen returns to them
    // instantly. iOS does the same: fullscreenTileID just changes which
    // tile is rendered, not which tiles are loaded.
    fullscreenIndex?.let { idx ->
        val ch = tiles.getOrNull(idx)
        if (ch != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                Tile(
                    channel = ch,
                    isAudioFocused = true,
                    isRelocating = false,
                    isDropTarget = false,
                    httpHeaders = httpHeaders,
                    cachingMs = cachingMs,
                    audioFocusStyle = audioFocusStyle,
                    tileRounded = false,
                    chromeVisible = chromeVisible,
                    focusFadedOut = focusFadedOut,
                    onTap = { onTileTap(idx) },
                    onDoubleTap = { onTileDoubleTap(idx) },
                )
            }
            return
        }
    }

    val (rows, cols) = gridShapeFor(tiles.size)
    val pad = if (tilePadding) 4.dp else 0.dp

    // Drag-to-reorder state. The MPV tiles are positional (never moved); a
    // long-press picks a tile up and we only commit the reorder on drop, so no
    // SurfaceView is relocated mid-drag (avoids the black-flash the in-place
    // swap architecture is built to prevent). dragPos is an absolute pixel
    // coordinate within the grid; it maps to the hovered cell for the
    // drop-target highlight + the final move.
    var dragSource by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cellW = (constraints.maxWidth.toFloat() / cols).coerceAtLeast(1f)
        val cellH = (constraints.maxHeight.toFloat() / rows).coerceAtLeast(1f)
        fun cellIndexAt(pos: Offset): Int {
            val c = (pos.x / cellW).toInt().coerceIn(0, cols - 1)
            val r = (pos.y / cellH).toInt().coerceIn(0, rows - 1)
            return (r * cols + c).coerceIn(0, tiles.size - 1)
        }
        val hoverIndex = dragSource?.let { cellIndexAt(dragPos) }

        Column(modifier = Modifier.fillMaxSize()) {
            for (r in 0 until rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    for (c in 0 until cols) {
                        val index = r * cols + c
                        val channel = tiles.getOrNull(index)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .padding(pad)
                                .then(
                                    if (channel != null) {
                                        Modifier.pointerInput(index, cols, rows, tiles.size, cellW, cellH) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    val col0 = index % cols
                                                    val row0 = index / cols
                                                    dragSource = index
                                                    dragPos = Offset(col0 * cellW + offset.x, row0 * cellH + offset.y)
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
                            if (channel != null) {
                                Tile(
                                    channel = channel,
                                    isAudioFocused = index == focusedIndex,
                                    isRelocating = index == relocatingIndex || index == dragSource,
                                    isDropTarget = hoverIndex == index && dragSource != index,
                                    httpHeaders = httpHeaders,
                                    cachingMs = cachingMs,
                                    audioFocusStyle = audioFocusStyle,
                                    tileRounded = tileRounded,
                                    chromeVisible = chromeVisible,
                                    focusFadedOut = focusFadedOut,
                                    onTap = { onTileTap(index) },
                                    onDoubleTap = { onTileDoubleTap(index) },
                                )
                            }
                        }
                    }
                }
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
    httpHeaders: Map<String, String>,
    cachingMs: Int,
    audioFocusStyle: String,
    tileRounded: Boolean,
    chromeVisible: Boolean,
    focusFadedOut: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val shape = if (tileRounded) RoundedCornerShape(8.dp) else RoundedCornerShape(0.dp)
    // Resolve the focus indicator for this tile. iOS canon:
    //   centerIcon: speaker icon fades with the chrome
    //   grayPersistent: muted gray border always around the active tile
    //   themeFading: cyan border that auto-hides after 5s of inactivity
    val showCenterIcon = isAudioFocused && audioFocusStyle == "centerIcon" && chromeVisible
    val borderColor = when {
        isDropTarget -> MaterialTheme.colorScheme.tertiary
        isRelocating -> MaterialTheme.colorScheme.primary
        isAudioFocused && audioFocusStyle == "grayPersistent" ->
            Color.White.copy(alpha = 0.5f)
        isAudioFocused && audioFocusStyle == "themeFading" && !focusFadedOut ->
            MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = 0.08f)
    }
    val borderWidth = when {
        isDropTarget -> 4.dp
        isRelocating -> 3.dp
        isAudioFocused && (audioFocusStyle == "grayPersistent" ||
                (audioFocusStyle == "themeFading" && !focusFadedOut)) -> 2.dp
        else -> 1.dp
    }
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
        // Tracks the URL the held MPV instance is currently playing. Lets
        // `update` distinguish a channel-flip (URL changed) from an aid-only
        // recomposition, so we call playFile (libmpv loadfile, replace mode)
        // only when needed. Mirrors iOS swapStreamIfChanged + the in-place
        // tile-swap pattern from commits e627ca7 / b34fa82 / 8fb0d5a.
        val currentUrlRef = remember { mutableStateOf("") }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Utils.copyAssets(ctx)
                val configDir = ctx.filesDir.path
                val cacheDir = ctx.cacheDir.path
                val view = MPVPlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.isLive = true
                    this.caFilePath = "$configDir/cacert.pem"
                    this.httpHeaders = httpHeaders
                    this.cachingMs = cachingMs
                }
                view.initialize(configDir, cacheDir)
                Log.i(TAG, "Tile MPV loading: ${channel.name}")
                if (channel.url.isNotBlank()) {
                    view.playFile(channel.url)
                    currentUrlRef.value = channel.url
                }
                // Initial audio focus state. `mute` is the reliable gate: aid=no
                // set right after playFile doesn't survive the async file load
                // (mpv re-selects the default audio track on FILE_LOADED), which
                // let every tile play sound at once. mute applies immediately and
                // persists regardless of track-selection timing; aid still spares
                // the CPU for non-focused tiles once it sticks.
                view.mpv.setPropertyString("aid", if (isAudioFocused) "auto" else "no")
                view.mpv.setPropertyString("mute", if (isAudioFocused) "no" else "yes")
                view
            },
            update = { view ->
                // In-place stream swap: when the positional Tile sees a new
                // channel (via MultiviewStore.swap or replaceTile), don't
                // teardown — just hand the new URL to the existing mpv handle.
                if (channel.url.isNotBlank() && currentUrlRef.value != channel.url) {
                    Log.i(TAG, "Tile MPV swap: ${currentUrlRef.value} -> ${channel.url}")
                    view.playFile(channel.url)
                    currentUrlRef.value = channel.url
                }
                view.mpv.setPropertyString("aid", if (isAudioFocused) "auto" else "no")
                view.mpv.setPropertyString("mute", if (isAudioFocused) "no" else "yes")
            },
            onRelease = { view ->
                Log.i(TAG, "Tile MPV releasing: ${channel.name}")
                view.destroy()
            },
        )
        // Channel-name overlay (top-left). Always shown — iOS canon keeps the
        // tile's broadcast bug visible alongside the channel label.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
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
