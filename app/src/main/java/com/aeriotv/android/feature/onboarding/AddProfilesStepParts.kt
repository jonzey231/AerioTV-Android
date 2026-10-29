package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.profiles.DispatcharrProfile
import com.aeriotv.android.core.profiles.ProfileRole
import com.aeriotv.android.feature.onboarding.components.OnboardingCard
import com.aeriotv.android.feature.onboarding.components.OnboardingSectionLabel
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.rememberIsTvDevice

internal const val ROSTER_FOOTNOTE =
    "Adding a profile here copies its name, avatar, role, and order. Skipping it changes nothing on the other device."

/** "phone", "tablet", or "TV" for the per-device profile copy. */
@Composable
internal fun rememberDeviceNoun(): String {
    if (rememberIsTvDevice()) return "TV"
    return if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
}

@Composable
internal fun ProfilesStepHeader(device: String) {
    Text(
        "Who watches on this $device?",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
    Text(
        "Profiles are chosen per device, so a shared TV and a personal phone can differ. Add the ones that belong here.",
        style = MaterialTheme.typography.bodyMedium.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun DispatcharrProfile.roleLabel(): String =
    (role ?: if (isDefault || userLevel >= 10) ProfileRole.Admin else ProfileRole.User).label

/** ON THIS PHONE/TABLET/TV: the profiles already here, each with an "Added" chip. */
@Composable
internal fun OnThisDeviceSection(device: String, profiles: List<DispatcharrProfile>) {
    if (profiles.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OnboardingSectionLabel("On this $device")
        profiles.forEach { p ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Fixed size, never squashed by a long name.
                Box(Modifier.requiredSize(36.dp)) {
                    com.aeriotv.android.feature.settings.ProfileAvatar(p, size = 36)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.displayName.ifBlank { p.username }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (p.isDefault) "Signed in now. ${p.roleLabel()}." else p.roleLabel(),
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AddedChip()
            }
        }
    }
}

@Composable
private fun AddedChip() {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(4.dp))
        Text("Added", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Toggles the server-user list under it (sign in once per added user). The
 * list and its Search row are hidden until this card is tapped; the
 * chevron points down while the list is open.
 */
@Composable
internal fun AddFromDispatcharrRow(enabled: Boolean, expanded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OnboardingCard(
        icon = Icons.Outlined.PersonAdd,
        title = "Add from Dispatcharr",
        subtitle = "Choose an existing Dispatcharr user to add as a profile on this device.",
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        highlighted = expanded,
        trailing = {
            Icon(
                if (expanded) Icons.Filled.ExpandMore
                else Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "Hide users" else "Show users",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** Under Continue: who is on this device so far. */
@Composable
internal fun ProfileCountLine(device: String, profiles: List<DispatcharrProfile>) {
    val text = when (profiles.size) {
        0 -> return
        1 -> "Only ${profiles[0].displayName.ifBlank { profiles[0].username }} is on this $device. You can add more later in Settings."
        else -> "${profiles.size} profiles on this $device."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
