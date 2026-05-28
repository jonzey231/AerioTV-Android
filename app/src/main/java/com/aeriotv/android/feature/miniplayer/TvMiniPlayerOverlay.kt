package com.aeriotv.android.feature.miniplayer

import android.content.res.Configuration
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aeriotv.android.feature.player.MPVPlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import `is`.xyz.mpv.Utils

/**
 * Android TV mini-player: a small top-right window that keeps the channel
 * playing after the user backs out of the fullscreen PlayerScreen onto the
 * guide. tvOS PlayerSession parity (the "small window in the top-right with
 * the stream still playing" Archie's screenshot referenced).
 *
 * Architecture choice: this overlay creates a FRESH MPVPlayerView with its
 * own MPV handle and re-plays the channel URL, rather than re-parenting the
 * fullscreen MPVPlayerView. The reparenting path technically works for one
 * frame but mpv-android-lib's BaseMPVView clears `vo` to null when the
 * SurfaceView's surfaceDestroyed callback fires between parents, and the
 * post-attach restoration is not reliable - producing a black mini. The
 * fresh-handle path is straightforward, robust, and the (3-5 second) buffer
 * gap matches the user's expectation that mpv has to re-start a stream.
 *
 * Critical lifecycle invariants (the 2026-05-28 ANR + black-video fix):
 *   1. Mini overlay is purely visual -- NO clickable / focusable surface.
 *      The Column does not call .clickable(); resume is handled exclusively
 *      by MainActivity.dispatchKeyEvent's double-press OK detection. Adding
 *      a clickable here turned the overlay into a D-pad focus participant
 *      sitting between the top nav row and the guide grid, trapping focus
 *      and freezing navigation.
 *   2. playFile() is called via view.post {} so it fires AFTER the
 *      SurfaceView is attached and the surface is created. Calling playFile
 *      inside the AndroidView factory lambda (before attach) produced
 *      "h264_mediacodec: Both surface and native_window are NULL" in mpv
 *      logs -- vo gets a null surface and the mini stays black.
 *   3. v.destroy() in onRelease runs on a background coroutine, NOT the
 *      main thread. mpv_terminate_destroy joins demuxer/decode/audio
 *      threads and blocks for several seconds; on the main thread that
 *      tripped the input-dispatch 5s watchdog and ANR'd the activity
 *      (matches the Phase 96 close-button fix). We also pre-emptively set
 *      vid=no so the SurfaceView's synchronous surfaceDestroyed callback
 *      doesn't itself wait on libmpv's render thread to release the
 *      surface.
 *
 * PlayerScreen.BackHandler on TV destroys the held MPV before this overlay
 * mounts (clean handoff, no double-decoding the same URL). The resume flow
 * (double-press Select wired in MainActivity.dispatchKeyEvent) re-pushes
 * the PLAYER route which creates a fresh handle of its own.
 *
 * TV-only - phone uses [MiniPlayerRow] above the bottom nav.
 */
@Composable
fun BoxScope.TvMiniPlayerOverlay(
    state: MiniPlayerSession.State,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state !is MiniPlayerSession.State.Active) return
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    if (!isTv) return
    val channel = state.channel
    if (channel.url.isBlank()) return

    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            // tvOS reference shot: mini sits at the same vertical position
            // as the centered top nav pills (~12dp top inset), right-aligned
            // ~24dp from the edge. No vertical offset that would push it
            // below the nav row.
            .padding(end = 24.dp, top = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // ── Video window ──────────────────────────────────────────────
        // 210x118 dp = 16:9 at a size that doesn't fight the centered nav
        // pills for horizontal space on the 960dp Streamer canvas.
        Box(
            modifier = Modifier
                .size(width = 210.dp, height = 118.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        ) {
            AndroidView(
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
                        // Smaller cache than the fullscreen player - 3s
                        // demuxer buffer is plenty for a 118dp preview and
                        // keeps native memory low while the user is on the
                        // guide.
                        this.cachingMs = 3_000
                    }
                    view.initialize(configDir, cacheDir)
                    // Defer playFile until the SurfaceView has been
                    // attached + the surface is created. view.post queues
                    // onto the view's handler so it runs after the next
                    // layout pass. Without this, mpv attaches vo to a null
                    // surface and the mini stays black. (Confirmed in
                    // logcat: "h264_mediacodec: Both surface and
                    // native_window are NULL".)
                    val url = channel.url
                    view.post {
                        runCatching { view.playFile(url) }
                            .onFailure { Log.w(TAG, "playFile failed", it) }
                    }
                    view
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { v ->
                    // ANR-safe teardown (mirrors MPVPlayerHolder.destroy
                    // Phase 96 pattern):
                    //   a. vid=no on main thread (instant -- stops the
                    //      render path so SurfaceView's surfaceDestroyed
                    //      callback returns immediately instead of waiting
                    //      for libmpv's render thread).
                    //   b. mpv.destroy() on a background scope -- it joins
                    //      demuxer/decoder/audio threads and blocks for
                    //      seconds; on the main thread that ANR'd the
                    //      activity within 5s of overlay unmount.
                    runCatching { v.mpv.setPropertyString("vid", "no") }
                    val mpvHandle = v.mpv
                    teardownScope.launch {
                        runCatching { mpvHandle.destroy() }
                            .onFailure { Log.w(TAG, "async destroy failed", it) }
                    }
                },
            )
        }

        Spacer(Modifier.height(6.dp))

        // ── Resume hint (below the video, subtle) ────────────────────
        Text(
            text = "Double press OK to resume",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Background scope for mpv teardown so onRelease never blocks the main
 *  thread. SupervisorJob means a single teardown failure doesn't cancel
 *  subsequent ones. */
private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val TAG = "TvMiniPlayerOverlay"
