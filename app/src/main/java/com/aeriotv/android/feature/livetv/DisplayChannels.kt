package com.aeriotv.android.feature.livetv

import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.guideMatchKey
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
    /** Recently watched channel ids, most recent first (Logan 2026-09-14). */
    recentIds: List<String> = emptyList(),
): List<M3UChannel> = computeLiveSearch(
    channels, selectedGroup, searchQuery, sortMode,
    allGroupNames, groupSortMode, hiddenGroups, favoriteIds, collections, recentIds,
).channels

/**
 * Live TV search result (Logan 2026-11-01: per-tab search on phones and
 * tablets matches programs too). [channels] holds the name or number matches
 * in the chosen sort, then the channels that matched only through a program,
 * ordered by that program's start (airing now first). [programMatches] maps
 * a program-only match's channel url to the program that matched, so the row
 * can say why it is there.
 */
internal class LiveSearchResult(
    val channels: List<M3UChannel>,
    val programMatches: Map<String, EPGProgramme> = emptyMap(),
)

/**
 * [computeDisplayChannels] plus program matching. [epgByChannel] is the
 * loaded guide map (guideMatchKey to programs); null skips program matching
 * (TV, which keeps its global Search tab). Memoized like the original; the
 * clock is part of the key at minute resolution.
 */
internal fun computeLiveSearch(
    channels: List<M3UChannel>,
    selectedGroup: String,
    searchQuery: String,
    sortMode: SortMode,
    allGroupNames: List<String>,
    groupSortMode: GroupSortMode,
    hiddenGroups: Set<String>,
    favoriteIds: Set<String>,
    collections: List<ChannelCollection>,
    recentIds: List<String> = emptyList(),
    epgByChannel: Map<String, List<EPGProgramme>>? = null,
    nowMillis: Long = System.currentTimeMillis(),
): LiveSearchResult {
    val epg = if (searchQuery.isBlank()) null else epgByChannel
    return GuideMemo.get(
        "displayChannels",
        listOf(
            GuideMemo.Ref(channels), selectedGroup, searchQuery, sortMode,
            GuideMemo.Ref(allGroupNames), groupSortMode, hiddenGroups, favoriteIds, GuideMemo.Ref(collections),
            recentIds, GuideMemo.Ref(epg), if (epg == null) 0L else nowMillis / 60_000L,
        ),
    ) {
        val t0 = System.nanoTime()
        val index = epg?.let { LiveProgramIndex.of(it) }
        val r = computeDisplayChannelsUncached(
            channels, selectedGroup, searchQuery, sortMode,
            allGroupNames, groupSortMode, hiddenGroups, favoriteIds, collections, recentIds,
            index, nowMillis,
        )
        val q = searchQuery.trim()
        if (q.isNotEmpty()) {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            runCatching {
                android.util.Log.i(
                    "AerioSearch",
                    "[SEARCH] live filter q=${q.length} channels=${r.channels.size - r.programMatches.size} " +
                        "programs=${r.programMatches.size} ms=$ms",
                )
            }
        }
        r
    }
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
    recentIds: List<String>,
    programIndex: LiveProgramIndex? = null,
    nowMillis: Long = System.currentTimeMillis(),
): LiveSearchResult {
    val query = searchQuery.trim()
    val programMatches = HashMap<String, EPGProgramme>()
    // Name or number match keeps the channel in the sorted block; otherwise
    // a program match (title or sub-title, now or upcoming) records why.
    fun keep(ch: M3UChannel): Boolean {
        if (query.isEmpty() || LiveSearchMatcher.channelMatches(ch, query)) return true
        val p = programIndex?.firstMatch(ch.guideMatchKey, query, nowMillis) ?: return false
        programMatches[ch.url] = p
        return true
    }
    fun split(sorted: List<M3UChannel>): LiveSearchResult {
        if (programMatches.isEmpty()) return LiveSearchResult(sorted)
        val (byProgram, byName) = sorted.partition { it.url in programMatches }
        return LiveSearchResult(
            byName + byProgram.sortedBy { programMatches[it.url]!!.startMillis },
            programMatches,
        )
    }
    // Recently Watched is its own order: the recents LRU, most recent first.
    // The chosen sort does not apply (the whole point of the group is the
    // order it was watched in); search still filters inside it.
    if (selectedGroup == PlaylistViewModel.RECENT_GROUP) {
        val rank = recentIds.withIndex().associate { (i, id) -> id to i }
        return split(
            channels.asSequence()
                .filter { it.id in rank }
                .filter { keep(it) }
                .sortedBy { rank[it.id] ?: Int.MAX_VALUE }
                .toList(),
        )
    }
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
    return split(channels.asSequence()
        .filter { ch ->
            when {
                collectionSelected -> collectionMembers?.contains(ch.id) ?: true
                selectedGroup == PlaylistViewModel.FAVORITES_GROUP -> ch.id in favoriteIds
                selectedGroup != PlaylistViewModel.ALL_GROUPS ->
                    ch.groupTitle.equals(selectedGroup, ignoreCase = true)
                query.isNotEmpty() -> true
                else -> ch.groupTitle !in hiddenGroups
            }
        }
        .filter { keep(it) }
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
        .toList())
}

/** Per-channel cached sort keys (E-1 stage 1). */
private class DisplaySortKey(
    val channel: M3UChannel,
    val rank: Int,
    val nameLower: String,
    val number: Double,
    val favorite: Boolean,
)
