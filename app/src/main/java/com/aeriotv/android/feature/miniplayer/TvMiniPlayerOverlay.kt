package com.aeriotv.android.feature.miniplayer

import android.content.res.Configuration
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aeriotv.android.core.playback.MPVPlayerHolder

/**
 * Android TV mini-player: a small top-right window that keeps the channel
 * playing after the user backs out of the fullscreen PlayerScreen onto the
 * guide. tvOS PlayerSession parity.
 *
 * Phase 163 architecture pivot (Archie 2026-05-28: "the stream should NOT
 * relaunch when switching between mini and fullscreen, only the window size
 * should change"):
 *
 * The mini-player now REUSES the same MPVPlayerView held in
 * [MPVPlayerHolder] across mini ↔ fullscreen transitions, just re-parenting
 * it between this overlay's AndroidView frame and PlayerScreen's
 * AndroidView frame. There is one libmpv handle, one decoder, one audio
 * track -- only the SurfaceView's parent (and hence its rendered size)
 * changes. Matches tvOS / iOS PlayerSession exactly. No reload, no buffer
 * gap.
 *
 * The "black mini" trap (Phase 140 regression): when the SurfaceView is
 * removed from one frame and re-added to another, BaseMPVView's
 * surfaceDestroyed callback fires synchronously and clears `vo` to null.
 * The previous workaround fix (set vid=auto on re-acquire inside
 * acquireOrCreate) ran BEFORE the new SurfaceView's surfaceCreated
 * callback, so vo had no surface to bind to. Phase 163 wraps the
 * vid=auto + pause=no in view.post {}, which defers them until after
 * layout + surfaceCreated have run -- now vo re-binds reliably.
 *
 * Resume to fullscreen is exclusively MainActivity's double-press-OK
 * detection. This overlay is purely visual -- no clickable / focusable
 * surface so the guide's D-pad navigation isn't trapped.
 *
 * TV-only -- phone uses [MiniPlayerRow] above the bottom nav.
 */
@Composable
fun BoxScope.TvMiniPlayerOverlay(
    state: MiniPlayerSession.State,
    mpvHolder: MPVPlayerHolder,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state !is MiniPlayerSession.State.Active) return
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    if (!isTv) return
    val channel = state.channel
    if (channel.url.isBlank()) return
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            // Vertical alignment matches the centered top nav pill row.
            .padding(end = 24.dp, top = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // ── Video window ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(width = 210.dp, height = 118.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    // acquireOrCreate returns the held view (decoded
                    // stream still running) or creates it if this is the
                    // very first mount of the app's session. detaches
                    // from any prior parent first so the new
                    // AndroidView frame can adopt cleanly.
                    val view = mpvHolder.acquireOrCreate(
                        context = ctx,
                        caFilePath = "${ctx.filesDir.path}/cacert.pem",
                        cachingMs = 3_000,
                        isLive = true,
                        httpHeaders = emptyMap(),
                        configDir = ctx.filesDir.path,
                        cacheDir = ctx.cacheDir.path,
                    )
                    // First-time mounts (held view didn't exist) need a
                    // playFile. Reuses (held view existed, same channel)
                    // skip it; channel switches (held view but different
                    // currentChannelId) re-issue playFile to switch
                    // streams without losing the handle.
                    val needsLoad = mpvHolder.currentChannelId != channel.id
                    if (needsLoad) {
                        view.post {
                            runCatching { view.playFile(channel.url) }
                                .onFailure { Log.w(TAG, "playFile failed", it) }
                            mpvHolder.currentChannelId = channel.id
                        }
                    }
                    // Re-bind vo to the new SurfaceView. The
                    // surfaceCreated callback on the mini's SurfaceView
                    // fires after layout; view.post defers vid=auto
                    // until then so vo has an attached surface to bind
                    // to. Phase 140's "black mini" came from firing
                    // these synchronously inside acquireOrCreate,
                    // BEFORE the surface existed.
                    view.post {
                        runCatching { view.mpv.setPropertyString("vid", "auto") }
                            .onFailure { Log.w(TAG, "vid=auto failed", it) }
                        runCatching { view.mpv.setPropertyString("pause", "no") }
                            .onFailure { Log.w(TAG, "pause=no failed", it) }
                    }
                    view
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    // Persistent-MPV teardown: remove from THIS frame
                    // but leave the held view + libmpv handle alive
                    // for the next consumer (PlayerScreen on resume,
                    // or a future re-mount of this overlay). The next
                    // acquireOrCreate consumer will re-parent it +
                    // re-bind vo via the view.post {} trampoline above.
                    mpvHolder.detach()
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

private const val TAG = "TvMiniPlayerOverlay"
