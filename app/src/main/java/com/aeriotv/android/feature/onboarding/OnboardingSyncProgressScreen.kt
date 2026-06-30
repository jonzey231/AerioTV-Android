package com.aeriotv.android.feature.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.sync.DriveSyncManager
import com.aeriotv.android.core.sync.SyncCategory

/**
 * Onboarding restore-progress screen, shown after a successful Google sign-in
 * on the Welcome screen while Drive snapshots pull and the restored playlist
 * hydrates. iOS sibling: ServerSyncView's staged "Setting Up" card
 * (Features/Onboarding/ServerSyncView.swift) -- same one-row-per-category
 * progression, with the Android twist that the categories are the Drive sync
 * snapshots (DriveSyncManager pull order) plus a final "Channels & Guide"
 * line driven by PlaylistViewModel reaching Phase.ChannelsReady.
 *
 * Form-factor neutral: the 520dp-capped centered column reads correctly on a
 * phone and on the 960x540dp TV canvas (TV-aware Typography scales the text).
 * There is nothing focusable on purpose -- it is pure status -- and BACK is
 * swallowed so the restore can't be interrupted; the host (Navigation.kt
 * WELCOME route) removes the screen itself when the restore settles without
 * anything to hydrate, and the ChannelsReady auto-advance to MAIN replaces
 * it in the success case.
 */
@Composable
fun OnboardingSyncProgressScreen(
    steps: List<DriveSyncManager.RestoreStep>,
    channelsState: DriveSyncManager.RestoreStepState,
) {
    // Swallow BACK ONLY while a category is actively applying -- interrupting
    // mid-apply could leave a half-restored DB. Once the restore finishes,
    // fails, or stalls (e.g. a dead-network pull stuck in Pending), nothing is
    // Running, so let BACK through: this screen has nothing focusable, so an
    // always-on BackHandler would trap the user on a dead-end with no way out
    // on any non-success outcome. The host still dismisses on its own in the
    // success case (ChannelsReady -> MAIN). Leaving between two category applies
    // is safe -- each apply is its own transaction; the next sync completes it.
    val restoreInProgress = steps.any {
        it.state == DriveSyncManager.RestoreStepState.Running
    } || channelsState == DriveSyncManager.RestoreStepState.Running
    BackHandler(enabled = restoreInProgress) { /* no-op while a step is applying */ }

    // Before the tracked pull publishes its list there is a brief window with
    // no steps; render every category as Pending so the card doesn't pop in.
    val displaySteps = steps.ifEmpty {
        SyncCategory.entries.map { DriveSyncManager.RestoreStep(category = it) }
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
                text = "Restoring Your Data",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Pulling your synced setup from Google Drive",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(vertical = 8.dp),
            ) {
                displaySteps.forEach { step ->
                    RestoreRow(
                        icon = step.category.lineIcon,
                        label = step.category.lineLabel,
                        state = step.state,
                        detail = step.detail,
                    )
                }
                // The playlist bootstrap that follows the category pulls --
                // channel list + guide hydration in one line, driven by
                // PlaylistViewModel Phase (its load is monolithic; there is
                // no separate channels-vs-EPG signal to split on).
                RestoreRow(
                    icon = Icons.Filled.LiveTv,
                    label = "Channels & Guide",
                    state = channelsState,
                    detail = if (channelsState == DriveSyncManager.RestoreStepState.Running) {
                        "Loading channels and guide"
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Friendlier short labels than SyncCategory.displayName (which is sized for
 * the Settings toggles, e.g. "Playlists & Servers"). */
private val SyncCategory.lineLabel: String
    get() = when (this) {
        SyncCategory.Playlists -> "Playlists"
        SyncCategory.WatchProgress -> "Watch progress"
        SyncCategory.Reminders -> "Reminders"
        SyncCategory.Preferences -> "Preferences"
        SyncCategory.Credentials -> "Credentials"
    }

private val SyncCategory.lineIcon: ImageVector
    get() = when (this) {
        SyncCategory.Playlists -> Icons.AutoMirrored.Filled.PlaylistPlay
        SyncCategory.WatchProgress -> Icons.Filled.History
        SyncCategory.Reminders -> Icons.Filled.Notifications
        SyncCategory.Preferences -> Icons.Filled.Tune
        SyncCategory.Credentials -> Icons.Filled.Key
    }

@Composable
private fun RestoreRow(
    icon: ImageVector,
    label: String,
    state: DriveSyncManager.RestoreStepState,
    detail: String?,
) {
    val pending = state == DriveSyncManager.RestoreStepState.Pending
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (pending) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (pending) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == DriveSyncManager.RestoreStepState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (state) {
                DriveSyncManager.RestoreStepState.Pending -> Icon(
                    imageVector = Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
                DriveSyncManager.RestoreStepState.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Same green as SettingUpScreen's done check.
                DriveSyncManager.RestoreStepState.Done -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(20.dp),
                )
                DriveSyncManager.RestoreStepState.Failed -> Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
