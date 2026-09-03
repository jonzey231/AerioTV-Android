package com.aeriotv.android.feature.livetv

import com.aeriotv.android.feature.playlist.PlaylistViewModel

/**
 * GH #80: the "All Channels" pill can be hidden like any group. The hidden
 * set carries the [PlaylistViewModel.ALL_GROUPS] sentinel for it. It is kept
 * whenever nothing else would be left to pick, so the guide never empties.
 */
fun groupTokens(visibleGroups: List<String>, hiddenGroups: Set<String>): List<String> =
    if (PlaylistViewModel.ALL_GROUPS in hiddenGroups && visibleGroups.isNotEmpty()) visibleGroups
    else listOf(PlaylistViewModel.ALL_GROUPS) + visibleGroups

/** Where a reset lands: All when it is shown, else the first visible group. */
fun fallbackGroupToken(tokens: List<String>): String =
    tokens.firstOrNull() ?: PlaylistViewModel.ALL_GROUPS
