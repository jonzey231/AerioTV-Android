package com.aeriotv.android.feature.livetv

import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GH #81: the saved Live TV group must restore for synthetic groups too. */
class GroupTokensTest {

    private val groups = listOf("News", "Sports", "Movies")

    @Test
    fun favoritesTokenRestoresEvenThoughNoChannelCarriesThatGroupName() {
        assertEquals(
            PlaylistViewModel.FAVORITES_GROUP,
            restoredGroupToken(PlaylistViewModel.FAVORITES_GROUP, groups),
        )
    }

    @Test
    fun favoritesTokenRestoresBeforeChannelsHaveLoaded() {
        assertEquals(
            PlaylistViewModel.FAVORITES_GROUP,
            restoredGroupToken(PlaylistViewModel.FAVORITES_GROUP, emptyList()),
        )
    }

    @Test
    fun allAndCollectionTokensRestore() {
        assertEquals(PlaylistViewModel.ALL_GROUPS, restoredGroupToken(PlaylistViewModel.ALL_GROUPS, groups))
        val collection = ChannelCollection.token("abc")
        assertEquals(collection, restoredGroupToken(collection, groups))
    }

    @Test
    fun providerGroupRestoresInTheLiveSpelling() {
        assertEquals("Sports", restoredGroupToken("sports", groups))
    }

    @Test
    fun unknownProviderGroupDoesNotRestore() {
        assertNull(restoredGroupToken("Gone", groups))
    }

    @Test
    fun blankOrMissingTokenDoesNotRestore() {
        assertNull(restoredGroupToken(null, groups))
        assertNull(restoredGroupToken("   ", groups))
    }

    @Test
    fun syntheticTokensAreRecognized() {
        assertTrue(isSyntheticGroupToken(PlaylistViewModel.FAVORITES_GROUP))
        assertTrue(isSyntheticGroupToken(PlaylistViewModel.ALL_GROUPS))
        assertTrue(isSyntheticGroupToken(PlaylistViewModel.RECENT_GROUP))
        assertTrue(isSyntheticGroupToken(ChannelCollection.token("1")))
    }

    @Test
    fun favoritesSurvivesGroupTokenAssemblyWhenItIsInTheOrderedList() {
        val ordered = orderGroups(groups, GroupSortMode.Manual, listOf(PlaylistViewModel.FAVORITES_GROUP), hasFavorites = true)
        assertEquals(PlaylistViewModel.FAVORITES_GROUP, ordered.first())
        val tokens = groupTokens(ordered, emptySet())
        assertEquals(PlaylistViewModel.FAVORITES_GROUP, tokens.first())
    }

    // ---- GH #81: display names -------------------------------------------

    @Test
    fun syntheticTokensGetHumanLabels() {
        assertEquals("Favorites", groupDisplayName(PlaylistViewModel.FAVORITES_GROUP))
        assertEquals("All Channels", groupDisplayName(PlaylistViewModel.ALL_GROUPS))
        assertEquals("Recently Watched", groupDisplayName(PlaylistViewModel.RECENT_GROUP))
    }

    @Test
    fun providerGroupNameIsItsOwnLabel() {
        assertEquals("Sports", groupDisplayName("Sports"))
    }

    @Test
    fun collectionTokenUsesTheCollectionName() {
        val collection = ChannelCollection(id = "abc", name = "Late Night")
        assertEquals(
            "Late Night",
            groupDisplayName(ChannelCollection.token("abc"), listOf(collection)),
        )
    }

    @Test
    fun unknownCollectionTokenNeverPrintsTheRawToken() {
        assertEquals("Collection", groupDisplayName(ChannelCollection.token("gone")))
    }

    // ---- GH #81: default group resolution ---------------------------------

    @Test
    fun landingUsesTheDefaultGroupWhenSet() {
        assertEquals("News", landingGroupToken("News", groups))
    }

    @Test
    fun staleDefaultFallsBackToAll() {
        assertNull(landingGroupToken("Gone", groups))
    }

    /**
     * Logan 2026-09-14: Recently Watched is a real group token now, so it is a
     * Default Group like any other and restores unconditionally (no channel
     * carries that group name).
     */
    @Test
    fun recentlyWatchedIsADefaultGroupLikeAnyOther() {
        assertEquals(
            PlaylistViewModel.RECENT_GROUP,
            landingGroupToken(PlaylistViewModel.RECENT_GROUP, groups),
        )
        assertEquals(
            PlaylistViewModel.RECENT_GROUP,
            restoredGroupToken(PlaylistViewModel.RECENT_GROUP, emptyList()),
        )
    }

    /** The Recently Watched token is pinned into the ordered list and hides
     *  like any group through the hidden set. */
    @Test
    fun recentlyWatchedIsPinnedAndHideable() {
        val ordered = orderGroups(groups, GroupSortMode.Default, emptyList(), hasRecent = true)
        assertTrue(PlaylistViewModel.RECENT_GROUP in ordered)
        val hidden = setOf(PlaylistViewModel.RECENT_GROUP)
        val tokens = groupTokens(ordered.filterNot { it in hidden }, hidden)
        assertTrue(PlaylistViewModel.RECENT_GROUP !in tokens)
    }

    /** Manage Groups commits one hidden set; the Recently Watched token in it
     *  is the visibility pref, not a hidden provider group. */
    @Test
    fun managedGroupsSplitsTheRecentlyWatchedVisibility() {
        var hidden: Set<String>? = null
        var visible: Boolean? = null
        applyManagedGroups(
            committed = setOf("News", PlaylistViewModel.RECENT_GROUP),
            currentHidden = emptySet(),
            currentRecentVisible = true,
            setHiddenGroups = { hidden = it },
            setRecentVisible = { visible = it },
        )
        assertEquals(setOf("News"), hidden)
        assertEquals(false, visible)

        // Checking it writes the pref and leaves the hidden set alone.
        hidden = null
        visible = null
        applyManagedGroups(
            committed = setOf("News"),
            currentHidden = setOf("News"),
            currentRecentVisible = false,
            setHiddenGroups = { hidden = it },
            setRecentVisible = { visible = it },
        )
        assertNull(hidden)
        assertEquals(true, visible)
    }

    @Test
    fun nothingResolvesWhenTheDefaultIsStaleOrEmpty() {
        assertNull(landingGroupToken("Gone", groups))
        assertNull(landingGroupToken("", groups))
    }

    @Test
    fun favoritesIsAValidDefaultGroup() {
        assertEquals(
            PlaylistViewModel.FAVORITES_GROUP,
            landingGroupToken(PlaylistViewModel.FAVORITES_GROUP, groups),
        )
    }

    @Test
    fun fallbackPrefersAllOverAPinnedFavoritesToken() {
        val fav = PlaylistViewModel.FAVORITES_GROUP
        val all = PlaylistViewModel.ALL_GROUPS
        assertEquals(all, fallbackGroupToken(listOf(fav, all, "News")))
        assertEquals("News", fallbackGroupToken(listOf("News", "Sports")))
        assertEquals(fav, fallbackGroupToken(listOf(fav, "News")))
        assertEquals(all, fallbackGroupToken(emptyList()))
    }

    // Landing (Logan 2026-10-28): cold start, profile switch and playlist
    // activation all land on the Default Group or All, never the last used group.

    @Test
    fun landingOnTheDefaultGroupIncludesSyntheticTokens() {
        assertEquals("Sports", landingGroupToken("Sports", groups))
        assertEquals(
            PlaylistViewModel.FAVORITES_GROUP,
            landingGroupToken(PlaylistViewModel.FAVORITES_GROUP, groups),
        )
    }

    @Test
    fun landingWithNoDefaultIsAllNotTheLastUsedGroup() {
        // The resolver takes no last used token at all: an unset default is
        // null (All), whatever group the profile was left on.
        assertNull(landingGroupToken("", groups))
        assertNull(landingGroupToken(null, groups))
    }

    @Test
    fun landingWithADefaultTheProviderDroppedIsAll() {
        assertNull(landingGroupToken("Gone", groups))
    }
}
