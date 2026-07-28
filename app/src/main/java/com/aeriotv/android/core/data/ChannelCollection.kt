package com.aeriotv.android.core.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * iOS `ChannelCollection` parity (issue #45, FavoritesStore.swift): a
 * user-created, named grouping of channels surfaced as a filter pill in
 * Live TV alongside All / the server groups. The whole list persists as ONE
 * JSON blob in AppPreferences (`channel_collections`), device-local -- iOS
 * keeps collections out of iCloud KVS for v1 and Android likewise leaves
 * them out of Drive sync.
 *
 * [memberIds] hold the same channel ids Favorites use (provider-stable ids,
 * never per-parse UUIDs). Stale ids (channel no longer in the playlist) are
 * silently dropped at filter time but stay persisted, so they resurrect if
 * the channel comes back -- iOS behavior, no garbage collection.
 */
@Immutable
@Serializable
data class ChannelCollection(
    val id: String,
    val name: String,
    val memberIds: List<String> = emptyList(),
    /** Pill position: before ("beginning") or after ("end") the group pills. */
    val placement: String = PLACEMENT_END,
) {
    companion object {
        const val PLACEMENT_BEGINNING = "beginning"
        const val PLACEMENT_END = "end"

        /**
         * `selectedGroup` sentinel for an active collection filter (iOS
         * `"collection:<id>"` in ChannelListView.filterChannels).
         */
        const val TOKEN_PREFIX = "collection:"

        fun token(id: String): String = TOKEN_PREFIX + id

        /** The collection id inside a sentinel, or null for a plain group. */
        fun idFromToken(selectedGroup: String): String? =
            if (selectedGroup.startsWith(TOKEN_PREFIX)) {
                selectedGroup.removePrefix(TOKEN_PREFIX)
            } else {
                null
            }
    }
}
