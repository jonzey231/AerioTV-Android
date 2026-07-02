package com.aeriotv.android.feature.multiview

import com.aeriotv.android.core.data.M3UChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped state for the multiview surface. Mirrors iOS MultiviewStore
 * (Aerio/Features/Multiview/MultiviewStore.swift) — single source of truth
 * for which channels are tiled, which tile owns audio, and which tiles are
 * muted.
 *
 * Phase 11a wires the selection set (the picker reads + writes it) and the
 * audio-focused index. The actual MPV-per-tile render lands in 11b, the
 * audio-focus visual indicator in 11c.
 */
@Singleton
class MultiviewStore @Inject constructor() {

    /** iOS hard cap: 9 tiles. The picker enforces this. */
    val maxTiles: Int = 9

    /**
     * iOS `MultiviewStore.softLimit` (issue #46): tile count at/above which
     * adding ANOTHER tile triggers the performance-warning confirmation
     * (softLimit + 1 == 5 is where it fires). Arguably more valuable on
     * Android TV hardware, where MediaTek decoders cap concurrent instances.
     */
    val softLimit: Int = 4

    /**
     * When the perf warning was last shown (iOS `warningLastShownAt`).
     * Deliberately in-memory, NOT persisted: iOS keeps it as a plain var on
     * the singleton so the warning re-fires after 2h (or a process restart),
     * while repeat over-limit adds in one sitting are not nagged.
     */
    private var warningLastShownAt: Long? = null

    /** 2h throttle window (iOS `warningThrottleInterval` = 7200s). */
    fun warningRecentlyShown(): Boolean {
        val last = warningLastShownAt ?: return false
        return System.currentTimeMillis() - last < 7_200_000L
    }

    fun noteWarningShown() {
        warningLastShownAt = System.currentTimeMillis()
    }

    private val _selected = MutableStateFlow<List<MultiviewTile>>(emptyList())
    val selected: StateFlow<List<MultiviewTile>> = _selected.asStateFlow()

    private val _audioFocusedIndex = MutableStateFlow(0)
    val audioFocusedIndex: StateFlow<Int> = _audioFocusedIndex.asStateFlow()

    fun isSelected(channel: M3UChannel): Boolean =
        _selected.value.any { it.id == channel.id }

    /** Id-based selection check for the mixed picker (VOD/Series/DVR tiles). */
    fun isSelected(tileId: String): Boolean =
        _selected.value.any { it.id == tileId }

    fun toggle(channel: M3UChannel) {
        val current = _selected.value
        val existing = current.firstOrNull { it.id == channel.id }
        if (existing != null) {
            _selected.value = current - existing
            // Slide audio focus back if the focused tile was removed past the end.
            if (_audioFocusedIndex.value >= _selected.value.size) {
                _audioFocusedIndex.value = (_selected.value.size - 1).coerceAtLeast(0)
            }
        } else if (current.size < maxTiles) {
            _selected.value = current + MultiviewTile.live(channel)
        }
    }

    /**
     * General picker add path (Phase 2: VOD / Series / DVR tiles). Returns true
     * if the tile is present after the call (already-added is treated as
     * success), false only when the grid is at [maxTiles]. Id-deduped so a
     * double-tap never stacks the same title.
     */
    fun addTile(tile: MultiviewTile): Boolean {
        val current = _selected.value
        if (current.any { it.id == tile.id }) return true
        if (current.size >= maxTiles) return false
        _selected.value = current + tile
        return true
    }

    /** Remove a tile by id (mixed picker counterpart of [removeAt]). */
    fun removeTile(tileId: String) {
        val index = _selected.value.indexOfFirst { it.id == tileId }
        if (index >= 0) removeAt(index)
    }

    fun clear() {
        _selected.value = emptyList()
        _audioFocusedIndex.value = 0
    }

    /**
     * Remove the tile at [index]. iOS parity (MultiviewStore.remove(id:),
     * MultiviewStore.swift 625-651): if the removed tile owned audio, audio
     * auto-promotes to the NEWEST remaining tile (`tiles.last`); otherwise
     * focus follows the originally-focused channel to its shifted slot.
     * The positional Tile composables stay mounted; tiles after the removed
     * index see a new url and do an in-place re-prepare (same cost profile
     * as [move]).
     */
    fun removeAt(index: Int) {
        val current = _selected.value
        if (index !in current.indices) return
        val removedHadAudio = index == _audioFocusedIndex.value
        val focusedChannelId = current.getOrNull(_audioFocusedIndex.value)?.id
        val mutable = current.toMutableList()
        mutable.removeAt(index)
        _selected.value = mutable
        _audioFocusedIndex.value = when {
            mutable.isEmpty() -> 0
            removedHadAudio -> mutable.lastIndex
            else -> mutable.indexOfFirst { it.id == focusedChannelId }.coerceAtLeast(0)
        }
    }

    /**
     * Player-flow seed: ensure [channel] is present at index 0 (the
     * audio-focused tile) WITHOUT discarding any already-staged tiles.
     * Used when the user opens "Add to Multiview" while watching a single
     * stream -- the now-playing channel becomes Tile 1 and keeps audio
     * focus, while a previously Guide-staged set (if any) is preserved
     * behind it. Idempotent: if the channel is already staged it is moved
     * to the front rather than duplicated.
     */
    fun seedCurrent(channel: M3UChannel) {
        val current = _selected.value
        val without = current.filterNot { it.id == channel.id }
        _selected.value = (listOf(MultiviewTile.live(channel)) + without).take(maxTiles)
        _audioFocusedIndex.value = 0
    }

    /**
     * Restore an exact prior selection + audio-focus index. Backs the
     * cancel path of the player-flow picker: if the user opens the sheet
     * (which seeds the current channel) then backs out, we revert the
     * store to precisely what it held before -- protecting a Guide-staged
     * set instead of blanket-clearing it.
     */
    fun restore(snapshot: List<MultiviewTile>, audioFocus: Int) {
        val clamped = snapshot.take(maxTiles)
        _selected.value = clamped
        _audioFocusedIndex.value = audioFocus.coerceIn(0, (clamped.size - 1).coerceAtLeast(0))
    }

    fun setAudioFocus(index: Int) {
        if (index in 0 until _selected.value.size) {
            _audioFocusedIndex.value = index
        }
    }

    /** Swaps two tile positions. Audio focus moves with the originally-focused tile. */
    fun swap(fromIndex: Int, toIndex: Int) {
        val current = _selected.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        if (fromIndex == toIndex) return
        val mutable = current.toMutableList()
        val tmp = mutable[fromIndex]
        mutable[fromIndex] = mutable[toIndex]
        mutable[toIndex] = tmp
        _selected.value = mutable
        // Track audio focus so it sticks to the same channel after the swap.
        val focused = _audioFocusedIndex.value
        _audioFocusedIndex.value = when (focused) {
            fromIndex -> toIndex
            toIndex -> fromIndex
            else -> focused
        }
    }

    /**
     * Move the tile at [fromIndex] to [toIndex] with insert (not swap)
     * semantics, shifting the tiles in between. Backs the drag-to-reorder
     * gesture (iOS MultiviewContainerView drag-reorder). Audio focus follows
     * the originally-focused channel to its new slot. The positional Tile
     * composables stay mounted; each affected slot's `update` callback sees a
     * new url and does an in-place loadfile, so no SurfaceView moves.
     */
    fun move(fromIndex: Int, toIndex: Int) {
        val current = _selected.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        if (fromIndex == toIndex) return
        val focusedChannelId = current.getOrNull(_audioFocusedIndex.value)?.id
        val mutable = current.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        _selected.value = mutable
        if (focusedChannelId != null) {
            val newFocus = mutable.indexOfFirst { it.id == focusedChannelId }
            if (newFocus >= 0) _audioFocusedIndex.value = newFocus
        }
    }

    /**
     * Replace the channel at [index] with [newChannel] in place. Mirrors iOS
     * MultiviewStore.swapTileContent (commit cc28f18 + e627ca7) — the
     * positional Tile composable preserves its AndroidView, the update
     * callback notices the URL change, and calls MPVPlayerView.playFile
     * which routes through `mpv_command loadfile` (default replace mode).
     * No tile teardown, no black flash on channel-flip.
     */
    fun replaceTile(index: Int, newTile: MultiviewTile) {
        val current = _selected.value
        if (index !in current.indices) return
        if (current[index].id == newTile.id) return
        val mutable = current.toMutableList()
        mutable[index] = newTile
        _selected.value = mutable
    }

    /**
     * Audit task #35: OOM guard. Called from the Application's onTrimMemory
     * forward when the system signals critical memory pressure. With up to 9
     * concurrent mpv handles + SurfaceViews + audio tracks, multiview is the
     * single largest resource consumer in the app, so it is the right thing
     * to shed first under pressure.
     *
     * Only acts on TRIM_MEMORY_RUNNING_CRITICAL (system about to kill us) or
     * TRIM_MEMORY_COMPLETE (we are being killed). Softer levels
     * (RUNNING_LOW / RUNNING_MODERATE / etc.) are NOT reacted to - those
     * fire frequently and shedding the user's tiles on every minor pressure
     * spike would be more disruptive than helpful. When we do shed we keep
     * exactly one tile (the audio-focused one, so the user's primary
     * viewing survives), drop all others. The freed mpv handles release
     * decoders, GPU buffers, audio tracks, and ~50-100MB of RAM each, which
     * is normally enough to let the system reclaim instead of killing us.
     */
    fun onMemoryPressure(level: Int) {
        val current = _selected.value
        if (current.size <= 1) return
        val shouldShed = when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> true
            else -> false
        }
        if (!shouldShed) return
        val focusedIdx = _audioFocusedIndex.value
        val keep = current.getOrNull(focusedIdx) ?: current.first()
        _selected.value = listOf(keep)
        _audioFocusedIndex.value = 0
        android.util.Log.w(
            "MultiviewStore",
            "memory pressure (level=$level): shed ${current.size - 1} of ${current.size} tiles, kept ${keep.displayName}",
        )
    }
}
