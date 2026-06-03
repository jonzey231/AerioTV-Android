package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.pip.PipState
import com.aeriotv.android.core.pip.enterPip16x9
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.pip.supportsPip
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.settings.bufferMillisFor
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

private const val TAG = "VODPlayerScreen"
private const val AUTO_HIDE_MS = 4_000L

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
) {
    // Keep the screen on during VOD playback. Matches PlayerScreen for the
    // same reason: system screen-timeout would otherwise dim/sleep the panel
    // mid-movie. iOS parity via IdleTimerRefCount.
    KeepScreenOnWhilePlaying()

    val settingsVm: SettingsViewModel = hiltViewModel()
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

    // Player progress, polled every 500ms while the player is mounted. Backs
    // the scrubber + position/duration row. positionMs is the canonical
    // playback position; previewMs is the user's pending-drag position before
    // the seek is committed.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPaused by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

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
            .background(Color.Black),
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
            // Close affordance still available during load / error.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
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

                // Wrap the header-aware HTTP factory in DefaultDataSource.Factory
                // so a local DVR recording's file:// URL resolves through
                // FileDataSource while remote URLs (VOD + Dispatcharr server
                // recordings) still flow through the HTTP factory carrying the
                // auth headers. A bare DefaultHttpDataSource.Factory cannot open
                // file://, so local recordings would otherwise fail to load.
                val upstreamFactory = DefaultDataSource.Factory(ctx, dataSourceFactory)
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
                            }
                        })
                        playWhenReady = true
                        setMediaItem(MediaItem.fromUri(streamUrl))
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
            if (player.contentDuration > 0L) {
                player.seekTo(pos)
                Log.i(TAG, "Resumed from ${pos}ms")
            }
        }

        // Periodic save. Mirrors iOS NowPlayingManager.currentWatchProgress's
        // ~5s persistence cadence. Same logic, just reading from ExoPlayer
        // instead of libmpv property-strings.
        LaunchedEffect(exoPlayer, videoId) {
            val player = exoPlayer ?: return@LaunchedEffect
            if (videoId.isNullOrBlank()) return@LaunchedEffect
            while (true) {
                delay(5_000L)
                val pos = player.contentPosition
                val dur = player.contentDuration
                if (pos <= 0L || dur <= 0L) continue
                watchVm.save(
                    videoId = videoId,
                    title = title,
                    posterUrl = posterUrl,
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
            while (true) {
                delay(500L)
                if (isDragging) continue
                positionMs = player.contentPosition.coerceAtLeast(0L)
                durationMs = player.contentDuration.coerceAtLeast(0L)
                isPaused = !player.playWhenReady
            }
        }

        // Tap-to-toggle chrome layer.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { chromeVisible = !chromeVisible },
        )

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
                        .statusBarsPadding()
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
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (pipAvailable) {
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
                BottomChrome(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPaused = isPaused,
                    isDragging = isDragging,
                    dragFraction = dragFraction,
                    onDragStart = { isDragging = true },
                    onDragChanged = { dragFraction = it },
                    onDragEnd = { fraction ->
                        val target = (fraction * durationMs).toLong()
                        exoPlayer?.seekTo(target)
                        positionMs = target
                        isDragging = false
                    },
                    onTogglePlay = {
                        val player = exoPlayer ?: return@BottomChrome
                        val nowPaused = !player.playWhenReady
                        player.playWhenReady = nowPaused  // toggling: paused -> resume
                        isPaused = !nowPaused
                    },
                    onSkipBack = {
                        val player = exoPlayer ?: return@BottomChrome
                        val target = max(0L, positionMs - 10_000L)
                        player.seekTo(target)
                        positionMs = target
                    },
                    onSkipForward = {
                        val player = exoPlayer ?: return@BottomChrome
                        val maxPos = if (durationMs > 0) durationMs else Long.MAX_VALUE
                        val target = min(maxPos, positionMs + 10_000L)
                        player.seekTo(target)
                        positionMs = target
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
            }
        }
    }

    LaunchedEffect(chromeVisible, isDragging) {
        if (chromeVisible && !isDragging) {
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ScrubberBar(
            positionMs = positionMs,
            durationMs = durationMs,
            isDragging = isDragging,
            dragFraction = dragFraction,
            onDragStart = onDragStart,
            onDragChanged = onDragChanged,
            onDragEnd = onDragEnd,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val displayMs = if (isDragging) (dragFraction * durationMs).toLong() else positionMs
            Text(
                text = formatTime(displayMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSkipBack) {
                Icon(
                    imageVector = Icons.Filled.Replay10,
                    contentDescription = "Back 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTogglePlay() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (isPaused) "Play" else "Pause",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSkipForward) {
                Icon(
                    imageVector = Icons.Filled.Forward10,
                    contentDescription = "Forward 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun ScrubberBar(
    positionMs: Long,
    durationMs: Long,
    isDragging: Boolean,
    dragFraction: Float,
    onDragStart: () -> Unit,
    onDragChanged: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val durationFloat = max(1L, durationMs).toFloat()
    val raw = if (isDragging) dragFraction else positionMs.toFloat() / durationFloat
    val filledFraction = raw.coerceIn(0f, 1f)
    val trackHeight = if (isDragging) 6.dp else 3.dp
    val thumbSize = if (isDragging) 18.dp else 12.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
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
        val thumbXPx = (widthPx * filledFraction) - thumbHalf
        val thumbX = with(density) { thumbXPx.toDp() }
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
                    detectDragGestures(
                        onDragStart = { offset: Offset ->
                            onDragStart()
                            onDragChanged((offset.x / widthPx).coerceIn(0f, 1f))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            onDragChanged((change.position.x / widthPx).coerceIn(0f, 1f))
                        },
                        onDragEnd = {
                            onDragEnd(dragFraction.coerceIn(0f, 1f))
                        },
                        onDragCancel = {
                            onDragEnd(dragFraction.coerceIn(0f, 1f))
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
