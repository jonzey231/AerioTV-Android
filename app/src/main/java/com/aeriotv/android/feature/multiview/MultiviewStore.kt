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

    private val _selected = MutableStateFlow<List<M3UChannel>>(emptyList())
    val selected: StateFlow<List<M3UChannel>> = _selected.asStateFlow()

    private val _audioFocusedIndex = MutableStateFlow(0)
    val audioFocusedIndex: StateFlow<Int> = _audioFocusedIndex.asStateFlow()

    fun isSelected(channel: M3UChannel): Boolean =
        _selected.value.any { it.id == channel.id }

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
            _selected.value = current + channel
        }
    }

    fun clear() {
        _selected.value = emptyList()
        _audioFocusedIndex.value = 0
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
    fun replaceTile(index: Int, newChannel: M3UChannel) {
        val current = _selected.value
        if (index !in current.indices) return
        if (current[index].id == newChannel.id) return
        val mutable = current.toMutableList()
        mutable[index] = newChannel
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
            "memory pressure (level=$level): shed ${current.size - 1} of ${current.size} tiles, kept ${keep.name}",
        )
    }
}
