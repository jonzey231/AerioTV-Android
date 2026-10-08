package com.aeriotv.android.feature.multiview

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer

/**
 * Every live Multiview tile ExoPlayer on screen, so leaving the app on a TV
 * can silence them synchronously in Activity.onStop (Logan 2026-10-08: the
 * Streamer kept a tile AudioTrack in state:started at the launcher after HOME,
 * because the tiles are owned by the Multiview composable and nothing in the
 * TV leave path reached them). Main thread only.
 */
object MultiviewTilePlayers {
    private const val TAG = "AerioMV"
    private val players = LinkedHashSet<ExoPlayer>()

    fun register(player: ExoPlayer) { players += player }

    fun unregister(player: ExoPlayer) { players -= player }

    /** Stop every tile now: muted, not playing, sockets closed. The tiles'
     *  composables release them when the Multiview route disposes. */
    fun stopAll(reason: String) {
        if (players.isEmpty()) return
        Log.i(TAG, "[MV] stopping ${players.size} tile player(s): $reason")
        players.toList().forEach { p ->
            runCatching {
                p.volume = 0f
                p.playWhenReady = false
                p.stop()
            }
        }
    }

    val isEmpty: Boolean get() = players.isEmpty()
}
