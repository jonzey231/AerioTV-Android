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
    // The pinned Favorites token rides along wherever orderGroups placed it.
    val others = visibleGroups.filterNot { it == PlaylistViewModel.ALL_GROUPS }
    val allShown = PlaylistViewModel.ALL_GROUPS !in hiddenGroups || others.isEmpty()
    return if (PlaylistViewModel.ALL_GROUPS in visibleGroups) {
        if (allShown) visibleGroups else others
    } else {
        if (allShown) listOf(PlaylistViewModel.ALL_GROUPS) + others else others
    }
}

/**
 * Where a reset lands: All when it is shown, else the first visible group.
 * Not simply the first token: Favorites is pinned ahead of All, so a reset
 * would otherwise land on Favorites whenever the user has any.
 */
fun fallbackGroupToken(tokens: List<String>): String = when {
    PlaylistViewModel.ALL_GROUPS in tokens -> PlaylistViewModel.ALL_GROUPS
    else -> tokens.firstOrNull() ?: PlaylistViewModel.ALL_GROUPS
}

/**
 * GH #81: is [token] a synthetic group rather than a provider group name?
 * Synthetic groups (All, Favorites, and the collection tokens) exist without a
 * matching `groupTitle` on any channel, so anything that validates a selection
 * against the channel list has to exempt them.
 */
fun isSyntheticGroupToken(token: String): Boolean =
    token == PlaylistViewModel.ALL_GROUPS ||
        token == PlaylistViewModel.FAVORITES_GROUP ||
        token == PlaylistViewModel.RECENT_GROUP ||
        token.startsWith(com.aeriotv.android.core.data.ChannelCollection.TOKEN_PREFIX)

/**
 * GH #81: resolve a persisted Live TV group token against a playlist's live
 * group names.
 *
 * Returns the token to select, or null when nothing should be restored (so the
 * caller keeps its default of All). Synthetic tokens survive unconditionally:
 * Favorites is not a provider group, so matching it by name against the channel
 * list is exactly the bug that dropped it. Provider group names are matched
 * case-insensitively and returned in the live list's spelling, so a provider
 * that re-cases a group keeps the selection. When [knownGroupNames] is empty
 * the channels have not loaded yet and the token is accepted as-is; the
 * screens' own stranded-selection effect prunes it later if it never appears.
 */
fun restoredGroupToken(saved: String?, knownGroupNames: Collection<String>): String? {
    val token = saved?.trim().orEmpty()
    if (token.isEmpty()) return null
    if (isSyntheticGroupToken(token)) return token
    if (knownGroupNames.isEmpty()) return token
    return knownGroupNames.firstOrNull { it.equals(token, ignoreCase = true) }
}

/**
 * GH #81: the human label for a group token. The tokens are storage values,
 * not copy: [PlaylistViewModel.FAVORITES_GROUP] is the literal "__favorites__"
 * and it leaked into the Manage Groups sheet (screenshot 2026-09-13), while
 * [PlaylistViewModel.ALL_GROUPS] is the bare "All". Every site that renders a
 * token as text routes through here so there is exactly one mapping.
 *
 * Collection tokens ("collection:<id>") resolve to the collection's own name
 * when [collections] carries it; callers that have no collection list fall
 * back to the generic "Collection" rather than printing the id.
 */
@JvmOverloads
fun groupDisplayName(
    token: String,
    collections: List<com.aeriotv.android.core.data.ChannelCollection> = emptyList(),
): String = when {
    token == PlaylistViewModel.ALL_GROUPS -> "All Channels"
    token == PlaylistViewModel.FAVORITES_GROUP -> "Favorites"
    token == PlaylistViewModel.RECENT_GROUP -> "Recently Watched"
    token.startsWith(com.aeriotv.android.core.data.ChannelCollection.TOKEN_PREFIX) -> {
        val id = com.aeriotv.android.core.data.ChannelCollection.idFromToken(token)
        collections.firstOrNull { it.id == id }?.name ?: "Collection"
    }
    else -> token
}

/**
 * GH #81: which group Live TV LANDS on for a playlist (Logan 2026-10-28):
 * every cold start (launch picker or not, the picker keeping the active
 * profile included), every profile switch and every playlist activation opens
 * on the active profile's "Default Group" when it still resolves against
 * [knownGroupNames], else null (the caller's All Channels fallback).
 *
 * The last used group is deliberately NOT consulted: pick News, close the
 * app, reopen, and the guide is on the Default Group (All when unset), not on
 * News. The last used token is still stored per profile (and synced as
 * lastGroup); it only matters inside a session, where the selection is held
 * in memory across tab changes and player returns.
 */
fun landingGroupToken(
    defaultToken: String?,
    knownGroupNames: Collection<String>,
): String? = restoredGroupToken(defaultToken, knownGroupNames)

/**
 * Split what Manage Groups committed back into its two stores (Logan
 * 2026-09-14). The sheets know one mechanism, the hidden set, so the synthetic
 * Recently Watched group rides in it: absent from the set means checked, which
 * is the [com.aeriotv.android.core.preferences.AppPreferences.recentGroupVisible]
 * pref. Writes only what actually changed, so a Done with no edits costs no
 * DataStore round trip.
 */
fun applyManagedGroups(
    committed: Set<String>,
    currentHidden: Set<String>,
    currentRecentVisible: Boolean,
    setHiddenGroups: (Set<String>) -> Unit,
    setRecentVisible: (Boolean) -> Unit,
) {
    val hidden = committed - PlaylistViewModel.RECENT_GROUP
    val recentVisible = PlaylistViewModel.RECENT_GROUP !in committed
    if (hidden != currentHidden) setHiddenGroups(hidden)
    if (recentVisible != currentRecentVisible) setRecentVisible(recentVisible)
}
