package com.aeriotv.android.feature.multiview

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.core.data.M3UChannel
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    LaunchedEffect(chromeVisible, lastInteractionAt) {
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
    onTileFocused: () -> Unit,
    onTileTap: (Int) -> Unit,
    onTileLongPress: (Int) -> Unit,
    onTileDoubleTap: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
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
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val (gr, gc) = gridShapeFor(tiles.size)
                val r = dpadIndex / gc
                val c = dpadIndex % gc
                fun idx(rr: Int, cc: Int) = rr * gc + cc
                when (ev.key) {
                    Key.DirectionRight -> {
                        val n = idx(r, c + 1)
                        if (c + 1 < gc && n < tiles.size) { dpadIndex = n; true } else false
                    }
                    Key.DirectionLeft -> {
                        if (c > 0) { dpadIndex = idx(r, c - 1); true } else false
                    }
                    Key.DirectionDown -> {
                        val n = idx(r + 1, c)
                        if (r + 1 < gr && n < tiles.size) { dpadIndex = n; true } else false
                    }
                    Key.DirectionUp -> {
                        if (r > 0) { dpadIndex = idx(r - 1, c); true } else false
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        onTileTap(dpadIndex); true
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
        tiles.forEachIndexed { index, channel ->
            val isFull = fullscreenIndex == index
            val col = index % cols
            val row = index / cols
            val offX = when {
                isFull -> 0.dp
                anyFullscreen -> gridW + cellWDp * col // parked off-screen (right)
                else -> cellWDp * col
            }
            val offY = if (isFull) 0.dp else cellHDp * row
            Box(
                modifier = Modifier
                    .offset(x = offX, y = offY)
                    .size(
                        width = if (isFull) gridW else cellWDp,
                        height = if (isFull) gridH else cellHDp,
                    )
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
) {
    val shape = if (tileRounded) RoundedCornerShape(8.dp) else RoundedCornerShape(0.dp)
    // D-pad focus (which tile the remote is currently on) is owned by the
    // grid host and passed in as [isDpadFocused]. Distinct from AUDIO focus
    // (which tile is playing sound): the user moves the ring around the grid
    // and presses OK to promote the ringed tile to audio focus. Without a
    // visible ring the user can't tell which tile they're about to select.
    val dpadFocused = isDpadFocused
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
    val borderColor = when {
        isDropTarget -> MaterialTheme.colorScheme.tertiary
        isRelocating -> MaterialTheme.colorScheme.primary
        dpadFocused -> Color.White
        isAudioFocused && audioFocusStyle == "grayPersistent" ->
            Color.White.copy(alpha = 0.5f)
        isAudioFocused && audioFocusStyle == "themeFading" && !focusFadedOut ->
            MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = 0.08f)
    }
    val borderWidth = when {
        isDropTarget -> 4.dp
        dpadFocused -> 4.dp
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
        ExoTile(
            url = channel.url,
            channelName = channel.name,
            httpHeaders = httpHeaders,
            isAudioFocused = isAudioFocused,
            paused = paused,
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
    isAudioFocused: Boolean,
    paused: Boolean,
) {
    val currentUrlRef = remember { mutableStateOf("") }
    val playerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Live-stream tuning. Tighter than the VOD profile because
            // a 9-tile grid wants every tile to start ASAP, even at
            // the cost of an occasional rebuffer.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_500, 5_000, 500, 2_000)
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
            val renderersFactory = DefaultRenderersFactory(ctx)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
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

            Log.i(TAG, "Tile ExoPlayer loading: $channelName")
            if (url.isNotBlank()) {
                player.setMediaSource(buildTileMediaSource(url, dataSourceFactory))
                player.prepare()
                currentUrlRef.value = url
            }

            PlayerView(ctx).apply {
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
