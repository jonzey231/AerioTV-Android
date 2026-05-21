package com.aeriotv.android.feature.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin VM exposing the app-scoped [ReminderBannerBus] to the navigation root.
 * Keeps the root composable free of an EntryPoint accessor.
 */
@HiltViewModel
class ReminderBannerViewModel @Inject constructor(
    private val bus: ReminderBannerBus,
) : ViewModel() {
    val banner: StateFlow<ReminderBannerData?> = bus.banner
    fun dismiss() = bus.dismiss()
}

/**
 * In-app reminder banner overlay. Mirrors iOS ReminderBannerView: when a
 * reminder fires while the app is foregrounded, a banner slides down from the
 * top. Tapping it navigates to the channel; it also auto-dismisses after a few
 * seconds or when the user taps the close button. Render this at the
 * navigation root, above the NavHost, so it floats over every screen.
 *
 * [onOpenChannel] receives the reminded channelId (may be blank for legacy
 * reminders set before channelId plumbing; callers should no-op-navigate in
 * that case).
 */
@Composable
fun ReminderBannerHost(
    onOpenChannel: (channelId: String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ReminderBannerViewModel = hiltViewModel(),
) {
    val data by vm.banner.collectAsStateWithLifecycle()

    // Auto-dismiss after 8s of visibility. Keyed on the program title so a new
    // reminder arriving resets the timer.
    LaunchedEffect(data?.programTitle, data?.startMillisKey()) {
        if (data != null) {
            kotlinx.coroutines.delay(8_000L)
            vm.dismiss()
        }
    }

    val banner = data
    if (banner != null) {
        // Outer container carries the overlay alignment + status-bar inset.
        // NOTE: do NOT put statusBarsPadding() in the same modifier chain as
        // the Row's fillMaxWidth()/height() -- in this edge-to-edge overlay it
        // collapses the Row to ~0 height. Keeping the inset on this wrapper Box
        // (which wraps content height) avoids that.
        Box(
            modifier = modifier
                .zIndex(100f)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            vm.dismiss()
                            if (banner.channelId.isNotBlank()) onOpenChannel(banner.channelId)
                        }
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = banner.programTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (banner.channelName.isNotBlank())
                                "Starting now on ${banner.channelName}"
                            else
                                "Starting now",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { vm.dismiss() }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
    }
}

/** Stable-ish key so the auto-dismiss timer resets per distinct banner. */
private fun ReminderBannerData.startMillisKey(): String = "$channelId|$channelName"
