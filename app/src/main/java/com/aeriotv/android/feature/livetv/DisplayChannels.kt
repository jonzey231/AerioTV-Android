package com.aeriotv.android.feature.livetv

import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.SortMode

/**
 * E-1 stage 2 (perf campaign 2026-08-19): the ONE filter+sort pipeline behind
 * the Guide grid and the channel List, extracted from their previously
 * duplicated `derivedStateOf` bodies so it can run OFF the main thread via
 * `produceState { withContext(Dispatchers.Default) { ... } }` at both call
 * sites. Semantics are the merged, identical behavior of both originals:
 *
 * - "collection:<id>" sentinel filters to the curated members, bypassing
 *   hidden groups; a dangling sentinel (deleted collection) shows everything;
 *   a real provider group literally named "collection:x" still filters as a
 *   group (GH #45).
 * - A specific group matches ignoring case. In "All", hidden groups are
 *   excluded UNLESS searching, where hidden channels stay findable.
 * - With a non-default group order active, "All" clusters by the ordered
 *   group list (rank primary), then the chosen sort applies within.
 * - Sort keys are precomputed per channel (E-1 stage 1): the comparators
 *   read cached fields only.
 */
/** Memoized front (see [GuideMemo]): re-entering a tab with the same inputs
 *  returns the last result instead of re-filtering and re-sorting the whole
 *  playlist. Large inputs key by reference, small ones by value. */
internal fun computeDisplayChannels(
    channels: List<M3UChannel>,
    selectedGroup: String,
    searchQuery: String,
    sortMode: SortMode,
    allGroupNames: List<String>,
    groupSortMode: GroupSortMode,
    hiddenGroups: Set<String>,
    favoriteIds: Set<String>,
    collections: List<ChannelCollection>,
): List<M3UChannel> = GuideMemo.get(
    "displayChannels",
    listOf(
        GuideMemo.Ref(channels), selectedGroup, searchQuery, sortMode,
        GuideMemo.Ref(allGroupNames), groupSortMode, hiddenGroups, favoriteIds, GuideMemo.Ref(collections),
    ),
) {
    computeDisplayChannelsUncached(
        channels, selectedGroup, searchQuery, sortMode,
        allGroupNames, groupSortMode, hiddenGroups, favoriteIds, collections,
    )
}

private fun computeDisplayChannelsUncached(
    channels: List<M3UChannel>,
    selectedGroup: String,
    searchQuery: String,
    sortMode: SortMode,
    allGroupNames: List<String>,
    groupSortMode: GroupSortMode,
    hiddenGroups: Set<String>,
    favoriteIds: Set<String>,
    collections: List<ChannelCollection>,
): List<M3UChannel> {
    val query = searchQuery.trim()
    val activeCollection = ChannelCollection.idFromToken(selectedGroup)
        ?.let { cid -> collections.firstOrNull { it.id == cid } }
    val collectionMembers = activeCollection?.memberIds?.toSet()
    val collectionSelected =
        selectedGroup.startsWith(ChannelCollection.TOKEN_PREFIX) &&
            allGroupNames.none { it == selectedGroup }
    val clusterByGroup = query.isEmpty() &&
        selectedGroup == PlaylistViewModel.ALL_GROUPS &&
        groupSortMode != GroupSortMode.Default
    val groupRankIndex = if (clusterByGroup) {
        allGroupNames.withIndex().associate { (i, g) -> g to i }
    } else {
        emptyMap()
    }
    return channels.asSequence()
        .filter { ch ->
            when {
                collectionSelected -> collectionMembers?.contains(ch.id) ?: true
                selectedGroup != PlaylistViewModel.ALL_GROUPS ->
                    ch.groupTitle.equals(selectedGroup, ignoreCase = true)
                query.isNotEmpty() -> true
                else -> ch.groupTitle !in hiddenGroups
            }
        }
        .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        .map { ch ->
            DisplaySortKey(
                channel = ch,
                rank = if (clusterByGroup) groupRankIndex[ch.groupTitle] ?: Int.MAX_VALUE else 0,
                nameLower = ch.name.lowercase(),
                number = ch.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE,
                favorite = ch.id in favoriteIds,
            )
        }
        .sortedWith(
            when (sortMode) {
                SortMode.ByName -> compareBy({ it.rank }, { it.nameLower })
                SortMode.FavoritesFirst -> compareBy(
                    { it.rank },
                    { !it.favorite }, // favorited sorts first
                    { it.number },
                    { it.nameLower },
                )
                SortMode.ByNumber -> compareBy({ it.rank }, { it.number }, { it.nameLower })
            },
        )
        .map { it.channel }
        .toList()
}

/** Per-channel cached sort keys (E-1 stage 1). */
private class DisplaySortKey(
    val channel: M3UChannel,
    val rank: Int,
    val nameLower: String,
    val number: Double,
    val favorite: Boolean,
)
