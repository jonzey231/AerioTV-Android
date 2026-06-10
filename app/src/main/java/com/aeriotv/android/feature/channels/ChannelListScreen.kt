package com.aeriotv.android.feature.channels

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.ui.LocalCanRecordToServer
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.feature.favorites.FavoritesViewModel
import com.aeriotv.android.feature.livetv.LiveTVViewMode
import com.aeriotv.android.ui.tv.tvFocusScale
import com.aeriotv.android.feature.livetv.ManageGroupsSheet
import com.aeriotv.android.feature.livetv.ProgramInfoSheet
import com.aeriotv.android.feature.livetv.RecordProgramSheet
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.SortMode
import com.aeriotv.android.feature.playlist.nowPlaying
import com.aeriotv.android.feature.reminders.RemindersViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.core.data.db.entity.reminderKey
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    onChannelClick: (M3UChannel) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
    modifierWrap: Modifier = Modifier,
    viewMode: LiveTVViewMode = LiveTVViewMode.List,
    canToggleViewMode: Boolean = false,
    onToggleViewMode: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favoritesVm: FavoritesViewModel = hiltViewModel()
    val favoritesList by favoritesVm.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds = remember(favoritesList) { favoritesList.map { it.channelId }.toSet() }
    val settingsVm: SettingsViewModel = hiltViewModel()
    val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)
    val hiddenGroups by settingsVm.hiddenGroups.collectAsStateWithLifecycle(initialValue = emptySet())
    val showChannelLogos by settingsVm.showChannelLogos.collectAsStateWithLifecycle(initialValue = true)

    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var manageGroupsOpen by remember { mutableStateOf(false) }

    // Preserve the order groups appear in the source channel list. iOS does
    // this on the parse step (HomeView.swift `fetchM3U` lines 1869-1872) — an
    // empty array seeded with the first-seen groupTitle per channel and
    // `firstIndex(of:)` mapped onto each ChannelDisplayItem.categoryOrder.
    // Earlier Android revisions sorted alphabetically, which scrambled
    // Dispatcharr's curated group ordering. Sequence.distinct() preserves
    // encounter order so dropping the .sortedBy gets us iOS-matching behavior.
    val allGroupsRaw by remember(state.channels) {
        derivedStateOf {
            state.channels.asSequence()
                .map { it.groupTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }
    }

    val groups by remember(allGroupsRaw, hiddenGroups) {
        derivedStateOf {
            val visible = allGroupsRaw.filterNot { it in hiddenGroups }
            listOf(PlaylistViewModel.ALL_GROUPS) + visible
        }
    }

    val filtered by remember(state.channels, state.searchQuery, state.selectedGroup, state.sortMode, favoriteIds, hiddenGroups) {
        derivedStateOf {
            val query = state.searchQuery.trim()
            val byGroupAndSearch = state.channels.asSequence()
                .filter {
                    // "All" still respects hidden-group filters so toggling a group off
                    // truly hides its channels from the default list. The user can find
                    // them again via search (which ignores the hidden set).
                    when {
                        state.selectedGroup == PlaylistViewModel.ALL_GROUPS && query.isEmpty() ->
                            it.groupTitle !in hiddenGroups
                        state.selectedGroup == PlaylistViewModel.ALL_GROUPS -> true
                        else -> it.groupTitle.equals(state.selectedGroup, ignoreCase = true)
                    }
                }
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .toList()
            when (state.sortMode) {
                SortMode.ByNumber -> byGroupAndSearch.sortedWith(
                    compareBy({ it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name.lowercase() }),
                )
                SortMode.ByName -> byGroupAndSearch.sortedBy { it.name.lowercase() }
                SortMode.FavoritesFirst -> byGroupAndSearch.sortedWith(
                    compareBy(
                        { it.id !in favoriteIds }, // false (favorited) sorts before true
                        { it.channelNumber?.toDoubleOrNull() ?: Double.MAX_VALUE },
                        { it.name.lowercase() },
                    ),
                )
            }
        }
    }

    Column(modifier = modifierWrap.fillMaxSize()) {
        // CenterAlignedTopAppBar (not the standard TopAppBar) centers the
        // title within the bar regardless of action button width on the
        // trailing edge, matching iOS `.navigationBarTitleDisplayMode(.inline)`
        // which iOS UIKit centers automatically (ChannelListView.swift:191).
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Live TV",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            actions = {
                if (canToggleViewMode) {
                    IconButton(onClick = onToggleViewMode) {
                        Icon(
                            imageVector = if (viewMode == LiveTVViewMode.Guide)
                                Icons.Filled.ViewList else Icons.Filled.CalendarMonth,
                            contentDescription = if (viewMode == LiveTVViewMode.Guide)
                                "Switch to List" else "Switch to Guide",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                SortMenu(
                    currentMode = state.sortMode,
                    onSelect = viewModel::onSortModeChange,
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("Search channels") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            // Trailing X clears the query in one tap. iOS UISearchBar has
            // this for free; Compose's OutlinedTextField doesn't, so we
            // surface it conditionally on non-empty input.
            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else null,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
        )

        // Audit task #51 final piece: collapse the chip Row when the user
        // scrolls past the top of the channel list, restore it when they
        // scroll back to row 0. Listening on the LazyColumn's
        // firstVisibleItemIndex via a hoisted state. The
        // AnimatedVisibility's expand/shrink-vertically gives a smooth
        // height collapse without snapping. Stays expanded on a
        // not-yet-scrolled list (initial state).
        val listState = rememberLazyListState()
        val chipsVisible by remember {
            derivedStateOf { listState.firstVisibleItemIndex == 0 }
        }
        AnimatedVisibility(
            visible = chipsVisible && groups.size > 1,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .clickable { manageGroupsOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = if (hiddenGroups.isEmpty())
                                "Manage groups"
                            else
                                "Manage groups (${hiddenGroups.size} hidden)",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        // iOS parity: warning dot in the top-right indicates
                        // the user has at least one group hidden. Without
                        // this, a user who hid groups months ago has no
                        // visual cue to remember why the chip list looks
                        // short. ManageGroupsButton in Aerio/Design/Components
                        // /ManageGroupsSheet.swift uses the same pattern.
                        if (hiddenGroups.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 6.dp, end = 6.dp)
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFFFFA502)),
                            )
                        }
                    }
                }
                items(groups, key = { it }) { group ->
                    FilterChip(
                        selected = state.selectedGroup == group,
                        onClick = { viewModel.onGroupSelected(group) },
                        label = { Text(group) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant,
            thickness = 0.5.dp,
        )

        // Audit task #51 (partial): pull-to-refresh on the Live TV list.
        // Drags the spinner from the top of the LazyColumn and invokes
        // PlaylistViewModel.refreshPlaylist(), which re-fetches the channel
        // list and the EPG. The spinner stays visible while
        // state.isLoading is true. PullToRefreshBox lets the inner
        // LazyColumn handle its own vertical scroll; we don't lose the
        // sticky chips above.
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refreshPlaylist() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 104dp bottom clears the MainScaffold NavigationBar so the
                // final channel row stays fully visible above the tab bar.
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = filtered, key = { it.id }) { channel ->
                    val programmes = state.epgByChannel[channel.guideMatchKey].orEmpty()
                    val nowProgramme = programmes.nowPlaying()
                    ChannelRow(
                        channel = channel,
                        nowProgramme = nowProgramme,
                        programmes = programmes,
                        isFavorite = channel.id in favoriteIds,
                        onPlay = { onChannelClick(channel) },
                        onToggleFavorite = { favoritesVm.toggle(channel) },
                        onShowProgramInfo = { programInfoTarget = it },
                        onShowRecord = { recordTarget = it },
                        palette = palette,
                        showLogo = showChannelLogos,
                    )
                }
            }
        }
    }

    programInfoTarget?.let { target ->
        ProgramInfoSheet(
            target = target,
            onDismiss = { programInfoTarget = null },
        )
    }
    recordTarget?.let { target ->
        RecordProgramSheet(
            target = target,
            onDismiss = { recordTarget = null },
        )
    }
    if (manageGroupsOpen) {
        ManageGroupsSheet(
            allGroups = allGroupsRaw,
            hiddenGroups = hiddenGroups,
            onSave = { settingsVm.setHiddenGroups(it) },
            onDismiss = { manageGroupsOpen = false },
        )
    }
}

@Composable
private fun SortMenu(
    currentMode: SortMode,
    onSelect: (SortMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = "Sort channels",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    // Leading checkmark on the active mode mirrors iOS
                    // SwiftUI Menu's Label("...", systemImage: "checkmark")
                    // pattern. Without this, the only signal for the active
                    // mode was the primary-tint label, which was easy to
                    // miss against the dark menu surface.
                    leadingIcon = if (mode == currentMode) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                    text = {
                        Text(
                            text = mode.label,
                            color = if (mode == currentMode)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (mode == currentMode) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Channel row matching iOS Live TV canon: dense [#][logo] title + EPG metadata
 * stack, with time-remaining + more + chevron on the right. Tap plays. Long-press
 * opens the iOS-canon menu (Favorites / Program Info / Record from Now). Chevron
 * toggles an inline upcoming-programmes panel.
 */
// Phase 57b: bumped to `internal` so the Favorites tab can reuse the
// same dense row + EPG-aware long-press menu the Live TV list uses.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelRow(
    channel: M3UChannel,
    nowProgramme: EPGProgramme?,
    programmes: List<EPGProgramme>,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowProgramInfo: (ProgramInfoTarget) -> Unit,
    onShowRecord: (ProgramInfoTarget) -> Unit,
    palette: CategoryPaletteState,
    /** iOS Issue #28: when false the channel logo is omitted so long channel
     *  names use the full row width. */
    showLogo: Boolean = true,
    /**
     * Optional leading drag handle, rendered at the very start of the row
     * before the channel number. Only the Favorites tab passes this (to back
     * its drag-to-reorder gesture); Live TV leaves it null so the row is
     * unchanged there. The handle owns the drag gesture so tap-to-play +
     * long-press-menu on the rest of the row stay intact.
     */
    reorderHandle: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val menuGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()

    // Category gradient runs cyan-of-card → category-tint when a now-playing
    // programme has a recognised category and the user has Category Colors on.
    // Mirrors iPhone canon footer: "cards tint via gradient" — colours the
    // accent edge without flattening the surface.
    //
    // Dispatcharr's bulk EPG grid drops `<category>` so nowProgramme.category
    // is typically blank for Dispatcharr-sourced playlists; the channel's
    // groupTitle ("Sports", "Movies HD", "Kids", "News" etc.) is passed as a
    // fallback so the row still tints when the program-level category isn't
    // available. The per-program lazy fetch in ProgramInfoSheet still wins
    // when the user opens the detail sheet.
    val tint = palette.tintFor(
        rawCategory = nowProgramme?.category,
        isLive = true,
        fallback = channel.groupTitle,
    )
    val baseSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    val cardBrush = if (tint != null) {
        Brush.horizontalGradient(listOf(tint, baseSurface))
    } else {
        Brush.horizontalGradient(listOf(baseSurface, baseSurface))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvFocusScale(focused)
            .clip(RoundedCornerShape(12.dp))
            .background(cardBrush)
            .then(
                // D-pad focus ring for the TV List view (the guide is the TV
                // default, but the List/Guide toggle reaches this row too).
                // Phone rows never gain focus, so `focused` stays false and
                // this is a no-op there.
                if (focused) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp),
                ) else Modifier,
            ),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onPlay,
                        onLongClick = { menuOpen = true; menuGuard.arm() },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reorderHandle != null) {
                    reorderHandle()
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier.width(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    channel.channelNumber?.let { num ->
                        Text(
                            text = num.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))

                // Channel logo — matches iOS ChannelListView.swift:1754-1758
                // `CachedLogoImage(width: 38, height: 26)` on iPhone. The
                // logo sits directly on the row's gradient surface with no
                // background tile: tvg-logo art is already designed to be
                // overlaid on dark surfaces (most ship transparent PNGs or
                // tightly-cropped raster), and the iOS canon screenshot the
                // user provided shows ESPN/NBC/NFL logos floating directly
                // on the card. The container is wider than tall so wider
                // logos like NBC Sports fit comfortably without cropping.
                if (showLogo) {
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .height(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (channel.tvgLogo.isNotBlank()) {
                            AsyncImage(
                                model = channel.tvgLogo,
                                contentDescription = null,
                                // Fit preserves aspect ratio and centers within
                                // the 50x32 container, matching iOS SwiftUI's
                                // default Image.resizable + scaledToFit() pair.
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .width(50.dp)
                                    .height(32.dp),
                            )
                        } else {
                            Text(
                                text = channel.name.take(2).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }

                // Uniform-height channel-info column. iOS lets the description
                // wrap 0-2 lines, which lets cards spring up and down by
                // ~14 dp depending on EPG. The Android list ended up
                // visually choppy in landscape on phones — every other row
                // a different height — so we lock the slot heights:
                //   * Channel name: always 1 line
                //   * Program title: always 1 line (placeholder space when
                //     no EPG so the column still occupies that slot)
                //   * Description: always exactly 2 lines (minLines == max)
                //   * Progress bar: always 2 dp tall (transparent spacer
                //     when no EPG)
                // Empty slots use a thin-space character so the Text node
                // still measures its proper line height. Mirrors iOS
                // intent (ChannelListView.swift:1782 comment "subtitle slot
                // stays empty") but enforces measurement parity Android-
                // side, which iOS gets for free from its SwiftUI VStack
                // layout pass.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = nowProgramme?.title ?: " ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = nowProgramme?.description?.takeIf { it.isNotBlank() } ?: " ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(4.dp))
                    if (nowProgramme != null) {
                        EpgProgressBar(nowProgramme)
                    } else {
                        // Transparent placeholder keeps the 2 dp progress-
                        // bar slot reserved so all rows align even when
                        // EPG hasn't loaded for this channel.
                        Spacer(Modifier.height(2.dp))
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    nowProgramme?.let {
                        Text(
                            text = formatRemaining(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreHoriz,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(
                            onClick = { isExpanded = !isExpanded },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand schedule",
                                tint = if (isExpanded) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // Channel long-press menu — iOS canon (ChannelListView.swift:1873).
            // Add/Remove from Favorites is a UI stub: the FavoritesStore lands
            // with the conditional-tab work. Record from Now opens the sheet
            // shell whose action toasts until Phase 9 DVR lands.
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = null,
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = {
                        Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites")
                    },
                    onClick = menuGuard.wrap {
                        menuOpen = false
                        onToggleFavorite()
                    },
                )
                if (nowProgramme != null) {
                    DropdownMenuItem(
                        text = { Text("Program Info") },
                        onClick = menuGuard.wrap {
                            menuOpen = false
                            onShowProgramInfo(nowProgramme.toInfoTarget(channel.name, channel.dispatcharrChannelId))
                        },
                    )
                }
                // Record row only surfaces for Dispatcharr-sourced channels
                // (the only path that has server-side scheduling today).
                // Showing-then-toasting on M3U / Xtream rows was bad UX --
                // the user tapped, got a "DVR requires Dispatcharr" toast,
                // then had no way to actually start a recording. Mirrors
                // iOS canon: the channel long-press menu hides irrelevant
                // actions instead of greying them out for context-free
                // sources.
                if (channel.url.isNotBlank() && channel.dispatcharrChannelId != null && LocalCanRecordToServer.current) {
                    val recordLabel = if (nowProgramme != null) "Record from Now" else "Record"
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                tint = Color(0xFFFF4757),
                            )
                        },
                        text = { Text(recordLabel) },
                        onClick = menuGuard.wrap {
                            menuOpen = false
                            val now = System.currentTimeMillis()
                            val target = nowProgramme?.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                                ?: ProgramInfoTarget(
                                    channelName = channel.name,
                                    title = "${channel.name} live recording",
                                    startMillis = now,
                                    endMillis = now + 3_600_000L,
                                    description = "",
                                    category = "",
                                    channelDispatcharrId = channel.dispatcharrChannelId,
                                )
                            onShowRecord(target)
                        },
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            ChannelGuidePanel(
                channelName = channel.name,
                channelId = channel.id,
                channelDispatcharrId = channel.dispatcharrChannelId,
                programmes = programmes,
                onShowProgramInfo = onShowProgramInfo,
                onShowRecord = onShowRecord,
            )
        }
    }
}

/**
 * Inline upcoming-schedule panel that appears below an expanded ChannelRow.
 * Mirrors iOS `guidePanel` (ChannelListView.swift:2149). Each programme row tap
 * opens ProgramInfoSheet. Long-press opens a Material 3 dropdown with Program
 * Info + Record options, matching the iOS popover at the same code site.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelGuidePanel(
    channelName: String,
    channelId: String,
    channelDispatcharrId: Int?,
    programmes: List<EPGProgramme>,
    onShowProgramInfo: (ProgramInfoTarget) -> Unit,
    onShowRecord: (ProgramInfoTarget) -> Unit,
) {
    val now = System.currentTimeMillis()
    val current = programmes.nowPlaying(now)
    val upcoming = remember(programmes, current) {
        programmes
            .asSequence()
            .filter { it.endMillis > now && it != current }
            .sortedBy { it.startMillis }
            .toList()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        if (upcoming.isEmpty()) {
            Text(
                text = "No upcoming schedule available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        } else {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                upcoming.forEach { programme ->
                    UpcomingProgrammeRow(
                        programme = programme,
                        channelName = channelName,
                        channelId = channelId,
                        onTap = { onShowProgramInfo(programme.toInfoTarget(channelName, channelDispatcharrId)) },
                        onShowRecord = { onShowRecord(programme.toInfoTarget(channelName, channelDispatcharrId)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpcomingProgrammeRow(
    programme: EPGProgramme,
    channelName: String,
    channelId: String,
    onTap: () -> Unit,
    onShowRecord: () -> Unit,
    remindersVm: RemindersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val menuGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    val key = remember(programme, channelName) {
        reminderKey(channelName, programme.title, programme.startMillis)
    }
    val isReminderSet by remindersVm.observeIsSet(key)
        .collectAsStateWithLifecycle(initialValue = false)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = { menuOpen = true; menuGuard.arm() },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = programme.title.ifBlank { "–" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (programme.description.isNotBlank()) {
                    Text(
                        text = programme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTimeRange(programme),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DropdownMenuItem(
                text = { Text("Program Info") },
                onClick = menuGuard.wrap {
                    menuOpen = false
                    onTap()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(if (isReminderSet) "Cancel Reminder" else "Set Reminder")
                },
                onClick = menuGuard.wrap {
                    menuOpen = false
                    if (isReminderSet) {
                        remindersVm.cancelReminder(key)
                        Toast.makeText(context, "Reminder cancelled.", Toast.LENGTH_SHORT).show()
                    } else {
                        remindersVm.setReminder(
                            channelName = channelName,
                            programTitle = programme.title,
                            startMillis = programme.startMillis,
                            endMillis = programme.endMillis,
                            channelId = channelId,
                        )
                        Toast.makeText(context, "Reminder set.", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Record") },
                onClick = menuGuard.wrap {
                    menuOpen = false
                    onShowRecord()
                },
            )
        }
    }
}

@Composable
private fun EpgProgressBar(programme: EPGProgramme) {
    val now = System.currentTimeMillis()
    val total = (programme.endMillis - programme.startMillis).coerceAtLeast(1L)
    val elapsed = (now - programme.startMillis).coerceAtLeast(0L).coerceAtMost(total)
    val progress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
    )
}

private fun formatRemaining(programme: EPGProgramme): String {
    val remainingMs = (programme.endMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val minutes = remainingMs / 60_000L
    if (minutes <= 0L) return "ending"
    if (minutes < 60L) return "${minutes}m"
    val hours = minutes / 60L
    val leftover = minutes % 60L
    return if (leftover == 0L) "${hours}h" else "${hours}h ${leftover}m"
}

private fun formatTimeRange(programme: EPGProgramme): String {
    val timeFormat = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    val start = timeFormat.format(java.util.Date(programme.startMillis))
    val end = timeFormat.format(java.util.Date(programme.endMillis))
    return "$start – $end"
}
