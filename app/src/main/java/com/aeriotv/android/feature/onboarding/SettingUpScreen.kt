package com.aeriotv.android.feature.onboarding

import com.aeriotv.android.ui.theme.textAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * tvOS ServerSyncView ("Setting Up") parity: a staged-loading card shown while
 * the playlist hydrates. Mirrors the four iOS stages (EPG / VOD / DVR /
 * preferences) with the same pending -> loading -> done iconography.
 *
 * The cold-launch load is monolithic (PlaylistViewModel goes Bootstrapping ->
 * ChannelsReady with no per-stage signals), so with no [saveStage] the stages
 * reveal sequentially and the host navigates away the moment the real load
 * reaches ChannelsReady. A Skip control lets the user jump to the guide
 * immediately.
 *
 * Edit Playlist reuses this same screen for a save (Logan 2026-09-18: a
 * successful save took several seconds behind a subtle "Saving..." label) with
 * [title] = "Saving Changes" and a non-null [saveStage]. In that mode the rows
 * are the REAL [PlaylistRepository.SaveStage] phases the repository reports at
 * its call boundaries - no timer, no Skip, because there is nothing to skip to
 * until the save settles.
 */
@Composable
fun SettingUpScreen(
    onSkip: (() -> Unit)? = null,
    title: String = "Setting Up",
    saveStage: com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage? = null,
    /**
     * The stages this save will actually run, in display order. Only the rows
     * for work that really happens are listed: no "Verifying credentials..."
     * when nothing re-authenticates, and no "Loading movies..." /
     * "Loading TV shows..." unless the On Demand catalog is genuinely being
     * rebuilt for a library this account may view.
     */
    savePlan: com.aeriotv.android.core.data.repository.PlaylistRepository.SavePlan? = null,
    /** A line under the stages: a held Child's launch with nothing cached (HeldListCopy), which stays here. */
    notice: String? = null,
) {
    val stageDriven = saveStage != null
    val planStages = savePlan?.stages ?: defaultSavePlanStages
    val stages = remember(stageDriven, planStages) {
        if (stageDriven) planStages.map { it.rowLabel }
        else listOf("Loading EPG", "Loading VOD", "Loading DVR", "Loading preferences")
    }
    // Index of the stage currently loading; earlier stages render as done.
    var revealed by remember { mutableIntStateOf(0) }
    LaunchedEffect(stageDriven) {
        if (stageDriven) return@LaunchedEffect
        while (revealed < stages.size) {
            delay(700L)
            revealed++
        }
    }
    // A save that skips the login (a rename, a Guide Days change) starts at
    // Loading channels, so the stages before the reported one read as done.
    // Indexed by POSITION IN THE PLAN, never by enum ordinal: the plan omits
    // the stages this save does not run, and an ordinal would point at the
    // wrong row (or off the end) the moment one is missing.
    val active = if (stageDriven) {
        planStages.indexOf(saveStage).coerceAtLeast(0)
    } else {
        revealed
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(vertical = 8.dp),
            ) {
                stages.forEachIndexed { i, label ->
                    val status = when {
                        i < active -> StageStatus.Done
                        i == active -> StageStatus.Loading
                        else -> StageStatus.Pending
                    }
                    StageRow(
                        label = label,
                        status = status,
                        showSynced = i == stages.lastIndex && status == StageStatus.Done,
                    )
                }
            }
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (onSkip != null) {
                Spacer(Modifier.height(28.dp))
                TextButton(onClick = onSkip) {
                    Text("Skip", color = MaterialTheme.colorScheme.textAccent)
                }
            }
        }
    }
}

/**
 * Row wording for each save phase. Kept next to the screen that renders them so
 * the wording lives in one place; sentence case with no trailing
 * ellipsis, matching the Apple app's rows exactly (Logan 2026-09-18).
 */
private val com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage.rowLabel: String
    get() = when (this) {
        com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage
            .VerifyingCredentials -> "Verifying credentials"
        com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage
            .LoadingChannels -> "Loading channels"
        com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage
            .LoadingMovies -> "Loading movies"
        com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage
            .LoadingSeries -> "Loading TV shows"
        com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage
            .Finishing -> "Finishing up"
    }

/** What a save runs when the caller passed no plan: the unconditional steps. */
private val defaultSavePlanStages = listOf(
    com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage.LoadingChannels,
    com.aeriotv.android.core.data.repository.PlaylistRepository.SaveStage.Finishing,
)

private enum class StageStatus { Pending, Loading, Done }

@Composable
private fun StageRow(label: String, status: StageStatus, showSynced: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StageStatus.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                StageStatus.Done -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(20.dp),
                )
                StageStatus.Pending -> Icon(
                    imageVector = Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (status == StageStatus.Pending) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
            if (showSynced) {
                Text(
                    text = "Synced",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.textAccent,
                )
            }
        }
    }
}
