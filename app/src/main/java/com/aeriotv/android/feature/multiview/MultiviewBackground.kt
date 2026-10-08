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
    private var appContext: Context? = null
    private var lastHeaders: Map<String, String> = emptyMap()

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
        appContext = app
        lastHeaders = headers
        val f = focus.coerceIn(0, tiles.lastIndex)
        players = tiles.mapIndexed { i, tile ->
            headlessPlayer(app, tile, headers, focused = i == f, positionMs = positions[i] ?: tile.resumePositionMs)
        }
        endedByReturn = false
        _state.value = State(tiles, f)
        Log.i(TAG, "MultiviewBg: background enter tiles=${tiles.size}")
    }

    /**
     * Live TV "Add to Multiview" while the tiles play in the background
     * (Apple e1dcbd2 parity): the channel joins the running pile as one more
     * headless tile, or leaves it when it is already a tile, instead of a
     * fresh staged pile replacing it. Returns false when nothing runs here.
     */
    fun toggleTile(tile: MultiviewTile, maxTiles: Int): Boolean {
        val s = _state.value ?: return false
        val app = appContext ?: return false
        val index = s.tiles.indexOfFirst { it.id == tile.id }
        if (index >= 0) {
            runCatching { players.getOrNull(index)?.release() }
            val tiles = s.tiles.filterIndexed { i, _ -> i != index }
            players = players.filterIndexed { i, _ -> i != index }
            if (tiles.isEmpty()) {
                endedByReturn = false
                _state.value = null
                Log.i(TAG, "MultiviewBg: remove ${tile.displayName}: no tiles left; Multiview ended")
                return true
            }
            // Audio follows the focused tile; a removed focused tile hands
            // audio to the newest remaining tile (MultiviewStore.removeAt).
            val focus = when {
                index == s.focus -> tiles.lastIndex
                index < s.focus -> s.focus - 1
                else -> s.focus
            }
            players.getOrNull(focus)?.volume = 1f
            _state.value = State(tiles, focus)
            Log.i(TAG, "MultiviewBg: remove ${tile.displayName}: tiles=${tiles.size}")
            return true
        }
        if (s.tiles.size >= maxTiles) {
            Log.i(TAG, "MultiviewBg: add ${tile.displayName} refused: ${s.tiles.size} tiles")
            return true
        }
        players = players + headlessPlayer(app, tile, lastHeaders, focused = false, positionMs = tile.resumePositionMs)
        _state.value = State(s.tiles + tile, s.focus)
        Log.i(TAG, "MultiviewBg: add ${tile.displayName}: tiles=${s.tiles.size + 1}")
        return true
    }

    @OptIn(UnstableApi::class)
    private fun headlessPlayer(
        app: Context,
        tile: MultiviewTile,
        headers: Map<String, String>,
        focused: Boolean,
        positionMs: Long?,
    ): ExoPlayer? {
        val ua = "AerioTV/${com.aeriotv.android.BuildConfig.VERSION_NAME} (Android; ${android.os.Build.MODEL})"
        return run {
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
                        volume = if (focused) 1f else 0f
                        setMediaSource(buildTileMediaSource(tile.resolvedUrl, DefaultDataSource.Factory(app, http)))
                        val pos = positionMs
                        if (tile.kind != TileKind.Live && pos != null && pos > 0) seekTo(pos)
                        prepare()
                        playWhenReady = true
                    }
            }.onFailure { Log.w(TAG, "tile ${tile.displayName} failed to start headless", it) }.getOrNull()
        }
    }

    /** Card tap: stop the headless tiles and hand back the grid to restore,
     *  with on-demand tiles carrying their current positions. */
    /**
     * True when the last end was a return to the grid ([takeForReturn]): the
     * Multiview keeps running in the foreground, so the Live TV mirror of the
     * set must NOT clear. Reset by [stop] and when tiles go to the background.
     */
    @Volatile
    var endedByReturn: Boolean = false
        private set

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
        endedByReturn = true
        _state.value = null
        Log.i(TAG, "MultiviewBg: return tiles=${tiles.size}")
        return State(tiles, s.focus)
    }

    /** Card Stop: end the tiles. */
    fun stop() {
        if (_state.value == null) return
        releasePlayers()
        endedByReturn = false
        _state.value = null
        Log.i(TAG, "MultiviewBg: stop")
    }

    private fun releasePlayers() {
        players.forEach { p -> runCatching { p?.release() } }
        players = emptyList()
    }
}
