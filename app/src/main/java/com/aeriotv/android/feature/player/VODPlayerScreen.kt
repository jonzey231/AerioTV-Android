package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.settings.bufferMillisFor
import com.aeriotv.android.feature.watchprogress.WatchProgressViewModel
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.delay

private const val TAG = "VODPlayerScreen"
private const val AUTO_HIDE_MS = 4_000L

/**
 * VOD playback. Same MPV pipeline as the live PlayerScreen but with a much
 * thinner chrome: tap-to-toggle + close button + title overlay. Phase 10c
 * adds the iOS-canon scrubber + position/duration row.
 *
 * `isLive = false` flips the buffer floor off (live forces a 5s minimum that
 * delays VOD startup unnecessarily) and lets MPV pick smooth-resume defaults.
 */
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
    val settingsVm: SettingsViewModel = hiltViewModel()
    val streamBufferSize by settingsVm.streamBufferSize.collectAsStateWithLifecycle(initialValue = "default")
    val watchVm: WatchProgressViewModel = hiltViewModel()

    var chromeVisible by remember { mutableStateOf(true) }
    var mpvView by remember { mutableStateOf<MPVPlayerView?>(null) }

    // Saved progress lookup. Null while loading; -1L after a confirmed "no
    // saved progress" read. Drives the resume-seek LaunchedEffect.
    var savedPositionMs by remember(videoId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(videoId) {
        if (videoId.isNullOrBlank()) return@LaunchedEffect
        val existing = watchVm.get(videoId)
        savedPositionMs = existing?.positionMs ?: -1L
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
                Utils.copyAssets(ctx)
                val configDir = ctx.filesDir.path
                val cacheDir = ctx.cacheDir.path

                val view = MPVPlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.isLive = false
                    this.caFilePath = "$configDir/cacert.pem"
                    this.httpHeaders = httpHeaders
                    this.cachingMs = bufferMillisFor(streamBufferSize)
                }
                view.initialize(configDir, cacheDir)

                view.mpv.addLogObserver(object : MPV.LogObserver {
                    override fun logMessage(prefix: String, level: Int, text: String) {
                        Log.i(TAG, "[mpv $prefix/L$level] ${text.trimEnd()}")
                    }
                })
                view.mpv.addObserver(object : MPV.EventObserver {
                    override fun eventProperty(property: String) {}
                    override fun eventProperty(property: String, value: Long) {}
                    override fun eventProperty(property: String, value: Boolean) {}
                    override fun eventProperty(property: String, value: String) {}
                    override fun eventProperty(property: String, value: Double) {}
                    override fun eventProperty(property: String, value: MPVNode) {}
                    override fun event(eventId: Int, data: MPVNode) {}
                })

                Log.i(TAG, "Loading VOD: $streamUrl")
                if (streamUrl.isNotBlank()) view.playFile(streamUrl)
                mpvView = view
                view
            },
            onRelease = { view ->
                Log.i(TAG, "Releasing VOD MPV")
                mpvView = null
                view.destroy()
            },
        )

        // Resume from saved position. Waits 1.5s for MPV to settle into playback
        // before seeking - seeking before PLAYBACK_RESTART tends to be a no-op
        // since the demuxer hasn't reported duration yet.
        LaunchedEffect(mpvView, savedPositionMs) {
            val view = mpvView ?: return@LaunchedEffect
            val pos = savedPositionMs ?: return@LaunchedEffect
            if (pos <= 0L) return@LaunchedEffect
            delay(1_500L)
            view.mpv.setPropertyString("time-pos", (pos / 1000.0).toString())
            Log.i(TAG, "Resumed from ${pos}ms")
        }

        // Periodic save. Mirrors iOS NowPlayingManager.currentWatchProgress's
        // ~5s persistence cadence. Bails if the player hasn't reported a
        // valid position/duration yet (live streams, early-load, etc).
        LaunchedEffect(mpvView, videoId) {
            val view = mpvView ?: return@LaunchedEffect
            if (videoId.isNullOrBlank()) return@LaunchedEffect
            while (true) {
                delay(5_000L)
                val posStr = view.mpv.getPropertyString("time-pos") ?: continue
                val durStr = view.mpv.getPropertyString("duration") ?: continue
                val posSecs = posStr.toDoubleOrNull() ?: continue
                val durSecs = durStr.toDoubleOrNull() ?: continue
                if (posSecs <= 0.0 || durSecs <= 0.0) continue
                watchVm.save(
                    videoId = videoId,
                    title = title,
                    posterUrl = posterUrl,
                    positionMs = (posSecs * 1000).toLong(),
                    durationMs = (durSecs * 1000).toLong(),
                )
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
            visible = chromeVisible,
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
                }
            }
        }
    }

    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { /* AndroidView.onRelease handles native cleanup. */ }
    }
}
