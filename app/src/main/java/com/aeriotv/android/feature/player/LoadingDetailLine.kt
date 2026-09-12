package com.aeriotv.android.feature.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.core.playback.LiveStreamFailover
import com.aeriotv.android.core.playback.PlaybackTracer
import com.aeriotv.android.ui.LocalIsDispatcharrAdmin
import com.aeriotv.android.ui.theme.LocalAppTheme
import kotlinx.coroutines.delay

/**
 * The small line under a loading spinner that says what the network is actually
 * doing, so a slow server looks like a slow server instead of a broken player
 * (Apple parity: LoadingDetailLine, commit 056feb2).
 *
 * It appears only after 3 s of loading so a quick start never shows it, refreshes
 * every 250 ms, and reads the byte source the PlaybackTracer wrapper already
 * counts (never a second counter). No progress bar, no toggle.
 *
 * Kept in its own file: both player screens are at the ART verifier's register
 * limit, so each of them calls into here with exactly ONE line.
 */
private const val DETAIL_DELAY_MS = 3_000L
private const val DETAIL_TICK_MS = 250L

/** KB below 1 MB, one decimal MB below 1024 MB, one decimal GB above. */
internal fun receivedText(bytes: Long): String {
    if (bytes < 1_048_576L) return "Received ${bytes / 1024L} KB"
    val mb = bytes.toDouble() / 1_048_576.0
    if (mb < 1024.0) return String.format("Received %.1f MB", mb)
    return String.format("Received %.1f GB", mb / 1024.0)
}

/**
 * The polled detail string, or null while it must stay hidden.
 *
 * [active] is the "loading / buffering status is showing" condition; its
 * transition to true starts the 3 s clock (a change between two non-empty
 * statuses leaves it true and the clock running). [restartKey] changing restarts
 * the waiting counter, which is what a failover step does.
 */
@Composable
private fun loadingDetail(
    active: Boolean,
    restartKey: Any?,
    sample: () -> PlaybackTracer.LoadingSnapshot,
): String? {
    var detail by remember { mutableStateOf<String?>(null) }
    var stepAtMs by remember { mutableLongStateOf(0L) }
    var connectedFallbackAtMs by remember { mutableLongStateOf(0L) }
    val reader = rememberUpdatedState(sample)
    LaunchedEffect(restartKey) {
        stepAtMs = SystemClock.elapsedRealtime()
        connectedFallbackAtMs = 0L
    }
    LaunchedEffect(active) {
        if (!active) {
            detail = null
            connectedFallbackAtMs = 0L
            return@LaunchedEffect
        }
        val appearedAtMs = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            detail = if (now - appearedAtMs < DETAIL_DELAY_MS) {
                null
            } else {
                val s = reader.value()
                when {
                    !s.connected -> "Connecting to server"
                    s.bytes <= 0L -> {
                        if (connectedFallbackAtMs == 0L) connectedFallbackAtMs = now
                        val base = maxOf(s.connectedAtMs, stepAtMs, connectedFallbackAtMs)
                        val secs = ((now - base) / 1000L).coerceAtLeast(0L)
                        "Waiting for stream data  $secs s"
                    }
                    else -> receivedText(s.bytes)
                }
            }
            delay(DETAIL_TICK_MS)
        }
    }
    return detail
}

/**
 * The one loading spinner either player may draw (tvOS parity: the native
 * circular ProgressView centered on the video). Theme accent, 24 dp on TV and
 * 32 dp on phone, 3 dp stroke. Nothing else in a player may draw a second one.
 */
@Composable
private fun LoadingSpinner(isTv: Boolean) {
    CircularProgressIndicator(
        color = LocalAppTheme.current.accentPrimary,
        strokeWidth = 3.dp,
        modifier = Modifier.size(if (isTv) 24.dp else 32.dp),
    )
}

/** The status line under the spinner: caption style, white 80 percent. */
@Composable
private fun LoadingStatusText(status: String) {
    Text(
        text = status,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.8f),
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
}

/** 9 sp on TV, 11 sp on phone, white 55 percent, monospaced digits, one line. */
@Composable
private fun DetailLineText(detail: String?, isTv: Boolean) {
    Text(
        text = detail ?: " ",
        fontSize = if (isTv) 9.sp else 11.sp,
        color = Color.White.copy(alpha = 0.55f),
        maxLines = 1,
        textAlign = TextAlign.Center,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        modifier = Modifier.alpha(if (detail == null) 0f else 1f),
    )
}

/**
 * VOD / catch-up / DVR detail line. Shows while the screen's own loading message
 * is up (URL resolve, version switch) and while the mounted player is still
 * buffering with nothing rendered, which is the same spinner to the user.
 */
@Composable
fun VodLoadingDetail(
    loadingMessage: String?,
    streamUrl: String,
    player: ExoPlayer?,
    tracer: PlaybackTracer,
    isTv: Boolean,
) {
    var buffering by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            buffering = p.playbackState == Player.STATE_BUFFERING && p.currentPosition <= 0L
            delay(DETAIL_TICK_MS)
        }
    }
    val active = loadingMessage != null || streamUrl.isBlank() || buffering
    val detail = loadingDetail(active = active, restartKey = streamUrl) { tracer.loadingSnapshot() }
    if (!active) return
    // The screen body already centers its own status text ("Loading...", a
    // version-switch message) while it is resolving a URL, so in that phase we
    // place the SAME stack around it: spinner above, detail line below. Once the
    // player is mounted and merely buffering, this composable owns the whole
    // stack and draws the status text itself. Either way: one spinner, one
    // status line, one detail line, centered on the video.
    val bodyOwnsStatus = loadingMessage != null || streamUrl.isBlank()
    if (bodyOwnsStatus) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.offset(y = (-30).dp)) {
                LoadingSpinner(isTv = isTv)
            }
            Box(modifier = Modifier.offset(y = 22.dp)) {
                DetailLineText(detail = detail, isTv = isTv)
            }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingSpinner(isTv = isTv)
        LoadingStatusText(status = "Buffering...")
        DetailLineText(detail = detail, isTv = isTv)
    }
}

/**
 * Live player: wires the holder's [LiveStreamFailover] hooks for this screen and
 * draws the failover status plus the loading detail line over the video.
 *
 * The hooks take the channel id as a PARAMETER (never captured), the same rule
 * the holder's onTerminalErrorRebuildUrl follows after the 2026-09-11
 * wrong-channel re-prime.
 */
@Composable
fun LiveFailoverStatusOverlay(
    exoHolder: AerioExoPlayerHolder,
    isTv: Boolean,
    channels: List<M3UChannel>,
    onLoadChannelStreams: suspend (Int) -> List<StreamOption>,
    onSwitchChannelStream: suspend (String, Int) -> String?,
    onLoadCurrentStreamId: suspend (String) -> Int?,
) {
    val isAdmin = LocalIsDispatcharrAdmin.current
    val channelsNow = rememberUpdatedState(channels)
    val listStreams = rememberUpdatedState(onLoadChannelStreams)
    val switchStream = rememberUpdatedState(onSwitchChannelStream)
    val statusStreamId = rememberUpdatedState(onLoadCurrentStreamId)
    DisposableEffect(isAdmin) {
        exoHolder.liveFailover.hooks = LiveStreamFailover.Hooks(
            // Direct Connect + admin + an integer channel pk: change_stream is
            // IsAdmin server-side, so a standard sub-account would only get 403.
            canSwitch = { id ->
                isAdmin && id.startsWith("disp:") &&
                    channelsNow.value.firstOrNull { it.id == id }?.dispatcharrChannelId != null
            },
            listStreamIds = { id ->
                val pk = channelsNow.value.firstOrNull { it.id == id }?.dispatcharrChannelId
                if (pk == null) emptyList() else listStreams.value(pk).map { it.id }
            },
            currentStreamId = { uuid -> statusStreamId.value(uuid) },
            changeStream = { uuid, streamId -> switchStream.value(uuid, streamId) },
        )
        onDispose { exoHolder.liveFailover.hooks = null }
    }

    val statusText by exoHolder.liveStatusText.collectAsStateWithLifecycle()
    val unavailable by exoHolder.streamUnavailable.collectAsStateWithLifecycle()
    val playerInstance by exoHolder.playerInstance.collectAsStateWithLifecycle()
    var buffering by remember { mutableStateOf(false) }
    LaunchedEffect(playerInstance) {
        val p = playerInstance ?: return@LaunchedEffect
        while (true) {
            buffering = p.playbackState == Player.STATE_BUFFERING && p.currentPosition <= 0L
            delay(DETAIL_TICK_MS)
        }
    }
    // The unavailable card owns the screen once the ladder gives up.
    val active = !unavailable && (statusText != null || buffering)
    val detail = loadingDetail(active = active, restartKey = statusText) {
        exoHolder.tracer.loadingSnapshot()
    }
    if (!active) return
    // tvOS parity stack (TSHLSRemuxer.swift ~2147): spinner, 8 dp, status text,
    // 8 dp, detail line -- centered on the video and the ONLY loading indicator
    // the live player draws (PlayerView's own spinner is SHOW_BUFFERING_NEVER).
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingSpinner(isTv = isTv)
        LoadingStatusText(status = statusText ?: "Buffering...")
        DetailLineText(detail = detail, isTv = isTv)
    }
}
