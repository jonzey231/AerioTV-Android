package com.aeriotv.android.feature.dvr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import com.aeriotv.android.core.category.CategoryPaletteState
import com.aeriotv.android.core.category.CategoryPillsFlow
import com.aeriotv.android.core.category.categoryTokens
import com.aeriotv.android.core.tv.TvActionMenuDialog
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.feature.settings.rememberIsTvDevice
import com.aeriotv.android.feature.settings.settingsRowCard
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

/**
 * DVR tab. Mirrors iOS MyRecordingsView (project_aeriotv_ios_canon.md "DVR tab"):
 * "My Recordings" title, three filter chips with counts (Scheduled / Recording /
 * Completed), empty-state film-strip icon + helper copy, and a list of row cards.
 *
 * Phase 9a is server-only — recordings come from Dispatcharr's
 * `/api/channels/recordings/`. Local recordings via foreground service land in
 * Phase 9b.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DvrTabContent(
    modifier: Modifier = Modifier,
    onPlayRecording: (String, String) -> Unit = { _, _ -> },
    onWatchLive: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onWatchFromBeginning: (String, String, Boolean) -> Unit = { _, _, _ -> },
    viewModel: DvrViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette by settingsVm.categoryPalette.collectAsStateWithLifecycle(initialValue = CategoryPaletteState.Default)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Auto-refresh server-side recordings every 30s while the tab is visible
    // so a Scheduled -> Recording -> Completed transition lands without the
    // user manually swiping the tab.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            viewModel.refresh()
        }
    }

    var pendingDelete by remember { mutableStateOf<DvrViewModel.Recording?>(null) }
    var pendingEdit by remember { mutableStateOf<DvrViewModel.Recording?>(null) }
    var pendingClearAll by remember { mutableStateOf(false) }
    val isTv = rememberIsTvDevice()
    // 10-foot rule: keep content out of the ~5% overscan band. 48dp on the
    // 960dp TV canvas = 5%; phones keep the tighter phone insets.
    val edgeInset = if (isTv) 48.dp else 16.dp

    // Land the user on their content, not an empty filter: if Scheduled is
    // empty but another filter has recordings, auto-select the first
    // non-empty one. Applies once per entry, never fights a manual pick.
    var autoFilterApplied by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (autoFilterApplied || state.isLoading) return@LaunchedEffect
        autoFilterApplied = true
        if (state.filter == DvrViewModel.Filter.Scheduled && state.scheduledCount == 0) {
            when {
                state.recordingCount > 0 -> viewModel.setFilter(DvrViewModel.Filter.Recording)
                state.completedCount > 0 -> viewModel.setFilter(DvrViewModel.Filter.Completed)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "My Recordings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (state.unsupportedSource) {
            EmptyState(
                title = "DVR needs Dispatcharr",
                body = "Switch to a Dispatcharr playlist in Settings to schedule recordings.",
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = edgeInset, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterPill(
                        label = "Scheduled (${state.scheduledCount})",
                        selected = state.filter == DvrViewModel.Filter.Scheduled,
                        onClick = { viewModel.setFilter(DvrViewModel.Filter.Scheduled) },
                    )
                }
                item {
                    FilterPill(
                        label = "Recording (${state.recordingCount})",
                        selected = state.filter == DvrViewModel.Filter.Recording,
                        onClick = { viewModel.setFilter(DvrViewModel.Filter.Recording) },
                    )
                }
                item {
                    FilterPill(
                        label = "Completed (${state.completedCount})",
                        selected = state.filter == DvrViewModel.Filter.Completed,
                        onClick = { viewModel.setFilter(DvrViewModel.Filter.Completed) },
                    )
                }
            }
            // Audit task #50 (delete-all): only visible when the Completed
            // filter is selected and there is at least one recording to
            // delete. Confirms via AlertDialog before firing the bulk delete.
            if (state.filter == DvrViewModel.Filter.Completed && state.completedCount > 0) {
                if (isTv) {
                    // Same pill grammar as the filter row so D-pad focus is
                    // visible; a bare TextButton has no focus chrome on TV.
                    var clearFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .height(36.dp)
                            .onFocusChanged { clearFocused = it.isFocused }
                            .clip(CircleShape)
                            .background(
                                if (clearFocused) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                },
                            )
                            .then(
                                if (clearFocused) {
                                    Modifier.border(2.dp, Color.White, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { pendingClearAll = true },
                            )
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Clear All",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    TextButton(
                        onClick = { pendingClearAll = true },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            text = "Clear All",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Column
        }
        state.error?.let { err ->
            Text(
                text = "Couldn't load recordings: $err",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        if (state.visible.isEmpty()) {
            // Filter-specific copy so the headline never contradicts a visible
            // non-zero count on a sibling chip.
            val (emptyTitle, emptyBody) = when (state.filter) {
                DvrViewModel.Filter.Scheduled ->
                    "No scheduled recordings" to
                        "Schedule a recording from the TV guide to get started."
                DvrViewModel.Filter.Recording ->
                    "Nothing recording right now" to
                        "Recordings in progress will show up here."
                DvrViewModel.Filter.Completed ->
                    "No completed recordings" to
                        "Finished recordings will show up here, ready to play."
            }
            EmptyState(title = emptyTitle, body = emptyBody)
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Phone: 104dp bottom clears the MainScaffold NavigationBar so the
            // last recording row stays tappable. TV has a TOP tab bar, so the
            // big bottom inset would just be a dead gap; 32dp keeps the last
            // card above the overscan band instead.
            contentPadding = PaddingValues(
                start = edgeInset,
                end = edgeInset,
                top = 8.dp,
                bottom = if (isTv) 32.dp else 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.visible, key = { it.id }) { rec ->
                RecordingRow(
                    rec = rec,
                    palette = palette,
                    onEdit = { pendingEdit = rec },
                    onDelete = { pendingDelete = rec },
                    onWatchLive = {
                        // Audit #50 / iOS v1.6.22: in-progress server rows that
                        // carry a server-reported URL offer Watch Live. Routes
                        // to the recording player at the live edge.
                        rec.inProgressUrl?.let { onWatchLive(it, rec.title, rec.isDvr) }
                    },
                    onWatchFromBeginning = {
                        rec.inProgressUrl?.let { onWatchFromBeginning(it, rec.title, rec.isDvr) }
                    },
                    onPlay = {
                        // Audit task #43: both local (file://) and Dispatcharr
                        // server recordings carry a playbackUrl resolved at
                        // toRecording() time (the server URL is the reported
                        // file_url or the constructed /file/ endpoint). Route to
                        // the VOD player, which applies the source's auth
                        // headers for remote URLs. Only finalized recordings get
                        // a URL, so a null means the file isn't ready yet.
                        val url = rec.playbackUrl
                        if (!url.isNullOrBlank()) {
                            onPlayRecording(url, rec.title)
                        } else {
                            Toast.makeText(
                                context,
                                "This recording has no playable file yet.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onSaveToDevice = {
                        // Audit task #43: download the finalized server
                        // recording to local storage via the foreground
                        // LocalRecordingService; it reappears as a Local copy
                        // playable offline. Mirrors iOS downloadRecording.
                        scope.launch {
                            viewModel.saveToDevice(rec).fold(
                                onSuccess = {
                                    Toast.makeText(
                                        context,
                                        "Saving \"${rec.title}\" to device…",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onFailure = { t ->
                                    Toast.makeText(
                                        context,
                                        "Save to Device failed: ${t.message ?: t::class.simpleName}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    },
                    onRemoveCommercials = {
                        scope.launch {
                            viewModel.applyComskip(rec).fold(
                                onSuccess = {
                                    Toast.makeText(
                                        context,
                                        "Remove Commercials started.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onFailure = { t ->
                                    Toast.makeText(
                                        context,
                                        "Remove Commercials failed: ${t.message ?: t::class.simpleName}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    },
                    onStopRecording = {
                        scope.launch {
                            viewModel.stopRecording(rec).fold(
                                onSuccess = {
                                    Toast.makeText(
                                        context,
                                        "Recording stopped.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onFailure = { t ->
                                    Toast.makeText(
                                        context,
                                        "Stop Recording failed: ${t.message ?: t::class.simpleName}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    pendingEdit?.let { rec ->
        EditRecordingSheet(
            recording = rec,
            onDismiss = { pendingEdit = null },
            onSave = { newStart, newEnd, newTitle, newDescription ->
                val id = rec.id.removePrefix("server-").toIntOrNull()
                pendingEdit = null
                if (id == null) {
                    Toast.makeText(context, "Invalid recording id.", Toast.LENGTH_SHORT).show()
                    return@EditRecordingSheet
                }
                scope.launch {
                    viewModel.editServerRecording(
                        recordingId = id,
                        startMillis = newStart,
                        endMillis = newEnd,
                        title = newTitle,
                        description = newDescription,
                    ).fold(
                        onSuccess = {
                            Toast.makeText(context, "Recording updated.", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { t ->
                            Toast.makeText(
                                context,
                                "Update failed: ${t.message ?: t::class.simpleName}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            },
        )
    }

    pendingDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete recording?") },
            text = {
                Text(
                    "This removes \"${rec.title}\" " +
                            if (rec.source == DvrViewModel.Source.Local)
                                "from this device permanently."
                            else
                                "from your Dispatcharr server. The file is gone after this."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        viewModel.deleteRecording(rec).fold(
                            onSuccess = {
                                Toast.makeText(context, "Recording deleted.", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { t ->
                                Toast.makeText(
                                    context,
                                    "Delete failed: ${t.message ?: t::class.simpleName}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    // Audit task #50 delete-all confirmation. Uses the running snapshot of
    // state.completedCount so the count in the prompt reflects what would
    // actually be deleted at the moment the user opens the dialog.
    if (pendingClearAll) {
        AlertDialog(
            onDismissRequest = { pendingClearAll = false },
            title = { Text("Clear all completed?") },
            text = {
                Text(
                    "This removes all ${state.completedCount} completed " +
                        "recordings. Server-side recordings are deleted on " +
                        "your Dispatcharr server; local recordings have " +
                        "their files removed from this device. This can't " +
                        "be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClearAll = false
                    scope.launch {
                        viewModel.deleteAllCompleted().fold(
                            onSuccess = { n ->
                                Toast.makeText(
                                    context,
                                    "Deleted $n recordings.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onFailure = { t ->
                                Toast.makeText(
                                    context,
                                    "Clear All partially failed: ${t.message ?: t::class.simpleName}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAll = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (rememberIsTvDevice()) {
        // TV: the guide-pill treatment instead of a stock FilterChip. The
        // stock chip's focus state is a faint Material overlay that is
        // indistinguishable from the selected state at couch distance; this
        // gives D-pad focus the same 2dp white ring program cells and guide
        // pills use, so focused vs selected is unambiguous.
        val interaction = remember { MutableInteractionSource() }
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .height(36.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                )
                .then(
                    if (focused) Modifier.background(Color.White.copy(alpha = 0.12f))
                    else Modifier,
                )
                .then(
                    if (focused) {
                        Modifier.border(2.dp, Color.White, CircleShape)
                    } else Modifier,
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    } else {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    rec: DvrViewModel.Recording,
    palette: CategoryPaletteState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onWatchLive: () -> Unit,
    onWatchFromBeginning: () -> Unit,
    onSaveToDevice: () -> Unit,
    onRemoveCommercials: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val statusColor = when (rec.status) {
        DvrViewModel.Recording.Status.Recording -> Color(0xFFFF4757)
        DvrViewModel.Recording.Status.Completed -> MaterialTheme.colorScheme.primary
        // A "stopped" Dispatcharr recording still wrote a finished, playable file
        // (Dispatcharr's own web UI shows it as COMPLETED), so present it like a
        // completed recording instead of an alarming orange warning. A genuine
        // failure is the separate Failed status below.
        DvrViewModel.Recording.Status.Stopped -> MaterialTheme.colorScheme.primary
        DvrViewModel.Recording.Status.Failed -> Color(0xFFFF4757)
        DvrViewModel.Recording.Status.Scheduled -> MaterialTheme.colorScheme.onSurfaceVariant
        DvrViewModel.Recording.Status.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (rec.status) {
        DvrViewModel.Recording.Status.Recording -> "Recording"
        DvrViewModel.Recording.Status.Completed -> "Completed"
        // Stopped-but-saved reads as Completed, matching Dispatcharr (see color).
        DvrViewModel.Recording.Status.Stopped -> "Completed"
        DvrViewModel.Recording.Status.Failed -> "Failed"
        DvrViewModel.Recording.Status.Scheduled -> "Scheduled"
        DvrViewModel.Recording.Status.Unknown -> "Unknown"
    }
    val dateFmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    val dateLabel = "${dateFmt.format(Date(rec.startMillis))} at " +
            "${timeFmt.format(Date(rec.startMillis))} – ${timeFmt.format(Date(rec.endMillis))}"

    var menuOpen by remember { mutableStateOf(false) }
    val isTv = rememberIsTvDevice()
    val tvGuard = com.aeriotv.android.core.tv.rememberTvMenuGuard()
    var focused by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                // Shared TV row chrome: visible focused card (accent fill +
                // 2dp border + 1.02 scale); at rest it paints the same
                // surface-0.55 card this row used before.
                .settingsRowCard(focused)
                .combinedClickable(
                    // Single tap plays a finalized recording (Completed /
                    // Stopped carry a playbackUrl); non-playable rows no-op on
                    // tap and use the long-press menu. Mirrors iOS tvOS
                    // playIfCompleted (MyRecordingsView.swift line 469).
                    // tvGuard: the OK RELEASE after a TV long-press is
                    // delivered as a click (to this row or to the menu's
                    // first item); arm/wrap swallows it.
                    // Single tap plays a finalized recording; an in-progress
                    // server row with a server URL tap-plays FROM THE BEGINNING
                    // (Logan 2026-07-20: a recording-in-progress is something
                    // you started to watch from the start; the live edge is the
                    // deliberate "Start at Live" menu choice). Other rows no-op
                    // and use the long-press menu.
                    onClick = tvGuard.wrap {
                        when {
                            !rec.playbackUrl.isNullOrBlank() -> onPlay()
                            rec.status == DvrViewModel.Recording.Status.Recording &&
                                rec.inProgressUrl != null -> onWatchFromBeginning()
                        }
                    },
                    onLongClick = { menuOpen = true; tvGuard.arm() },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.LiveTv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rec.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = dateLabel,
                    // bodySmall lands at ~10.8sp effective under the 0.9 TV
                    // type scale, below couch readability; bodyMedium on TV.
                    style = if (isTv) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rec.description.isNotBlank()) {
                    Text(
                        text = rec.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Tokenize the resolved category on XMLTV separators. When the
                // value has no separators (a single un-splittable genre like
                // "Sports") the tokenizer already returns it as one token, so
                // the ifEmpty fallback only fires when the raw string itself is
                // blank -- which it no longer is for completed recordings now
                // that DvrViewModel hydrates them from the programme list
                // (Streamer feedback #6). The fallback is kept as a belt-and-
                // suspenders: any non-blank rec.category renders at least one
                // pill instead of being silently dropped.
                val genreTokens = rec.category.categoryTokens()
                    .ifEmpty { listOfNotNull(rec.category.trim().takeIf { it.isNotEmpty() }) }
                if (genreTokens.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    CategoryPillsFlow(
                        tokens = genreTokens,
                        palette = palette,
                    )
                }
                DestinationBadge(source = rec.source)
            }
            Spacer(Modifier.size(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = statusLabel.uppercase(),
                    style = if (isTv) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                )
                // The long-press affordance is invisible on a remote; surface
                // it on the focused row only, where the eye already is.
                if (isTv && focused) {
                    Text(
                        text = "Hold OK for options",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
        }
        RecordingActionMenu(
            rec = rec,
            expanded = menuOpen,
            tvGuard = tvGuard,
            onDismiss = { menuOpen = false },
            onEdit = onEdit,
            onDelete = onDelete,
            onWatchLive = onWatchLive,
            onWatchFromBeginning = onWatchFromBeginning,
            onSaveToDevice = onSaveToDevice,
            onRemoveCommercials = onRemoveCommercials,
            onStopRecording = onStopRecording,
        )
    }
}

/**
 * Storage-destination indicator beneath each recording row. Mirrors iOS
 * MyRecordingsView line 708-717:
 *   Server → server.rack glyph + accentPrimary cyan
 *   Local  → internaldrive glyph + system green
 * The cyan/green color split is intentional — it lets the user scan a long
 * mixed list and see at a glance which rows would survive if their server
 * went offline. The Material `Storage` icon is the closest SF-Symbols
 * `server.rack` equivalent (stack of horizontal rectangles); `Smartphone`
 * stands in for `internaldrive` since the local recording physically lives
 * on this device.
 */
@Composable
private fun DestinationBadge(source: DvrViewModel.Source) {
    val isLocal = source == DvrViewModel.Source.Local
    val icon = if (isLocal) Icons.Outlined.Smartphone else Icons.Outlined.Storage
    val label = if (isLocal) "Local" else "Server"
    // iOS uses `Color.green` for local rows — system green (#34C759). Server
    // rows pull the active theme accent so the badge stays brand-coherent
    // when a custom accent is set in Appearance.
    val tint = if (isLocal) Color(0xFF34C759) else MaterialTheme.colorScheme.primary
    val isTv = rememberIsTvDevice()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (isTv) 15.dp else 12.dp),
        )
        Text(
            text = label,
            style = if (isTv) MaterialTheme.typography.labelMedium
            else MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Per-status context menu, anchored to the long-pressed row. Mirrors iOS
 * MyRecordingsView.contextMenuItems(for:) (lines 280-365):
 *
 *  - Completed / Stopped server recording: Play / Save to Device /
 *    Remove Commercials / Delete from Server
 *  - Completed local recording: Play / Delete
 *  - In-progress server recording: Stop Recording / Delete
 *  - Scheduled server recording: Edit / Cancel
 *  - Unknown status: bare Delete so the user can clean up rows the
 *    server hasn't classified yet
 */
@Composable
private fun RecordingActionMenu(
    rec: DvrViewModel.Recording,
    expanded: Boolean,
    tvGuard: com.aeriotv.android.core.tv.TvMenuGuard,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWatchLive: () -> Unit,
    onWatchFromBeginning: () -> Unit,
    onSaveToDevice: () -> Unit,
    onRemoveCommercials: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val isServer = rec.source == DvrViewModel.Source.Server
    val isCompleted = rec.status == DvrViewModel.Recording.Status.Completed ||
        rec.status == DvrViewModel.Recording.Status.Stopped
    val isInProgress = rec.status == DvrViewModel.Recording.Status.Recording
    val isScheduled = rec.status == DvrViewModel.Recording.Status.Scheduled

    // One source of truth for the per-status actions; rendered as an anchored
    // DropdownMenu on phone (a thumb-distance idiom) and as a centered Dialog
    // on TV (anchored popups float oddly mid-screen at 10 feet, and the app's
    // modal idiom on TV is the centered panel).
    val actions = buildList {
        if (isCompleted && isServer) {
            add(TvMenuAction("Save to Device", Icons.Outlined.Download) { onSaveToDevice() })
            add(TvMenuAction("Remove Commercials", Icons.Outlined.ContentCut) { onRemoveCommercials() })
        }
        if (isInProgress && isServer) {
            // iOS MyRecordingsView 345-370: an in-progress server row with a
            // server-reported URL offers Watch Live (live edge) + Watch from
            // Beginning (window start). Gate on inProgressUrl, not the channel
            // id -- the recording carries its own playback URL.
            if (rec.inProgressUrl != null) {
                // Tap already starts from the beginning (see the row's
                // onClick), so the menu leads with the OTHER option, Start
                // at Live, and keeps Watch from Beginning for discoverability.
                add(TvMenuAction("Start at Live", Icons.Outlined.PlayArrow) { onWatchLive() })
                add(TvMenuAction("Watch from Beginning", Icons.Outlined.SkipPrevious) { onWatchFromBeginning() })
            }
            add(TvMenuAction("Stop Recording", Icons.Outlined.Stop) { onStopRecording() })
        }
        if (isScheduled && isServer) {
            add(TvMenuAction("Edit", Icons.Outlined.Edit) { onEdit() })
        }
        val deleteLabel = when {
            isServer && isScheduled -> "Cancel"
            isServer -> "Delete from Server"
            else -> "Delete"
        }
        add(TvMenuAction(deleteLabel, Icons.Outlined.Delete, destructive = true) { onDelete() })
    }

    if (rememberIsTvDevice()) {
        if (expanded) {
            TvActionMenuDialog(
                title = rec.title,
                actions = actions,
                guard = tvGuard,
                onDismiss = onDismiss,
            )
        }
        return
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        actions.forEach { action ->
            val tint = if (action.destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
            DropdownMenuItem(
                text = {
                    Text(
                        action.label,
                        color = if (action.destructive) MaterialTheme.colorScheme.error
                        else androidx.compose.ui.graphics.Color.Unspecified,
                    )
                },
                leadingIcon = action.icon?.let { icon ->
                    { Icon(icon, contentDescription = null, tint = tint) }
                },
                onClick = { onDismiss(); action.onClick() },
            )
        }
    }
}
