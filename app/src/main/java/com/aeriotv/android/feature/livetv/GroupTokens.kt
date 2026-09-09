package com.aeriotv.android.feature.livetv

import com.aeriotv.android.feature.playlist.PlaylistViewModel

/**
 * GH #80: the "All Channels" pill can be hidden like any group. The hidden
 * set carries the [PlaylistViewModel.ALL_GROUPS] sentinel for it. It is kept
 * whenever nothing else would be left to pick, so the guide never empties.
 */
fun groupTokens(visibleGroups: List<String>, hiddenGroups: Set<String>): List<String> {
    // orderGroups now places the All token inside the list (any group can
    // sit above it in Manual order); keep that position when it is there.
    val others = visibleGroups.filterNot { it == PlaylistViewModel.ALL_GROUPS }
    val allShown = PlaylistViewModel.ALL_GROUPS !in hiddenGroups || others.isEmpty()
    return if (PlaylistViewModel.ALL_GROUPS in visibleGroups) {
        if (allShown) visibleGroups else others
    } else {
        if (allShown) listOf(PlaylistViewModel.ALL_GROUPS) + others else others
    }
}

/** Where a reset lands: All when it is shown, else the first visible group. */
fun fallbackGroupToken(tokens: List<String>): String =
    tokens.firstOrNull() ?: PlaylistViewModel.ALL_GROUPS
