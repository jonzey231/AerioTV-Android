package com.aeriotv.android.feature.dvr

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DvrTabContent(
    modifier: Modifier = Modifier,
    viewModel: DvrViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("My Recordings", style = MaterialTheme.typography.titleMedium) },
            colors = TopAppBarDefaults.topAppBarColors(
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
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.visible, key = { it.id }) { rec ->
                RecordingRow(rec)
            }
        }
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

@Composable
private fun RecordingRow(rec: DvrViewModel.Recording) {
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
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
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = statusLabel.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.Bold,
        )
    }
}
