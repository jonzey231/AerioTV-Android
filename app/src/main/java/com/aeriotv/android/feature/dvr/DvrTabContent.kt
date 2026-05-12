package com.aeriotv.android.feature.dvr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
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
    viewModel: DvrViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
            EmptyState(
                title = "No recordings",
                body = "Schedule a recording from the TV guide to get started.",
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 104dp bottom clears the MainScaffold NavigationBar so the
            // last recording row stays tappable (otherwise the bottom card
            // sits underneath the Live TV / DVR / On Demand / Settings nav
            // when the user has more than 4-5 recordings).
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.visible, key = { it.id }) { rec ->
                RecordingRow(
                    rec = rec,
                    onEdit = { pendingEdit = rec },
                    onDelete = { pendingDelete = rec },
                    onPlay = {
                        // Server completed playback + local playback both need
                        // a player route that takes a raw URL. Wiring lands in
                        // a follow-up phase (iOS MyRecordingsView playRecording
                        // / playServerRecording paths).
                        Toast.makeText(
                            context,
                            "Recording playback lands in the next phase.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onSaveToDevice = {
                        // iOS "Save to Device" walks the recording over to the
                        // local DVR slot via DownloadManager + a confirmation
                        // sheet. Needs MANAGE_EXTERNAL_STORAGE or scoped storage
                        // wiring; deferred to a dedicated phase.
                        Toast.makeText(
                            context,
                            "Save to Device lands in the next phase.",
                            Toast.LENGTH_SHORT,
                        ).show()
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
            if (state.visible.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing here yet",
                        body = "Schedule a recording from the TV guide or hit Record from Now in the player.",
                    )
                }
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
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onSaveToDevice: () -> Unit,
    onRemoveCommercials: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val statusColor = when (rec.status) {
        DvrViewModel.Recording.Status.Recording -> Color(0xFFFF4757)
        DvrViewModel.Recording.Status.Completed -> MaterialTheme.colorScheme.primary
        DvrViewModel.Recording.Status.Stopped -> Color(0xFFFFA502)
        DvrViewModel.Recording.Status.Failed -> Color(0xFFFF4757)
        DvrViewModel.Recording.Status.Scheduled -> MaterialTheme.colorScheme.onSurfaceVariant
        DvrViewModel.Recording.Status.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (rec.status) {
        DvrViewModel.Recording.Status.Recording -> "Recording"
        DvrViewModel.Recording.Status.Completed -> "Completed"
        DvrViewModel.Recording.Status.Stopped -> "Stopped"
        DvrViewModel.Recording.Status.Failed -> "Failed"
        DvrViewModel.Recording.Status.Scheduled -> "Scheduled"
        DvrViewModel.Recording.Status.Unknown -> "Unknown"
    }
    val dateFmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    val dateLabel = "${dateFmt.format(Date(rec.startMillis))} at " +
            "${timeFmt.format(Date(rec.startMillis))} – ${timeFmt.format(Date(rec.endMillis))}"

    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuOpen = true },
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
                    style = MaterialTheme.typography.bodySmall,
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
                DestinationBadge(source = rec.source)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = statusLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold,
            )
        }
        RecordingActionMenu(
            rec = rec,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onEdit = onEdit,
            onDelete = onDelete,
            onPlay = onPlay,
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
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
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onSaveToDevice: () -> Unit,
    onRemoveCommercials: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val isServer = rec.source == DvrViewModel.Source.Server
    val isCompleted = rec.status == DvrViewModel.Recording.Status.Completed ||
        rec.status == DvrViewModel.Recording.Status.Stopped
    val isInProgress = rec.status == DvrViewModel.Recording.Status.Recording
    val isScheduled = rec.status == DvrViewModel.Recording.Status.Scheduled

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (isCompleted) {
            DropdownMenuItem(
                text = { Text("Play") },
                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onDismiss(); onPlay() },
            )
            if (isServer) {
                DropdownMenuItem(
                    text = { Text("Save to Device") },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onDismiss(); onSaveToDevice() },
                )
                DropdownMenuItem(
                    text = { Text("Remove Commercials") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onDismiss(); onRemoveCommercials() },
                )
            }
        }

        if (isInProgress && isServer) {
            DropdownMenuItem(
                text = { Text("Stop Recording") },
                leadingIcon = { Icon(Icons.Outlined.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onDismiss(); onStopRecording() },
            )
        }

        if (isScheduled && isServer) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onDismiss(); onEdit() },
            )
        }

        val deleteLabel = when {
            isServer && isScheduled -> "Cancel"
            isServer -> "Delete from Server"
            else -> "Delete"
        }
        DropdownMenuItem(
            text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}
