package com.aeriotv.android.feature.multiview

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multiview in the background (phones and tablets, round 2 2026-10-07): a
 * pinch in on the Multiview screen leaves to the browse screens with NO mini
 * player. The tiles keep playing headless here (video tracks off, every tile
 * muted except the audio-focused one) while a dock card above the nav bar
 * reads "Multiview playing in background". Tapping the card returns to the
 * fullscreen Multiview as it was (same tiles, order and audio focus; on-demand
 * tiles resume where the headless player is); Stop ends the tiles.
 * Main thread only. TV never enters this state.
 */
object MultiviewBackground {
    private const val TAG = "MultiviewBg"

    data class State(val tiles: List<MultiviewTile>, val focus: Int)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private var players: List<ExoPlayer?> = emptyList()

    /** Start the headless tiles. [positions] are on-demand tile positions
     *  (index -> ms) captured from the fullscreen grid. */
    @OptIn(UnstableApi::class)
    fun enter(
        context: Context,
        tiles: List<MultiviewTile>,
        focus: Int,
        headers: Map<String, String>,
        positions: Map<Int, Long>,
    ) {
        if (tiles.isEmpty()) return
        releasePlayers()
        val app = context.applicationContext
        val ua = "AerioTV/${com.aeriotv.android.BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})"
        val f = focus.coerceIn(0, tiles.lastIndex)
        players = tiles.mapIndexed { i, tile ->
            runCatching {
                val h = if (tile.kind == TileKind.Live) headers else headers + tile.httpHeaders
                val http = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(30_000)
                    .setReadTimeoutMs(30_000)
                    .setUserAgent(h.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: ua)
                if (h.isNotEmpty()) http.setDefaultRequestProperties(h)
                ExoPlayer.Builder(app)
                    .setHandleAudioBecomingNoisy(false)
                    .build()
                    .apply {
                        // Headless: no picture to draw, so no video decode.
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                            .build()
                        volume = if (i == f) 1f else 0f
                        setMediaSource(buildTileMediaSource(tile.resolvedUrl, DefaultDataSource.Factory(app, http)))
                        val pos = positions[i] ?: tile.resumePositionMs
                        if (tile.kind != TileKind.Live && pos != null && pos > 0) seekTo(pos)
                        prepare()
                        playWhenReady = true
                    }
            }.onFailure { Log.w(TAG, "tile ${tile.displayName} failed to start headless", it) }.getOrNull()
        }
        _state.value = State(tiles, f)
        Log.i(TAG, "MultiviewBg: background enter tiles=${tiles.size}")
    }

    /** Card tap: stop the headless tiles and hand back the grid to restore,
     *  with on-demand tiles carrying their current positions. */
    fun takeForReturn(): State? {
        val s = _state.value ?: return null
        val tiles = s.tiles.mapIndexed { i, t ->
            val p = players.getOrNull(i)
            if (t.kind != TileKind.Live && p != null && p.currentPosition > 0) {
                t.copy(resumePositionMs = p.currentPosition)
            } else {
                t
            }
        }
        releasePlayers()
        _state.value = null
        Log.i(TAG, "MultiviewBg: return tiles=${tiles.size}")
        return State(tiles, s.focus)
    }

    /** Card Stop: end the tiles. */
    fun stop() {
        if (_state.value == null) return
        releasePlayers()
        _state.value = null
        Log.i(TAG, "MultiviewBg: stop")
    }

    private fun releasePlayers() {
        players.forEach { p -> runCatching { p?.release() } }
        players = emptyList()
    }
}
