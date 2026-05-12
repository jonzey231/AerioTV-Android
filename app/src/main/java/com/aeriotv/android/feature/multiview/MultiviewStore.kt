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
}
