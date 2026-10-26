package com.aeriotv.android.feature.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.aeriotv.android.ui.tv.tvFocusScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.profiles.DispatcharrProfile
import com.aeriotv.android.core.profiles.PinType
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.ui.settings.SettingsActionRow
import com.aeriotv.android.ui.settings.SettingsCard
import com.aeriotv.android.ui.settings.SettingsDetailTopBar
import com.aeriotv.android.ui.settings.SettingsNavRow
import com.aeriotv.android.ui.settings.SettingsSectionFooter
import com.aeriotv.android.ui.settings.SettingsToggleRow
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import kotlinx.coroutines.launch

/**
 * Profiles phase 2b (2026-10-17): the Child profile's Manage Profiles row
 * and page, the TV launch picker, and the "Ask who is watching at launch"
 * toggle. Copy: "Manage Profiles", "Enter an Admin PIN", "Who is watching?".
 */

/** Bottom of a Child's Settings root list. */
@Composable
fun ManageProfilesRow(onClick: () -> Unit) {
    SettingsCard {
        SettingsNavRow(
            title = "Manage Profiles",
            subtitle = "Needs an Admin PIN",
            icon = Icons.Outlined.ManageAccounts,
            onClick = onClick,
        )
    }
}

/**
 * Manage Profiles for a Child: an Admin PIN first, then the profile list
 * (tap to switch). Nothing about other profiles shows before the PIN.
 */
@Composable
fun ManageProfilesPage(onBack: () -> Unit) {
    val vm: ProfilesViewModel = hiltViewModel()
    val playlistId by vm.activePlaylistId.collectAsStateWithLifecycle(initialValue = null)
    val pid = playlistId ?: return
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var confirm by remember { mutableStateOf<DispatcharrProfile?>(null) }
    val profiles by vm.profiles(pid).collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by vm.activeProfileId(pid).collectAsStateWithLifecycle(initialValue = null)
    val role by vm.activeRole.collectAsStateWithLifecycle()
    val firstRow = remember { FocusRequester() }
    // Re-lock whenever the active profile is (again) a Child.
    LaunchedEffect(role, activeId) {
        if (role == com.aeriotv.android.core.profiles.ProfileRole.Child) unlocked = false
    }
    // After the Admin PIN, focus lands on the first profile row.
    LaunchedEffect(unlocked, profiles.size) {
        if (!unlocked || profiles.isEmpty()) return@LaunchedEffect
        repeat(10) {
            if (runCatching { firstRow.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(32L)
        }
    }

    fun doSwitch(p: DispatcharrProfile) {
        busy = true; status = null
        // Leaving the Child moves the rail to Playlists: focus that row after.
        com.aeriotv.android.core.profiles.ProfileSwitchFocus.arm(com.aeriotv.android.core.profiles.ProfileSwitchFocus.Target.Rail)
        vm.switchTo(pid, p.id) { err -> busy = false; status = err }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsDetailTopBar(title = "Manage Profiles", onBack = onBack)
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!unlocked) {
                item("gate") {
                    SettingsCard {
                        SettingsActionRow(
                            label = "Manage Profiles",
                            leadingIcon = Icons.Filled.Lock,
                            onClick = {
                                if (vm.adminHasPin(pid)) {
                                    asking = true
                                } else {
                                    // Inconsistent state (no Admin PIN while a Child
                                    // exists, e.g. after a sync): never strand the Child.
                                    Log.w("AerioProfiles", "[PROFILE] manage profiles: no Admin PIN exists; opening")
                                    unlocked = true
                                }
                            },
                        )
                    }
                    SettingsSectionFooter("Enter an Admin PIN to switch profiles.")
                }
            } else {
                itemsIndexed(profiles, key = { _, p -> p.id }) { i, p ->
                    ProfileRow(
                        profile = p,
                        locked = vm.hasPin(p.id),
                        modifier = if (i == 0) Modifier.focusRequester(firstRow) else Modifier,
                        // Long press does nothing here (never a switch).
                        onLongClick = {},
                        isActive = p.id == activeId,
                        onClick = {
                            if (p.id == activeId || busy) return@ProfileRow
                            when {
                                // The Admin whose PIN opened this page holds a ticket;
                                // anyone else with a PIN gets the full pad.
                                vm.hasPin(p.id) && !vm.hasSwitchTicket(p.id) -> pinFor = p
                                vm.isPlaying() -> confirm = p
                                else -> doSwitch(p)
                            }
                        },
                    )
                }
                if (busy) item("busy") { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            }
            status?.let { item("status") { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        }
    }
    if (asking) {
        AdminPinDialog(pid, vm, title = "Enter an Admin PIN", onDismiss = { asking = false }) {
            asking = false
            unlocked = true
        }
    }
    pinFor?.let { p ->
        SwitchPinDialog(p, vm, onDismiss = { pinFor = null }) {
            pinFor = null
            if (vm.isPlaying()) confirm = p else doSwitch(p)
        }
    }
    confirm?.let { p ->
        StopPlaybackDialog(name = p.displayName, onDismiss = { confirm = null }) { confirm = null; doSwitch(p) }
    }
}

/** Settings > Playlists > (Dispatcharr playlist): Admin only, Android TV only. */
@Composable
fun AskAtLaunchRow(playlistId: String) {
    if (!rememberIsTvDevice()) return
    val vm: ProfilesViewModel = hiltViewModel()
    val activeId by vm.activeProfileId(playlistId).collectAsStateWithLifecycle(initialValue = null)
    if (activeId == null || !vm.isRoleAdmin(playlistId)) return
    var on by remember(playlistId) { mutableStateOf(vm.askAtLaunch(playlistId)) }
    SettingsCard {
        SettingsToggleRow(
            title = "Ask who is watching at launch",
            checked = on,
            onCheckedChange = { v -> on = v; vm.setAskAtLaunch(playlistId, v) },
        )
    }
}

/**
 * Android TV launch picker: full screen, avatars with initials, names, a lock
 * badge on PIN profiles; D-pad moves, OK selects; the last used profile has
 * focus. A locked profile asks for its PIN first.
 */
@Composable
fun TvLaunchProfilePicker(manager: ProfileManager) {
    val picker by manager.launchPicker.collectAsStateWithLifecycle()
    val pk = picker ?: return
    val profiles by manager.profiles(pk.playlistId).collectAsStateWithLifecycle(initialValue = emptyList())
    var pinFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lastFocus = remember { FocusRequester() }

    fun choose(p: DispatcharrProfile) {
        busy = true; error = null
        scope.launch {
            manager.pickAtLaunch(p.id).onFailure { busy = false; error = it.message ?: "Switch failed" }
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Who is watching?", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(32.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    itemsIndexed(profiles, key = { _, p -> p.id }) { _, p ->
                        var focused by remember { mutableStateOf(false) }
                        val isLast = p.id == pk.lastProfileId || (pk.lastProfileId == null && p.isDefault)
                        Column(
                            Modifier
                                .width(120.dp)
                                .then(if (isLast) Modifier.focusRequester(lastFocus) else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                // No indication: the focus visual is the round
                                // accent ring, never a square grey highlight.
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    enabled = !busy,
                                ) {
                                    if (manager.hasPin(p.id)) pinFor = p else choose(p)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.tvFocusScale(focused, focusedScale = 1.08f)) {
                                Box(
                                    Modifier
                                        .border(
                                            com.aeriotv.android.ui.tv.TvChrome.ringWidth + 1.dp,
                                            if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            CircleShape,
                                        )
                                        .padding(5.dp)
                                        .alpha(if (focused) 1f else 0.6f),
                                ) {
                                    ProfileAvatar(profile = p, size = 88)
                                }
                                if (manager.hasPin(p.id)) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = "PIN",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(26.dp)
                                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                            .padding(4.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                p.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (focused) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                                color = if (focused) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    LaunchedEffect(profiles.size) { if (profiles.isNotEmpty()) runCatching { lastFocus.requestFocus() } }
    pinFor?.let { p ->
        PinEntryDialog(
            title = "Enter PIN for ${p.displayName}",
            type = manager.pinType(p.id) ?: PinType.Numbers,
            pinLength = manager.pinLength(p.id),
            onDismiss = { pinFor = null },
            onEntered = { seq ->
                val r = manager.verifyPin(p.id, seq)
                if (r == ProfileManager.PinCheck.Ok) { pinFor = null; choose(p); null } else pinCheckMessage(r)
            },
            lockSeconds = { manager.pinLockSeconds(p.id) },
        )
    }
}

/** True while the active profile is a Child (reactive). */
@Composable
fun rememberIsChildProfile(): Boolean =
    com.aeriotv.android.core.profiles.ProfileRoles.active.collectAsStateWithLifecycle().value ==
        com.aeriotv.android.core.profiles.ProfileRole.Child
