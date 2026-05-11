package com.aeriotv.android.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.playlist.SortMode
import com.aeriotv.android.feature.playlist.nowPlaying

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    onChannelClick: (M3UChannel) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
    modifierWrap: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val groups by remember(state.channels) {
        derivedStateOf {
            val unique = state.channels.asSequence()
                .map { it.groupTitle }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedBy { it.lowercase() }
                .toList()
            listOf(PlaylistViewModel.ALL_GROUPS) + unique
        }
    }

    val filtered by remember(state.channels, state.searchQuery, state.selectedGroup, state.sortMode) {
        derivedStateOf {
            val query = state.searchQuery.trim()
            val byGroupAndSearch = state.channels.asSequence()
                .filter {
                    state.selectedGroup == PlaylistViewModel.ALL_GROUPS ||
                            it.groupTitle.equals(state.selectedGroup, ignoreCase = true)
                }
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .toList()
            when (state.sortMode) {
                SortMode.ByNumber -> byGroupAndSearch.sortedWith(
                    compareBy({ it.channelNumber ?: Int.MAX_VALUE }, { it.name.lowercase() }),
                )
                SortMode.ByName -> byGroupAndSearch.sortedBy { it.name.lowercase() }
                SortMode.FavoritesFirst -> byGroupAndSearch
                    .sortedWith(compareBy({ it.channelNumber ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                // TODO Phase 5: actually surface favorited channels to the top once the
                // Favorites store lands. For now this falls back to ByNumber ordering.
            }
        }
    }

    Column(modifier = modifierWrap.fillMaxSize()) {
        TopAppBar(
            title = {
                val playlistName = state.playlist?.name?.takeIf { it.isNotBlank() } ?: "Live TV"
                Text(
                    text = "$playlistName  •  ${filtered.size} / ${state.channels.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            actions = {
                SortMenu(
                    currentMode = state.sortMode,
                    onSelect = viewModel::onSortModeChange,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
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
            shape = RoundedCornerShape(14.dp),
        )

        if (groups.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Leading filter / manage-groups icon, iOS canon. Stub for Phase 5 -
                // tapping does nothing yet; Manage Groups screen lands with the
                // Favorites store work.
                item {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .clickable(enabled = false) { /* TODO Phase 5: open Manage Groups */ },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "Manage groups",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = filtered, key = { it.id }) { channel ->
                val nowProgramme = state.epgByChannel[channel.tvgID]?.nowPlaying()
                ChannelRow(
                    channel = channel,
                    nowProgramme = nowProgramme,
                    onClick = { onChannelClick(channel) },
                )
            }
        }
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
 * Channel row matching iOS Live TV canon (App Store screenshots IMG_1075..IMG_1081
 * + exploration screenshots 16:40-16:45 on 2026-05-11):
 *
 *   [#]  [logo]  Channel name                    [time remaining]
 *               Programme title (cyan when live)
 *               Description (muted, italic)
 *               ━━━━━━━━━━━━━━━━━━━━━ (progress bar)        [⋯] [v]
 *
 * Layout intentionally dense so 7+ rows fit on a phone screen without scrolling.
 */
@Composable
private fun ChannelRow(
    channel: M3UChannel,
    nowProgramme: EPGProgramme?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel number column (iOS shows "1", "2", "3" muted on the left)
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

        // Logo (or letter avatar fallback)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.tvgLogo.isNotBlank()) {
                AsyncImage(
                    model = channel.tvgLogo,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
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

        // Title block + EPG metadata stack
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (nowProgramme != null) {
                Text(
                    text = nowProgramme.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nowProgramme.description.isNotBlank()) {
                    Text(
                        text = nowProgramme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(4.dp))
                EpgProgressBar(nowProgramme)
            } else if (channel.groupTitle.isNotBlank()) {
                Text(
                    text = channel.groupTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Right column: time remaining + more/expand controls
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
                // TODO Phase 5: implement long-press menu (Add to Favorites / Program Info /
                // Record from Now). The dots icon is the iOS canon affordance; hooking
                // its onClick comes with that phase.
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
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
