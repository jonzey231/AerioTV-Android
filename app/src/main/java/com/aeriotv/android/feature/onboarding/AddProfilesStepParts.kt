package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.feature.settings.isServerAdmin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aeriotv.android.ui.settings.dpadFocusRing
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.focusRequester
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
    ProfileRole.resolve(userLevel, role).label

/** ON THIS PHONE/TABLET/TV: the profiles already here, each with an "Added" chip. */
@Composable
internal fun OnThisDeviceSection(
    device: String,
    profiles: List<DispatcharrProfile>,
    /** Profiles added from the roster this session: Remove instead of the chip. */
    undoable: Set<String> = emptySet(),
    onRemove: (DispatcharrProfile) -> Unit = {},
    removeEnabled: Boolean = true,
    /** Profile id whose Remove should take focus (TV, right after Add). */
    focusRemoveFor: String? = null,
    onFocusConsumed: () -> Unit = {},
) {
    if (profiles.isEmpty()) return
    // TV: Apple TV's rows at half scale (56 pt avatar, 18 pt gaps, 8 / 6 pt
    // padding, a 60 pt remove target). The phone 56 dp rows drew 112 px
    // tall on a 1080p TV.
    val isTv = rememberIsTvDevice()
    val avatar = if (isTv) 28 else 36
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OnboardingSectionLabel("On this $device")
        profiles.forEach { p ->
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = if (isTv) 34.dp else 56.dp)
                    .padding(horizontal = if (isTv) 4.dp else 8.dp, vertical = if (isTv) 3.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Fixed size, never squashed by a long name.
                Box(Modifier.requiredSize(avatar.dp)) {
                    com.aeriotv.android.feature.settings.ProfileAvatar(p, size = avatar)
                }
                Spacer(Modifier.size(if (isTv) 9.dp else 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.displayName.ifBlank { p.username }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (p.isDefault) "Signed in now. ${p.roleLabel()}." else p.roleLabel(),
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!p.isDefault && p.id in undoable) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    if (focusRemoveFor == p.id) {
                        androidx.compose.runtime.LaunchedEffect(focusRemoveFor) {
                            kotlinx.coroutines.delay(50)
                            runCatching { focus.requestFocus() }
                            onFocusConsumed()
                        }
                    }
                    // Added chip stays; a red minus beside it removes the
                    // profile from this device (Logan, 2026-10-29).
                    AddedChip()
                    Spacer(Modifier.size(if (isTv) 9.dp else 6.dp))
                    androidx.compose.material3.IconButton(
                        onClick = { onRemove(p) },
                        enabled = removeEnabled,
                        modifier = Modifier
                            .size(if (isTv) 30.dp else 40.dp)
                            .focusRequester(focus)
                            .dpadFocusRing(CircleShape),
                    ) {
                        Icon(
                            Icons.Filled.RemoveCircle,
                            contentDescription = "Remove ${p.displayName.ifBlank { p.username }} from this device",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = if (isTv) Modifier.size(17.dp) else Modifier,
                        )
                    }
                } else AddedChip()
            }
        }
    }
}

@Composable
private fun AddedChip() {
    // TV: Apple TV's chip at half scale (10 / 4 pt padding, 18 pt check, 4 pt gap).
    val isTv = rememberIsTvDevice()
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = if (isTv) 5.dp else 10.dp, vertical = if (isTv) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (isTv) 9.dp else 14.dp))
        Spacer(Modifier.size(if (isTv) 2.dp else 4.dp))
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
        // TV: the compact card the Welcome and Connect a Server cards use.
        compact = rememberIsTvDevice(),
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

/**
 * One server user under "Add from Dispatcharr": the checkbox row, and the
 * role chip once it is checked (TV: two focus stops, the chip right of the
 * row). [rowModifier] lands on the focusable row (the first row's focus
 * target). TV: Apple TV's ProfileRowButtonStyle at half scale (16 pt
 * corners, 16 pt vertical padding, 18 pt gap).
 */
@Composable
internal fun ServerUserPickRow(
    user: DispatcharrUserSummary,
    checked: Boolean,
    enabled: Boolean,
    role: ProfileRole,
    onCheckedChange: (Boolean) -> Unit,
    onRole: (ProfileRole) -> Unit,
    rowModifier: Modifier = Modifier,
) {
    val isTv = rememberIsTvDevice()
    val shape = RoundedCornerShape(if (isTv) 8.dp else 12.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(rowModifier)
                .heightIn(min = if (isTv) 44.dp else 56.dp)
                .clip(shape)
                .dpadFocusRing(shape, washTint = MaterialTheme.colorScheme.primary)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                )
                .padding(horizontal = 12.dp, vertical = if (isTv) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.size(if (isTv) 9.dp else 12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.username, style = MaterialTheme.typography.titleMedium)
                com.aeriotv.android.feature.settings.ServerUserCaption(user)
            }
        }
        if (checked) com.aeriotv.android.feature.settings.RoleChip(role, enabled = enabled, locked = user.isServerAdmin(), onChange = onRole)
    }
}

/**
 * TV page of "Who watches on this TV?" (admin login): Apple TV's pickBody
 * at half scale, since a 1080p Android TV is a 960 x 540 dp canvas where
 * tvOS has 1920 x 1080 points. One centered 550 dp column (tvOS 1100 pt),
 * 12 dp between the header, the roster, and the controls (tvOS 24 pt),
 * and the controls in a 320 dp column under the roster (tvOS 640 pt).
 * b3cc85db carried 1100 and 640 over as dp, which ran the roster edge to
 * edge on the 960 dp Streamer and stretched Continue across two thirds of
 * the screen (Logan, 2026-11-10). When it fits, the block sits centered
 * with the same space above and below; when it does not, the page scrolls
 * as one with 24 dp at each end, and focus back near the top scrolls it to
 * the top so the header shows (Logan, 2026-11-11).
 */
@Composable
internal fun TvWhoWatchesColumn(
    header: @Composable () -> Unit,
    list: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    // Focus back on a control near the top scrolls the page to its top.
    com.aeriotv.android.ui.tv.TvTopSnapPage(scroll) {
        Column(
            // verticalScroll keeps the screen height as the minimum, so the
            // centered arrangement below has room to center in when it fits.
            modifier = Modifier
                .widthIn(max = 550.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(scroll)
                .padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Inside {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { header() }
                list()
                Column(
                    Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { controls() }
            }
        }
    }
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
