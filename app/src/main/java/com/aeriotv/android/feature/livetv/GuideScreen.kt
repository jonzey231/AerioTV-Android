package com.aeriotv.android.feature.livetv

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.ProgramInfoTarget
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

/**
 * EPG Guide grid (channels on Y, time on X). Mirrors iOS EPGGuideView.
 *
 * Layout:
 *   +---------+--------------------- horizontalScroll -------------------->
 *   | (corner)|  10:00 | 11:00 | 12:00 | 13:00 | 14:00 | ...
 *   +---------+----------------------------------------------------------
 *   | [logo]  |  [programme]  [programme]      [programme]
 *   | ESPN HD |
 *   +---------+----------------------------------------------------------
 *   | [logo]  |       [programme]    [programme]    [programme]
 *   | TBS HD  |
 *   +---------+----------------------------------------------------------
 *
 * A single horizontalScroll state is shared across the time-header row and every
 * channel row so they pan together. Programmes are absolutely positioned in
 * their row via Modifier.offset, with x derived from
 *   (programme.start - windowStart) / msPerDp.
 *
 * "Now" indicator: a 2dp vertical cyan line drawn over the programme strip at
 * the current-time x position, recomputed every minute.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    onChannelClick: (M3UChannel) -> Unit,
    viewMode: LiveTVViewMode,
    canToggleViewMode: Boolean,
    onToggleViewMode: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var programInfoTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }
    var recordTarget by remember { mutableStateOf<ProgramInfoTarget?>(null) }

    // Tick "now" forward every 30s so the indicator + currently-airing tinting stay
    // accurate without forcing the whole tree to recompose on every frame.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    // Guide window: -1h history to +23h ahead = 24h scrollable strip. Matches
    // iOS-default `epgWindowHours=36` only loosely (24h keeps the strip a
    // manageable scroll on a 9-inch tablet); Phase 8 user preference will let
    // the user choose 6/12/24/36/48/72 hours like iOS.
    val windowStart = remember(nowMillis) {
        // Floor `now` to the start of the current hour to keep header labels clean.
        val hourMs = 3_600_000L
        (nowMillis / hourMs) * hourMs - hourMs
    }
    val windowDurationMs = 24L * 3_600_000L

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

    val filteredChannels by remember(state.channels, state.selectedGroup) {
        derivedStateOf {
            val query = state.searchQuery.trim()
            state.channels.asSequence()
                .filter {
                    state.selectedGroup == PlaylistViewModel.ALL_GROUPS ||
                            it.groupTitle.equals(state.selectedGroup, ignoreCase = true)
                }
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .sortedWith(compareBy({ it.channelNumber ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                .toList()
        }
    }

    val timeFormatter = remember { SimpleDateFormat("h a", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() } }

    // Horizontal scroll state shared across the time-header row + every channel
    // row so they pan together.
    val horizontalScrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                val playlistName = state.playlist?.name?.takeIf { it.isNotBlank() } ?: "Live TV"
                Text(
                    text = "$playlistName  •  ${filteredChannels.size} / ${state.channels.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            actions = {
                if (canToggleViewMode) {
                    IconButton(onClick = onToggleViewMode) {
                        Icon(
                            imageVector = if (viewMode == LiveTVViewMode.Guide)
                                Icons.Filled.ViewList else Icons.Filled.CalendarMonth,
                            contentDescription = if (viewMode == LiveTVViewMode.Guide) "Switch to List" else "Switch to Guide",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        // Group filter chips - same UX as the List view so toggling between
        // modes keeps the user's filter context.
        if (groups.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

        // Time-header row: empty corner over the channel rail + horizontally-scrolled hour labels.
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(GuideMetrics.RAIL_WIDTH)
                    .height(GuideMetrics.HEADER_HEIGHT)
                    .background(MaterialTheme.colorScheme.background),
            )
            Box(
                modifier = Modifier
                    .horizontalScroll(horizontalScrollState)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val hourCount = (windowDurationMs / 3_600_000L).toInt()
                Row(
                    modifier = Modifier
                        .width(GuideMetrics.HOUR_WIDTH * hourCount)
                        .height(GuideMetrics.HEADER_HEIGHT),
                ) {
                    for (i in 0 until hourCount) {
                        val slotStart = windowStart + i * 3_600_000L
                        Box(
                            modifier = Modifier
                                .width(GuideMetrics.HOUR_WIDTH)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = timeFormatter.format(java.util.Date(slotStart)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = filteredChannels, key = { it.id }) { channel ->
                val programmes = state.epgByChannel[channel.tvgID].orEmpty()
                ChannelGuideRow(
                    channel = channel,
                    programmes = programmes,
                    windowStart = windowStart,
                    windowDurationMs = windowDurationMs,
                    nowMillis = nowMillis,
                    horizontalScrollState = horizontalScrollState,
                    onChannelClick = { onChannelClick(channel) },
                    onProgrammeClick = { programme ->
                        programInfoTarget = programme.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                    },
                    onProgrammeRecord = { programme ->
                        recordTarget = programme.toInfoTarget(channel.name, channel.dispatcharrChannelId)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelGuideRow(
    channel: M3UChannel,
    programmes: List<EPGProgramme>,
    windowStart: Long,
    windowDurationMs: Long,
    nowMillis: Long,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    onChannelClick: () -> Unit,
    onProgrammeClick: (EPGProgramme) -> Unit,
    onProgrammeRecord: (EPGProgramme) -> Unit,
) {
    val context = LocalContext.current
    var railMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GuideMetrics.ROW_HEIGHT),
    ) {
        // Sticky-left channel rail. Tap plays the channel; long-press opens the
        // favorites toggle. Mirrors iOS EPGGuideView channel-rail .contextMenu
        // (EPGGuideView.swift:2823).
        Row(
            modifier = Modifier
                .width(GuideMetrics.RAIL_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                .combinedClickable(
                    onClick = onChannelClick,
                    onLongClick = { railMenuOpen = true },
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            channel.channelNumber?.let { num ->
                Text(
                    text = num.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.tvgLogo.isNotBlank()) {
                    AsyncImage(
                        model = channel.tvgLogo,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            DropdownMenu(
                expanded = railMenuOpen,
                onDismissRequest = { railMenuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DropdownMenuItem(
                    text = { Text("Add to Favorites") },
                    onClick = {
                        railMenuOpen = false
                        Toast.makeText(
                            context,
                            "Favorites store lands with the conditional-tab work.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }

        // Programme strip - horizontally scrolled with the header.
        Box(
            modifier = Modifier
                .horizontalScroll(horizontalScrollState)
                .fillMaxHeight(),
        ) {
            val totalWidth = GuideMetrics.HOUR_WIDTH * (windowDurationMs / 3_600_000L).toInt()
            Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
                // Programme cells, positioned by offset.
                programmes.forEach { programme ->
                    // Clip to the window so off-screen programmes don't bloat the layout.
                    val rawStart = programme.startMillis
                    val rawEnd = programme.endMillis
                    if (rawEnd <= windowStart) return@forEach
                    val windowEnd = windowStart + windowDurationMs
                    if (rawStart >= windowEnd) return@forEach
                    val clippedStart = rawStart.coerceAtLeast(windowStart)
                    val clippedEnd = rawEnd.coerceAtMost(windowEnd)
                    val xDp = ((clippedStart - windowStart) / GuideMetrics.MS_PER_DP_LONG).toFloat().dp
                    val wDp = ((clippedEnd - clippedStart) / GuideMetrics.MS_PER_DP_LONG).toFloat().dp
                    val isLive = programme.startMillis <= nowMillis && programme.endMillis > nowMillis
                    ProgrammeCell(
                        programme = programme,
                        channelName = channel.name,
                        widthDp = wDp,
                        isLive = isLive,
                        modifier = Modifier.offset(x = xDp),
                        onClick = { onProgrammeClick(programme) },
                        onRecord = { onProgrammeRecord(programme) },
                    )
                }
                // "Now" indicator - 2dp cyan line, only drawn when "now" falls inside the window.
                if (nowMillis in (windowStart + 1)..(windowStart + windowDurationMs - 1)) {
                    val nowX = ((nowMillis - windowStart) / GuideMetrics.MS_PER_DP_LONG).toFloat().dp
                    Box(
                        modifier = Modifier
                            .offset(x = nowX)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgrammeCell(
    programme: EPGProgramme,
    channelName: String,
    widthDp: androidx.compose.ui.unit.Dp,
    isLive: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onRecord: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (isLive)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
    val borderColor = if (isLive)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    else
        MaterialTheme.colorScheme.surfaceVariant
    val titleColor = if (isLive)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onBackground
    Column(
        modifier = modifier
            .width(widthDp)
            .fillMaxHeight()
            .padding(start = 1.dp, end = 1.dp, top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.5.dp, borderColor, RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DropdownMenuItem(
                text = { Text("Program Info") },
                onClick = {
                    menuOpen = false
                    onClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Set Reminder") },
                onClick = {
                    menuOpen = false
                    Toast.makeText(
                        context,
                        "Reminders land in a future phase.",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            if (programme.endMillis > System.currentTimeMillis()) {
                val recordLabel = if (isLive) "Record from Now" else "Record"
                DropdownMenuItem(
                    text = { Text(recordLabel) },
                    onClick = {
                        menuOpen = false
                        onRecord()
                    },
                )
            }
        }
        Text(
            text = programme.title.ifBlank { "—" },
            style = MaterialTheme.typography.labelMedium,
            color = titleColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (programme.description.isNotBlank()) {
            Text(
                text = programme.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Layout constants for the guide grid. Centralized so Phase 8 zoom-via-AppStorage can scale them. */
private object GuideMetrics {
    val RAIL_WIDTH = 168.dp
    val HEADER_HEIGHT = 36.dp
    val ROW_HEIGHT = 80.dp
    val HOUR_WIDTH = 320.dp
    /** ms / dp for 1 hour = 320dp. Used to compute programme cell x + width. */
    const val MS_PER_DP_LONG: Long = 3_600_000L / 320L
}
