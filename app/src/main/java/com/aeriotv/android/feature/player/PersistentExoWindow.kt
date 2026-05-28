package com.aeriotv.android.feature.player

import android.util.Log
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aeriotv.android.core.playback.AerioExoPlayerHolder

/**
 * The single, activity-lifetime Media3 [PlayerView]. Direct counterpart of
 * [PersistentMpvWindow] for the Media3 migration.
 *
 * Mounted ONCE inside MainActivity's root Box (as a sibling above NavHost,
 * via the same Phase 165 -> 167 z-order rules), it never changes parents.
 * Only the modifier driving its size + position changes in response to
 * [ExoWindowState] flips from PlayerScreen / TvMiniPlayerOverlay.
 *
 * Visibility modes mirror [PersistentMpvWindow] one-for-one:
 *   - Hidden: AndroidView mounted inside a zero-size Box.
 *   - Fullscreen: fillMaxSize within the outer Box. Default z-order, so
 *     PlayerScreen chrome drawn ON TOP via NavHost (Phase 167's trick).
 *   - Mini: 210x118 dp at top-right with 24dp end / 12dp top inset.
 *     zIndex(1f) so it floats above whatever NavHost route is showing
 *     underneath (Phase 175's fix).
 *
 * Surface mode: PlayerView with surface_type=surface_view. Media3's
 * MediaCodecVideoRenderer writes directly into the SurfaceView's surface
 * via MediaCodec.configure(..., surface) -- this is the path that fixes
 * the QTI HEVC-in-MPEG-TS bug. No GLES blit, no FFmpeg hevc_mediacodec
 * wrapper.
 */
@OptIn(UnstableApi::class)
@Composable
fun BoxScope.PersistentExoWindow(
    holder: AerioExoPlayerHolder,
    state: ExoWindowState,
) {
    val mode by state.mode.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // See PersistentMpvWindow for the long form of the z-index rationale.
    // tl;dr: NavHost paints over PersistentExoWindow by declaration order;
    // fullscreen wants that (chrome over video), mini doesn't (mini needs
    // to float above the Guide's opaque background).
    val containerModifier = when (mode) {
        ExoWindowState.Mode.Hidden -> Modifier.size(0.dp)
        ExoWindowState.Mode.Fullscreen -> Modifier
            .fillMaxSize()
            .background(Color.Black)
        ExoWindowState.Mode.Mini -> Modifier
            .zIndex(1f)
            .align(Alignment.TopEnd)
            .padding(end = 24.dp, top = 12.dp)
            .size(width = 210.dp, height = 118.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    }

    Box(modifier = containerModifier) {
        AndroidView(
            factory = { ctx ->
                Log.i(TAG, "PersistentExoWindow factory: building PlayerView + binding player")
                val player = holder.acquireOrCreate(ctx)
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // We render our own chrome via Compose (PlayerChromeOverlay).
                    // The built-in PlayerView controls would compete with it.
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // Letterbox (FIT) matches the iOS canon and our libmpv
                    // setup -- broadcast streams have intrinsic aspect ratios
                    // we don't want to crop. ZOOM is a Settings-level toggle
                    // we'll port from MPV later.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // Surface mode is set via the bundled layout
                    // exo_player_view.xml's surface_type=surface_view default.
                    setPlayer(player)
                }
            },
            modifier = Modifier.fillMaxSize(),
            // No onRelease teardown: this View outlives every composition.
            // PlayerScreen / mini-overlay only flip ExoWindowState; the
            // explicit X-close path goes through holder.destroy().
        )
    }
}

private const val TAG = "PersistentExoWindow"
