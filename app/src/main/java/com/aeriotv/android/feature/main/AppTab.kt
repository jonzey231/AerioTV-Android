package com.aeriotv.android.feature.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Mirrors iOS Aerio/Features/Home/HomeView.swift AppTab enum (lines 2783-2787):
 * liveTV, favorites, dvr, onDemand, settings.
 *
 * Per iOS canon, the tab BAR only renders tabs that have applicable content:
 *  - Live TV: always visible
 *  - Settings: always visible
 *  - Favorites: only when the user has favorited at least one channel
 *    (Phase 5+; not surfaced today since the Favorites store is empty)
 *  - DVR: only when the active source supports server-side recordings
 *    (Dispatcharr + Xtream do, raw M3U does not)
 *  - On Demand: only when the active source serves VOD (Movies / Series)
 *
 * See [com.aeriotv.android.feature.main.MainScaffold.visibleTabs] for the
 * predicate that drives this. The enum carries every possible tab; the
 * scaffold filters down to what's actionable for the current source.
 */
enum class AppTab(
    val id: String,
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
) {
    LiveTV(
        id = "livetv",
        label = "Live TV",
        iconSelected = Icons.Filled.LiveTv,
        iconUnselected = Icons.Outlined.LiveTv,
    ),
    Favorites(
        id = "favorites",
        label = "Favorites",
        iconSelected = Icons.Filled.Favorite,
        iconUnselected = Icons.Outlined.Favorite,
    ),
    DVR(
        id = "dvr",
        label = "DVR",
        iconSelected = Icons.Filled.Tv,
        iconUnselected = Icons.Outlined.Tv,
    ),
    OnDemand(
        id = "ondemand",
        label = "On Demand",
        iconSelected = Icons.Filled.OndemandVideo,
        iconUnselected = Icons.Outlined.OndemandVideo,
    ),
    Settings(
        id = "settings",
        label = "Settings",
        iconSelected = Icons.Filled.Settings,
        iconUnselected = Icons.Outlined.Settings,
    ),
}
