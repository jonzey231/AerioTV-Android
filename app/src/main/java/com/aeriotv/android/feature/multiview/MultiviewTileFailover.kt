package com.aeriotv.android.feature.multiview

import androidx.compose.runtime.mutableStateOf
import com.aeriotv.android.core.playback.LiveStreamFailover

/**
 * Screen-to-tile wiring for the Multiview live tiles' stream walk and the
 * connection-issue pin (Apple parity: MultiviewContainerView
 * connectionIssueChanged).
 *
 * [hooks] is installed by MultiviewScreen for as long as the grid is on screen
 * (the same Dispatcharr calls the player's LiveFailoverStatusOverlay wires),
 * so each tile's [LiveStreamFailover] reads it at walk time instead of having
 * it threaded through the grid's layout composables. Main thread.
 */
object MultiviewTileFailover {

    /** Dispatcharr change_stream access for tiles; null while no grid is up. */
    @Volatile var hooks: LiveStreamFailover.Hooks? = null

    /** A tile's own [LiveStreamFailover.Hooks]: every call goes through
     *  [hooks] as installed at that moment, so a closed grid never walks. */
    val tileHooks = LiveStreamFailover.Hooks(
        canSwitch = { id -> runCatching { hooks?.canSwitch?.invoke(id) }.getOrNull() == true },
        listStreamIds = { id -> hooks?.listStreamIds?.invoke(id) ?: emptyList() },
        currentStreamId = { uuid -> hooks?.currentStreamId?.invoke(uuid) },
        changeStream = { uuid, streamId ->
            (hooks ?: throw IllegalStateException("Multiview closed")).changeStream(uuid, streamId)
        },
    )

    /** Tiles currently showing a connection issue (status, error card, or
     *  limit notice). Snapshot state: the screen pins its chrome while the
     *  sole tile is in here. */
    val issueTiles = mutableStateOf<Set<Any>>(emptySet())

    fun reportIssue(key: Any, active: Boolean) {
        val now = issueTiles.value
        val next = if (active) now + key else now - key
        if (next != now) issueTiles.value = next
    }
}
