package com.aeriotv.android.feature.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
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
            subtitle = "Switch profiles, Admin PIN",
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
    var adding by remember { mutableStateOf(false) }
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
        // A switch lands on the Live TV root: focus the guide grid after.
        com.aeriotv.android.core.profiles.ProfileSwitchFocus.arm(com.aeriotv.android.core.profiles.ProfileSwitchFocus.Target.Guide)
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
                // Kid-only device (and any Child): behind the Admin PIN a parent
                // can add their own profile, roster suggestions included.
                item("add") {
                    SettingsCard {
                        SettingsActionRow(
                            label = "Add Profile",
                            leadingIcon = androidx.compose.material.icons.Icons.Filled.Add,
                            onClick = { vm.setPickerAddUnlocked(pid); adding = true },
                        )
                        if (vm.hasDeviceAdminPin(pid)) DeviceAdminPinRow(pid, vm)
                    }
                }
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
    if (adding) AddProfileDialog(pid, vm) { adding = false; vm.setPickerAddUnlocked(null) }
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
    val pickerVm: ProfilesViewModel = hiltViewModel()
    val adder = rememberPickerAdder(pk.playlistId, pickerVm, profiles)
    val launchRole by com.aeriotv.android.core.profiles.ProfileRoles.active.collectAsStateWithLifecycle()
    var moveFor by remember { mutableStateOf<DispatcharrProfile?>(null) }

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
                        val isLast = p.id == pk.lastProfileId || (pk.lastProfileId == null && p.isDefault)
                        WhoIsWatchingTile(
                            profile = p,
                            locked = manager.hasPin(p.id),
                            enabled = !busy,
                            modifier = (if (isLast) Modifier.focusRequester(lastFocus) else Modifier)
                                .focusRequester(adder.requester(p.id)),
                            onLongClick = if (launchRole == com.aeriotv.android.core.profiles.ProfileRole.Admin) {
                                { if (!busy) moveFor = p }
                            } else null,
                        ) { if (manager.hasPin(p.id)) pinFor = p else choose(p) }
                    }
                    if (com.aeriotv.android.core.profiles.RoleRules.canSeeSwitcher(launchRole)) {
                        item("add") { PickerAddTile(enabled = !busy, onStart = adder::start) }
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
    moveFor?.let { p ->
        ProfileMoveSheet(p, profiles, onDismiss = { moveFor = null }) { how ->
            moveFor = null
            error = manager.move(pk.playlistId, p.id, how).exceptionOrNull()?.message
            scope.launch { restoreFocus(adder.requester(p.id), "picker tile after move") }
        }
    }
    adder.Dialogs()
}

/**
 * Admin reorder sheet for a "Who is watching?" tile (launch picker and the
 * TV top-bar picker): Move Left, Move Right, and Move to Front.
 */
@Composable
internal fun ProfileMoveSheet(
    profile: DispatcharrProfile,
    profiles: List<DispatcharrProfile>,
    onDismiss: () -> Unit,
    onMove: (com.aeriotv.android.core.profiles.ProfileManager.Move) -> Unit,
) {
    val at = profiles.indexOfFirst { it.id == profile.id }
    com.aeriotv.android.ui.settings.SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.displayName) },
        text = {
            Column {
                if (at > 0) ChoiceRow(label = "Move Left", onClick = { onMove(com.aeriotv.android.core.profiles.ProfileManager.Move.Earlier) })
                if (at in 0 until profiles.lastIndex) ChoiceRow(label = "Move Right", onClick = { onMove(com.aeriotv.android.core.profiles.ProfileManager.Move.Later) })
                if (at > 0) ChoiceRow(label = "Move to Front", onClick = { onMove(com.aeriotv.android.core.profiles.ProfileManager.Move.First) })
            }
        },
        confirmButton = {},
        dismissButton = { com.aeriotv.android.ui.settings.SettingsDialogTextButton(label = "Cancel", onClick = onDismiss) },
    )
}

/** True while the active profile is a Child (reactive). */
@Composable
fun rememberIsChildProfile(): Boolean =
    com.aeriotv.android.core.profiles.ProfileRoles.active.collectAsStateWithLifecycle().value ==
        com.aeriotv.android.core.profiles.ProfileRole.Child

/**
 * One "Who is watching?" tile (launch picker and the TV top-bar picker):
 * avatar with the round accent ring on focus (no square highlight), dimmed
 * at rest, lock badge, name; [current] adds a "Current" caption.
 */
@Composable
internal fun WhoIsWatchingTile(
    profile: DispatcharrProfile,
    locked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    /** Admin only: opens the reorder sheet; null = no long press. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(120.dp)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
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
                ProfileAvatar(profile = profile, size = 88)
            }
            if (locked) {
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
            profile.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (focused) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
            color = if (focused) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (current) {
            Text("Current", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Android TV top-bar profile picker (2c): the launch picker's full-screen
 * "Who is watching?" with the current profile focused and captioned, Back
 * closes without switching, and a Manage Profiles tile (Admin / User) that
 * opens the playlist's Settings page. Switching follows the usual PIN and
 * stop-playback rules.
 */
@Composable
fun TvProfilePickerDialog(onDismiss: () -> Unit) {
    val vm: ProfilesViewModel = hiltViewModel()
    val playlistId by vm.activePlaylistId.collectAsStateWithLifecycle(initialValue = null)
    val pid = playlistId ?: return
    val profiles by vm.profiles(pid).collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by vm.activeProfileId(pid).collectAsStateWithLifecycle(initialValue = null)
    val role by vm.activeRole.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var confirm by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val currentFocus = remember { FocusRequester() }
    val currentId = activeId ?: profiles.firstOrNull { it.isDefault }?.id
    val adder = rememberPickerAdder(pid, vm, profiles)
    var moveFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    val moveScope = rememberCoroutineScope()

    fun doSwitch(p: DispatcharrProfile) {
        busy = true; error = null
        com.aeriotv.android.core.profiles.ProfileSwitchFocus.arm(com.aeriotv.android.core.profiles.ProfileSwitchFocus.Target.Guide)
        vm.switchTo(pid, p.id) { err -> busy = false; error = err; if (err == null) onDismiss() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Who is watching?", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(32.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    itemsIndexed(profiles, key = { _, p -> p.id }) { _, p ->
                        val isCurrent = p.id == currentId
                        WhoIsWatchingTile(
                            profile = p,
                            locked = vm.hasPin(p.id),
                            enabled = !busy,
                            current = isCurrent,
                            modifier = (if (isCurrent) Modifier.focusRequester(currentFocus) else Modifier)
                                .focusRequester(adder.requester(p.id)),
                            onLongClick = if (role == com.aeriotv.android.core.profiles.ProfileRole.Admin) {
                                { if (!busy) moveFor = p }
                            } else null,
                        ) {
                            when {
                                isCurrent -> onDismiss()
                                vm.hasPin(p.id) -> pinFor = p
                                vm.isPlaying() -> confirm = p
                                else -> doSwitch(p)
                            }
                        }
                    }
                    if (com.aeriotv.android.core.profiles.RoleRules.canSeeSwitcher(role)) {
                        item("add") { PickerAddTile(enabled = !busy, onStart = adder::start) }
                        item("manage") {
                            PickerActionTile(Icons.Outlined.ManageAccounts, "Manage Profiles", enabled = !busy) {
                                Log.i("AerioProfiles", "[PROFILE] manage profiles from tv picker")
                                onDismiss()
                                vm.manageProfiles()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    LaunchedEffect(profiles.size, currentId) {
        if (profiles.isEmpty()) return@LaunchedEffect
        repeat(10) {
            if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(32L)
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
    moveFor?.let { p ->
        ProfileMoveSheet(p, profiles, onDismiss = { moveFor = null }) { how ->
            moveFor = null
            error = vm.move(pid, p.id, how).exceptionOrNull()?.message
            moveScope.launch { restoreFocus(adder.requester(p.id), "picker tile after move") }
        }
    }
    adder.Dialogs()
}

/** A picker tile that is an action, not a profile (Add Profile, Manage Profiles). */
@Composable
internal fun PickerActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(120.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .tvFocusScale(focused, focusedScale = 1.08f)
                .border(
                    com.aeriotv.android.ui.tv.TvChrome.ringWidth + 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    CircleShape,
                )
                .padding(5.dp)
                .alpha(if (focused) 1f else 0.6f),
        ) {
            Box(
                Modifier.size(88.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun PickerAddTile(enabled: Boolean, onStart: () -> Unit) =
    PickerActionTile(androidx.compose.material.icons.Icons.Filled.Add, "Add Profile", enabled) {
        Log.i("AerioProfiles", "[PROFILE] add profile from picker")
        onStart()
    }

/**
 * Picker "Add Profile": an Admin PIN first when any Admin has one, then the
 * existing Add Profile dialog over the picker; the new profile's tile takes
 * focus once it appears.
 */
internal class PickerAdder(
    private val playlistId: String,
    private val vm: ProfilesViewModel,
) {
    var askPin by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var knownIds: Set<String>? by mutableStateOf(null)
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun requester(id: String): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    private fun open() {
        vm.setPickerAddUnlocked(playlistId)
        adding = true
    }

    fun start(current: List<DispatcharrProfile>) {
        knownIds = current.map { it.id }.toSet()
        if (vm.adminHasPin(playlistId)) askPin = true else open()
    }

    @Composable
    fun Dialogs() {
        if (askPin) {
            AdminPinDialog(playlistId, vm, title = "Enter an Admin PIN", onDismiss = { askPin = false; knownIds = null }) {
                askPin = false
                open()
            }
        }
        if (adding) AddProfileDialog(playlistId, vm) { adding = false; vm.setPickerAddUnlocked(null) }
    }
}

@Composable
internal fun rememberPickerAdder(playlistId: String, vm: ProfilesViewModel, profiles: List<DispatcharrProfile>): PickerAdderHandle {
    val adder = remember(playlistId) { PickerAdder(playlistId, vm) }
    val profilesState = androidx.compose.runtime.rememberUpdatedState(profiles)
    // Focus the new tile once the added profile shows up.
    LaunchedEffect(profiles.map { it.id }, adder.adding) {
        val known = adder.knownIds ?: return@LaunchedEffect
        if (adder.adding || adder.askPin) return@LaunchedEffect
        val added = profiles.firstOrNull { it.id !in known } ?: return@LaunchedEffect
        adder.knownIds = null
        repeat(20) {
            if (runCatching { adder.requester(added.id).requestFocus() }.isSuccess) {
                Log.i("AerioProfiles", "[PROFILE] picker focus on added ${added.username}")
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50L)
        }
    }
    return remember(adder) { PickerAdderHandle(adder) { profilesState.value } }
}

internal class PickerAdderHandle(private val adder: PickerAdder, private val current: () -> List<DispatcharrProfile>) {
    fun start() = adder.start(current())
    fun requester(id: String) = adder.requester(id)
    @Composable fun Dialogs() = adder.Dialogs()
}
