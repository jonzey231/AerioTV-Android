package com.aeriotv.android.feature.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import com.aeriotv.android.ui.tv.tvFocusScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.core.profiles.DispatcharrProfile
import com.aeriotv.android.core.profiles.ProfileLevel
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.profiles.effectiveRole
import com.aeriotv.android.ui.settings.SettingsCard
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.SettingsSectionFooter
import com.aeriotv.android.ui.settings.SettingsSectionHeader
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsRowCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Profiles UI (phase 1, 2026-10-16): the Profiles section on a Dispatcharr
 * playlist's settings page (every form factor, the TV switcher lives here),
 * and the phone / tablet Live TV header switcher. Copy: "Profiles",
 * "Add Profile", "Manage Profiles", "Switch Profile".
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val manager: ProfileManager,
    playlistDao: PlaylistDao,
    private val exoHolder: com.aeriotv.android.core.playback.AerioExoPlayerHolder,
) : ViewModel() {

    val activePlaylistId: Flow<String?> =
        playlistDao.observeActive().map { it.firstOrNull()?.id }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activePlaylistProfiles: Flow<List<DispatcharrProfile>> =
        activePlaylistId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else manager.profiles(id) }

    fun profiles(playlistId: String) = manager.profiles(playlistId)
    /** A picker surface opened: prompt profiles pull from Dispatcharr Sync (bounded, non-blocking). */
    fun notePickerOpened(playlistId: String, reason: String) = manager.notePickerOpened(playlistId, reason)
    fun activeProfileId(playlistId: String) = manager.activeProfileId(playlistId)
    /** App-local Admin role: add and remove profiles (server level not needed). */
    /** Set up a TV sender gate (docs/DeviceSetupHandoff.md). */
    fun canSetUpTv(playlistId: String): Boolean = manager.canSetUpTv(playlistId)

    suspend fun isAdmin(playlistId: String) =
        com.aeriotv.android.core.profiles.RoleRules.canManageProfiles(manager.activeRole(playlistId)) &&
            manager.activeRole(playlistId) != null
    /** Channel Profiles are assigned on the server: base and active must be server admins. */
    suspend fun canAssignChannelProfiles(playlistId: String) =
        com.aeriotv.android.core.profiles.RoleRules.canAssignChannelProfiles(
            manager.baseIsServerAdmin(playlistId), manager.activeIsAdmin(playlistId), manager.activeRole(playlistId),
        )
    suspend fun addCandidates(playlistId: String) = manager.addCandidates(playlistId)
    suspend fun addManual(playlistId: String, username: String, password: String?, apiKey: String?, role: com.aeriotv.android.core.profiles.ProfileRole? = null) =
        if (apiKey != null) manager.addProfileByApiKey(playlistId, apiKey, role)
        else manager.addProfileManual(playlistId, username, password, null, role)
    /** Admin or Standard (identical in AerioTV). */
    fun isRoleAdmin(playlistId: String) = com.aeriotv.android.core.profiles.RoleRules.isFullAccess(manager.activeRole(playlistId))
    fun pinsMissingForChild(playlistId: String, becomingChild: String? = null) = manager.pinsMissingForChild(playlistId, becomingChild)
    fun needsPinAfterAdd(playlistId: String, profileId: String) = manager.needsPinAfterAdd(playlistId, profileId)
    fun isPendingPinAdd(profileId: String) = manager.isPendingPinAdd(profileId)
    suspend fun discardPendingAdds(playlistId: String) = manager.discardPendingAdds(playlistId)
    val activeRole = com.aeriotv.android.core.profiles.ProfileRoles.active

    // Roles (phase 2b)
    fun roleOf(profileId: String) = manager.roleOf(profileId)
    fun roleChangeGate(playlistId: String, profileId: String, to: com.aeriotv.android.core.profiles.ProfileRole) =
        manager.roleChangeGate(playlistId, profileId, to)
    fun setRole(playlistId: String, profileId: String, to: com.aeriotv.android.core.profiles.ProfileRole) =
        manager.setRole(playlistId, profileId, to)
    fun verifyAnyAdminPin(playlistId: String, entries: List<String>) = manager.verifyAnyAdminPin(playlistId, entries)
    fun adminPinType(playlistId: String) = manager.adminPinIds(playlistId).firstOrNull()?.let { manager.pinType(it) }
    // Device Admin PIN (device-local)
    fun hasDeviceAdminPin(playlistId: String) = manager.hasDeviceAdminPin(playlistId)
    fun deviceAdminPinId(playlistId: String) = manager.deviceAdminPinId(playlistId)
    fun setDeviceAdminPin(playlistId: String, type: com.aeriotv.android.core.profiles.PinType, entries: List<String>, current: List<String>?, onboarding: Boolean = false) =
        manager.setDeviceAdminPin(playlistId, type, entries, current, onboarding)
    fun removeDeviceAdminPin(playlistId: String, current: List<String>) = manager.removeDeviceAdminPin(playlistId, current)
    fun deviceAdminPinRemovalBlock(playlistId: String) = manager.deviceAdminPinRemovalBlock(playlistId)
    fun makeBaseChild(playlistId: String) = manager.makeBaseChild(playlistId)
    fun rosterSuggestions(playlistId: String) = manager.rosterSuggestions(playlistId)
    suspend fun addFromRoster(playlistId: String, row: com.aeriotv.android.core.sync.dsync.ProfileRow, user: DispatcharrUserSummary, password: String?, apiKey: String?) =
        manager.addFromRoster(playlistId, row, user, password, apiKey)
    suspend fun undoAdd(profileId: String) = manager.undoAdd(profileId)
    val undoableRosterAdds get() = manager.undoableRosterAdds
    fun adminHasPin(playlistId: String) = manager.adminHasPin(playlistId)
    /** Assumption A: a Child may be added or set now (every Admin and Standard profile has a PIN). */
    fun childGateClear(playlistId: String, becomingChild: String? = null) =
        manager.adminHasPin(playlistId) && manager.pinsMissingForChild(playlistId, becomingChild).isEmpty()
    suspend fun canManageUserApiKeys(playlistId: String) = manager.canManageUserApiKeys(playlistId)
    suspend fun generateUserApiKey(playlistId: String, userId: Int) = manager.generateUserApiKey(playlistId, userId)
    suspend fun revokeUserApiKey(playlistId: String, userId: Int) = manager.revokeUserApiKey(playlistId, userId)
    fun lastAdminUnlock() = manager.lastAdminUnlock
    fun anyChild(playlistId: String) = manager.anyChild(playlistId)
    fun activeProfile(playlistId: String) = manager.activeProfile(playlistId)
    fun pinRemovalBlock(playlistId: String, profileId: String) = manager.pinRemovalBlock(playlistId, profileId)
    fun resetGate(playlistId: String) = manager.resetGate(playlistId)
    suspend fun resetPin(playlistId: String, profileId: String, adminEntries: List<String>?, replacement: Pair<com.aeriotv.android.core.profiles.PinType, List<String>>? = null) =
        manager.resetPin(playlistId, profileId, adminEntries, replacement)
    fun askAtLaunch(playlistId: String) = manager.askAtLaunch(playlistId)
    fun setAskAtLaunch(playlistId: String, on: Boolean) = manager.setAskAtLaunch(playlistId, on)

    /** True while anything is playing (main player, mini player, background audio). */
    fun isPlaying(): Boolean {
        val p = exoHolder.playerInstance.value ?: return false
        return p.playbackState == androidx.media3.common.Player.STATE_READY ||
            p.playbackState == androidx.media3.common.Player.STATE_BUFFERING
    }

    fun switchTo(playlistId: String, profileId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val r = manager.switchTo(playlistId, profileId)
            onDone(r.exceptionOrNull()?.let { it.message ?: "Switch failed" })
        }
    }

    suspend fun listUsers(playlistId: String) = manager.listServerUsers(playlistId)
    suspend fun add(playlistId: String, user: DispatcharrUserSummary, password: String?, apiKey: String?, role: com.aeriotv.android.core.profiles.ProfileRole? = null) =
        manager.addProfile(playlistId, user, password, apiKey, role)
    fun rename(profileId: String, name: String) = manager.rename(profileId, name)
    fun move(playlistId: String, profileId: String, how: com.aeriotv.android.core.profiles.ProfileManager.Move) =
        manager.move(playlistId, profileId, how)
    fun setAvatar(playlistId: String, profileId: String, avatar: com.aeriotv.android.core.profiles.AvatarSpec) =
        manager.setAvatar(playlistId, profileId, avatar)
    fun canEditAvatar(playlistId: String, profileId: String) =
        com.aeriotv.android.core.profiles.RoleRules.canEditAvatar(manager.activeRole(playlistId), manager.activeProfile(playlistId)?.id == profileId)
    suspend fun remove(profileId: String) = manager.remove(profileId)
    fun manageProfiles() = manager.requestManageProfiles()
    fun setPickerAddUnlocked(playlistId: String?) { manager.pickerAddUnlocked = playlistId }

    // PIN (phase 2a)
    fun hasPin(profileId: String) = manager.hasPin(profileId)
    fun hasSwitchTicket(profileId: String) = manager.hasSwitchTicket(profileId)
    fun pinType(profileId: String) = manager.pinType(profileId)
    fun pinLength(profileId: String) = manager.pinLength(profileId)
    /** Known only when every Admin PIN holder's length is known and equal. */
    fun adminPinLength(playlistId: String): Int? =
        manager.adminPinIds(playlistId).map { manager.pinLength(it) }.distinct().singleOrNull()
    fun verifyPin(profileId: String, entries: List<String>) = manager.verifyPin(profileId, entries)
    fun pinLockSeconds(profileId: String) = manager.pinLockSeconds(profileId)
    fun adminPinLockSeconds(playlistId: String) =
        manager.adminPinIds(playlistId).minOfOrNull { manager.pinLockSeconds(it) } ?: 0
    suspend fun canManagePin(playlistId: String, profileId: String) = manager.canManagePin(playlistId, profileId)
    suspend fun needsCurrentPin(playlistId: String, profileId: String) = manager.needsCurrentPin(playlistId, profileId)
    suspend fun setPin(playlistId: String, profileId: String, type: com.aeriotv.android.core.profiles.PinType, entries: List<String>, current: List<String>?) =
        manager.setPin(playlistId, profileId, type, entries, current)
    suspend fun removePin(playlistId: String, profileId: String, current: List<String>?) =
        manager.removePin(playlistId, profileId, current)

    // Channel Profiles per user (Admin only)
    suspend fun channelProfileAssignment(playlistId: String, profileId: String) =
        manager.channelProfileAssignment(playlistId, profileId)
    suspend fun saveChannelProfiles(playlistId: String, profileId: String, userId: Int, ids: List<Int>) =
        manager.saveChannelProfiles(playlistId, profileId, userId, ids)
}

/** Phone / tablet Live TV header: avatar circle, only with 2+ profiles. */
@Composable
fun ProfileSwitcherButton(modifier: Modifier = Modifier) {
    ProfileSwitcherHost { active, open ->
        ProfileAvatar(
            profile = active,
            size = 38,
            modifier = modifier.clip(CircleShape).clickable { open() },
        )
    }
}

/**
 * Android TV top tab bar (2c): the active profile's avatar after Settings,
 * the pill height, the standard accent ring while focused. OK opens the
 * full-screen "Who is watching?" picker (TvProfilePickerDialog). Hidden for a Child and with fewer
 * than two profiles. [onDown] moves focus into the tab content.
 */
@Composable
fun TvProfileSwitcherButton(modifier: Modifier = Modifier, onDown: () -> Boolean = { false }) {
    var tvPicker by remember { mutableStateOf(false) }
    ProfileSwitcherHost { active, open ->
        // Same diameter, alignment, and 1.08 grow as the bar's Refresh and
        // Search circles, but the avatar disc IS the button: it fills the
        // whole circle (no inset, no platter) and focus rings its edge.
        com.aeriotv.android.ui.tv.TvActionCircleSurface(
            bare = true,
            modifier = modifier.onPreviewKeyEvent { e ->
                e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    e.key == androidx.compose.ui.input.key.Key.DirectionDown && onDown()
            },
            onClick = {
                // TV: the full-screen "Who is watching?" picker, not the sheet.
                android.util.Log.i("AerioProfiles", "[PROFILE] picker opened (tv top bar)")
                tvPicker = true
            },
        ) {
            ProfileAvatar(
                profile = active,
                size = com.aeriotv.android.ui.tv.TvChrome.circleSize.value.toInt(),
            )
        }
        if (tvPicker) TvProfilePickerDialog(onDismiss = { tvPicker = false })
    }
}

/** Visibility, state, and the Switch Profile sheet shared by both switchers. */
@Composable
private fun ProfileSwitcherHost(button: @Composable (active: DispatcharrProfile, open: () -> Unit) -> Unit) {
    val vm: ProfilesViewModel = hiltViewModel()
    val playlistId by vm.activePlaylistId.collectAsStateWithLifecycle(initialValue = null)
    val profiles by vm.activePlaylistProfiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val pid = playlistId ?: return
    if (profiles.size < 2) return
    // Phase 2b: a Child never sees the switcher (or any other profile name).
    val role by vm.activeRole.collectAsStateWithLifecycle()
    if (!com.aeriotv.android.core.profiles.RoleRules.canSeeSwitcher(role)) return
    val activeId by vm.activeProfileId(pid).collectAsStateWithLifecycle(initialValue = null)
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<DispatcharrProfile?>(null) }

    button(active) { error = null; open = true }

    fun doSwitch(p: DispatcharrProfile) {
        busy = true
        vm.switchTo(pid, p.id) { err -> busy = false; error = err; if (err == null) open = false }
    }

    if (open) {
        LaunchedEffect(pid) { vm.notePickerOpened(pid, "switch") }
        // Few profiles: the resting height shows everything, so swipe down
        // dismisses but an upward drag is inert (it used to move the sheet
        // and snap back, Logan 2026-10-29). Many profiles: the sheet expands
        // on drag and the list scrolls. The scrim always dismisses.
        val longList = profiles.size > SWITCH_SHEET_FIXED_MAX
        com.aeriotv.android.ui.FormFactorModal(
            onDismiss = { open = false },
            traceName = "switch-profile",
            sheetGesturesEnabled = true,
            sheetDragDownOnly = !longList,
            sheetExpandable = longList,
        ) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Text(
                    "Switch Profile",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                Column(
                    if (longList) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    else Modifier,
                ) {
                profiles.forEach { p ->
                    ProfileRow(
                        profile = p,
                        locked = vm.hasPin(p.id),
                        isActive = p.id == active.id,
                        modifier = if (p.id == active.id) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = {
                            if (p.id == active.id || busy) return@ProfileRow
                            if (vm.hasPin(p.id)) pinFor = p
                            else if (vm.isPlaying()) confirm = p else doSwitch(p)
                        },
                    )
                }
                }
                LaunchedEffect(Unit) {
                    repeat(10) {
                        if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                        kotlinx.coroutines.delay(32L)
                    }
                }
                if (busy) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                // The last profile row already draws its hairline; a second
                // divider here doubled it (Logan, 2026-10-29).
                Spacer(Modifier.height(6.dp))
                var mFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { mFocused = it.isFocused }
                        .settingsRowCard(mFocused)
                        .clickable { open = false; vm.manageProfiles() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ManageAccounts, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text("Manage Profiles", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    confirm?.let { p ->
        StopPlaybackDialog(name = p.displayName, onDismiss = { confirm = null }) {
            confirm = null
            doSwitch(p)
        }
    }
    pinFor?.let { p ->
        SwitchPinDialog(p, vm, onDismiss = { pinFor = null }) {
            pinFor = null
            if (vm.isPlaying()) confirm = p else doSwitch(p)
        }
    }
}

/** "Enter PIN for {name}" before switching to a locked profile. */
@Composable
internal fun SwitchPinDialog(p: DispatcharrProfile, vm: ProfilesViewModel, onDismiss: () -> Unit, onOk: () -> Unit) {
    PinEntryDialog(
        title = "Enter PIN for ${p.displayName}",
        type = vm.pinType(p.id) ?: com.aeriotv.android.core.profiles.PinType.Numbers,
        pinLength = vm.pinLength(p.id),
        onDismiss = onDismiss,
        onEntered = { seq ->
            val r = vm.verifyPin(p.id, seq)
            if (r == com.aeriotv.android.core.profiles.ProfileManager.PinCheck.Ok) { onOk(); null } else pinCheckMessage(r)
        },
        lockSeconds = { vm.pinLockSeconds(p.id) },
    )
}

@Composable
internal fun StopPlaybackDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    com.aeriotv.android.ui.settings.SettingsAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Profile") },
        text = { Text("Stop playback and switch to $name?") },
        confirmButton = { SettingsDialogTextButton(label = "Switch", onClick = onConfirm) },
        dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = onDismiss) },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
/** Switch Profile sheet: up to this many rows sit at the resting height without a drag. */
private const val SWITCH_SHEET_FIXED_MAX = 4

@Composable
internal fun ProfileRow(
    profile: DispatcharrProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    // Never null: a TV long press must never fall through to onClick (switch).
    onLongClick: () -> Unit = {},
    locked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .settingsRowCard(focused)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(profile, 32)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(profile.effectiveRole().label, if (locked) "PIN" else null).joinToString("  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            val sub = buildList {
                add(profile.username)
                add(
                    when (ProfileLevel.of(profile.userLevel)) {
                        ProfileLevel.Admin -> "Dispatcharr admin"
                        ProfileLevel.Standard -> "Standard"
                        ProfileLevel.Limited -> "Limited"
                    },
                )
                if (profile.streamLimit > 0) add("Streams: ${profile.streamLimit}")
            }.joinToString("  ")
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (locked) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.Lock,
                contentDescription = "PIN",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
        }
        if (isActive) {
            Spacer(Modifier.size(8.dp))
            Icon(Icons.Filled.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun LevelBadge(level: ProfileLevel) {
    Text(
        text = level.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Settings > Playlists > (Dispatcharr playlist) > Profiles. */
@Composable
fun ProfilesSection(playlistId: String) {
    val vm: ProfilesViewModel = hiltViewModel()
    val profiles by vm.profiles(playlistId).collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by vm.activeProfileId(playlistId).collectAsStateWithLifecycle(initialValue = null)
    var isAdmin by remember { mutableStateOf(false) }
    var canChannelProfiles by remember { mutableStateOf(false) }
    var canSetUpTv by remember { mutableStateOf(false) }
    var showSetUpTv by remember { mutableStateOf(false) }
    LaunchedEffect(playlistId, activeId) {
        isAdmin = vm.isAdmin(playlistId)
        canSetUpTv = vm.canSetUpTv(playlistId)
        canChannelProfiles = vm.canAssignChannelProfiles(playlistId)
    }
    val isTv = rememberIsTvDevice()
    val showSetUpTvRow = canSetUpTv && !isTv
    val role by vm.activeRole.collectAsStateWithLifecycle()
    // Phase 2b: a Child has no Profiles section (Manage Profiles at the
    // bottom of Settings is its only way out).
    if (!com.aeriotv.android.core.profiles.RoleRules.canSeeSwitcher(role)) return
    // Nothing new for a single-profile non-admin; an Admin sees Add Profile.
    if (profiles.size < 2 && !isAdmin && !showSetUpTvRow) return

    var confirmSwitch by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var actionsFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var renaming by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var avatarFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var removing by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var settingPin by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var removingPin by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var channelProfilesFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var roleFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var resettingPin by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var removeAdminPinFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    val scope = rememberCoroutineScope()

    fun doSwitch(p: DispatcharrProfile) {
        busy = true; status = null
        com.aeriotv.android.core.profiles.ProfileSwitchFocus.arm(com.aeriotv.android.core.profiles.ProfileSwitchFocus.Target.Guide)
        vm.switchTo(playlistId, p.id) { err -> busy = false; status = err }
    }
    val rowFocus = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    // After a removal, focus lands on the next row (or the previous one when
    // the last row went), never on a tab pill.
    suspend fun removeAndRefocus(p: DispatcharrProfile) {
        val i = profiles.indexOfFirst { it.id == p.id }
        val next = profiles.getOrNull(i + 1) ?: profiles.getOrNull(i - 1)
        val err = vm.remove(p.id).exceptionOrNull()?.message
        status = err
        if (err == null && next != null) rowFocus[next.id]?.let { restoreFocus(it, "next profile row after remove") }
    }
    // TV: when the scrim drops, focus returns to the row that started the switch.
    val released by com.aeriotv.android.core.profiles.ProfileSwitchFocus.released.collectAsStateWithLifecycle()
    LaunchedEffect(released) {
        val id = profiles.firstOrNull {
            com.aeriotv.android.core.profiles.ProfileSwitchFocus.consume(com.aeriotv.android.core.profiles.ProfileSwitchFocus.Target.Row(it.id))
        }?.id ?: return@LaunchedEffect
        restoreFocus(rowFocus[id] ?: return@LaunchedEffect, "profile row after switch")
    }

    Column {
        SettingsSectionHeader("Profiles")
        SettingsCard {
            profiles.forEach { p ->
                ProfileRow(
                    profile = p,
                    locked = vm.hasPin(p.id),
                    modifier = Modifier.focusRequester(rowFocus.getOrPut(p.id) { androidx.compose.ui.focus.FocusRequester() }),
                    isActive = p.id == activeId,
                    onClick = {
                        if (busy) return@ProfileRow
                        // Tap a profile to switch to it; tap the active one
                        // for Rename / Remove.
                        // Long press (TV too) opens the actions for any row.
                        if (p.id == activeId) actionsFor = p
                        else if (vm.hasPin(p.id)) pinFor = p
                        else if (vm.isPlaying()) confirmSwitch = p
                        else doSwitch(p)
                    },
                    onLongClick = { if (!busy) actionsFor = p },
                    trailing = if (isTv) null else {
                        {
                            Spacer(Modifier.size(4.dp))
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Profile options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { actionsFor = p }
                                    .padding(6.dp)
                                    .size(18.dp),
                            )
                        }
                    },
                )
            }
            if (isAdmin) {
                var focused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .settingsRowCard(focused)
                        .clickable { adding = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text("Add Profile", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            }
            // Set up a TV: phone and tablet only, Admin or default profile.
            // With one playlist Settings > Playlists opens this page directly.
            if (showSetUpTvRow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsRowCard(false)
                        .clickable { showSetUpTv = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Set up a TV", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "Send this playlist, your profiles, and sync to a TV on your network.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isAdmin) DeviceAdminPinRow(playlistId, vm)
            if (busy) {
                Row(Modifier.padding(16.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            }
            status?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
        SettingsSectionFooter(
            "Each profile signs in as its own Dispatcharr user. Favorites, recordings, and watch history are kept per profile on this device.",
        )
    }

    if (showSetUpTv) {
        com.aeriotv.android.feature.devicesetup.SetUpTvDialog(playlistId = playlistId, onDismiss = { showSetUpTv = false })
    }

    confirmSwitch?.let { p ->
        StopPlaybackDialog(name = p.displayName, onDismiss = { confirmSwitch = null }) {
            confirmSwitch = null; doSwitch(p)
        }
    }
    actionsFor?.let { p ->
        var canPin by remember(p.id) { mutableStateOf(false) }
        LaunchedEffect(p.id, activeId) { canPin = vm.canManagePin(playlistId, p.id) }
        val hasPin = vm.hasPin(p.id)
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(p.displayName) },
            text = {
                // Up to ten rows for an Admin: scroll so none clips on a 1080p TV
                // (a focused row brings itself into view as D-pad focus moves).
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(
                        listOfNotNull(p.effectiveRole().label, if (hasPin) "PIN" else null).joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(p.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    ChoiceRow(label = "Rename", onClick = { actionsFor = null; renaming = p })
                    if (vm.isRoleAdmin(playlistId)) {
                        // Dispatcharr admins are always Admin: no Role picker.
                        if (!com.aeriotv.android.core.profiles.ProfileRole.isServerAdmin(p.userLevel)) {
                            ChoiceRow(label = "Role: ${p.effectiveRole().label}", onClick = { actionsFor = null; roleFor = p })
                        }
                        val at = profiles.indexOfFirst { it.id == p.id }
                        fun moveRow(how: com.aeriotv.android.core.profiles.ProfileManager.Move) {
                            actionsFor = null
                            status = vm.move(playlistId, p.id, how).exceptionOrNull()?.message
                            scope.launch { rowFocus[p.id]?.let { restoreFocus(it, "profile row after move") } }
                        }
                        if (at > 0) ChoiceRow(label = "Move Up", onClick = { moveRow(com.aeriotv.android.core.profiles.ProfileManager.Move.Earlier) })
                        if (at in 0 until profiles.lastIndex) ChoiceRow(label = "Move Down", onClick = { moveRow(com.aeriotv.android.core.profiles.ProfileManager.Move.Later) })
                    }
                    if (vm.canEditAvatar(playlistId, p.id)) {
                        ChoiceRow(label = "Avatar", onClick = { actionsFor = null; avatarFor = p })
                    }
                    if (canPin) {
                        ChoiceRow(label = if (hasPin) "Change PIN" else "Set PIN", onClick = { actionsFor = null; settingPin = p })
                        if (hasPin) ChoiceRow(label = "Remove PIN", onClick = { actionsFor = null; removingPin = p })
                        // An Admin resets another profile's forgotten PIN.
                        if (hasPin && vm.isRoleAdmin(playlistId) && p.id != activeId) {
                            ChoiceRow(label = "Reset PIN", onClick = { actionsFor = null; resettingPin = p })
                        }
                    }
                    if (canChannelProfiles) {
                        ChoiceRow(label = "Channel Profiles", onClick = { actionsFor = null; channelProfilesFor = p })
                    }
                    if (isAdmin && !p.isDefault) {
                        ChoiceRow(label = "Remove", destructive = true, onClick = { actionsFor = null; removing = p })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { actionsFor = null }) },
        )
    }
    pinFor?.let { p ->
        SwitchPinDialog(p, vm, onDismiss = { pinFor = null }) {
            pinFor = null
            if (vm.isPlaying()) confirmSwitch = p else doSwitch(p)
        }
    }
    settingPin?.let { p -> SetPinFlow(playlistId, p, vm) { err -> settingPin = null; status = err } }
    removingPin?.let { p -> RemovePinFlow(playlistId, p, vm) { err -> removingPin = null; status = err } }
    roleFor?.let { p -> RoleFlow(playlistId, p, vm) { err -> roleFor = null; status = err } }
    resettingPin?.let { p -> ResetPinFlow(playlistId, p, vm) { err -> resettingPin = null; status = err } }
    channelProfilesFor?.let { p -> ChannelProfilesDialog(playlistId, p, vm) { err -> channelProfilesFor = null; status = err } }
    avatarFor?.let { p ->
        AvatarPickerDialog(profile = p, onDismiss = { avatarFor = null; rowFocus[p.id]?.let { fr -> scope.launch { restoreFocus(fr, "profile row after avatar") } } }) { spec ->
            status = vm.setAvatar(playlistId, p.id, spec).exceptionOrNull()?.message
            avatarFor = null
            rowFocus[p.id]?.let { fr -> scope.launch { restoreFocus(fr, "profile row after avatar") } }
        }
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.displayName) }
        val saveFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = {
                // TV: Down (or Done) leaves the field for the buttons.
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        runCatching { saveFocus.requestFocus() }
                    }),
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        val k = e.key
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                            k == androidx.compose.ui.input.key.Key.DirectionDown
                        ) {
                            runCatching { saveFocus.requestFocus() }.isSuccess
                        } else false
                    },
                )
            },
            confirmButton = {
                Box(Modifier.focusRequester(saveFocus)) {
                    SettingsDialogTextButton(label = "Save", enabled = name.isNotBlank(), onClick = {
                        vm.rename(p.id, name); renaming = null
                    })
                }
            },
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { renaming = null }) },
        )
    }
    removing?.let { p ->
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove Profile") },
            text = {
                Text("Remove ${p.displayName}? Favorites, recordings, and watch history for this profile on this device will be deleted.")
            },
            confirmButton = {
                SettingsDialogTextButton(label = "Remove", destructive = true, onClick = {
                    removing = null
                    if (vm.roleOf(p.id) == com.aeriotv.android.core.profiles.ProfileRole.Child) {
                        removeAdminPinFor = p
                    } else {
                        scope.launch { removeAndRefocus(p) }
                    }
                })
            },
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { removing = null }) },
        )
    }
    removeAdminPinFor?.let { p ->
        AdminPinDialog(playlistId, vm, title = "Enter a PIN from an Admin or Standard profile to remove ${p.displayName}", onDismiss = { removeAdminPinFor = null }) {
            removeAdminPinFor = null
            scope.launch { removeAndRefocus(p) }
        }
    }
    if (adding) AddProfileDialog(playlistId, vm) { adding = false }
}

/**
 * Request [fr] after the scrim dialog has gone and the main window has its
 * focus back; retried because the window regains focus a few frames later.
 */
internal suspend fun restoreFocus(fr: androidx.compose.ui.focus.FocusRequester, what: String) {
    kotlinx.coroutines.delay(150L)
    repeat(20) { i ->
        if (runCatching { fr.requestFocus() }.isSuccess) {
            android.util.Log.i("AerioProfiles", "[PROFILE] focus restored to $what (try ${i + 1})")
            return
        }
        kotlinx.coroutines.delay(50L)
    }
}

/** "Enter a PIN from an Admin or Standard profile": any full-access profile's PIN passes. */
@Composable
fun AdminPinDialog(playlistId: String, vm: ProfilesViewModel, title: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    PinEntryDialog(
        title = title,
        type = vm.adminPinType(playlistId) ?: com.aeriotv.android.core.profiles.PinType.Numbers,
        pinLength = vm.adminPinLength(playlistId),
        onDismiss = onDismiss,
        onEntered = { seq ->
            val r = vm.verifyAnyAdminPin(playlistId, seq)
            if (r == ProfileManager.PinCheck.Ok) { onOk(); null } else pinCheckMessage(r)
        },
        lockSeconds = { vm.adminPinLockSeconds(playlistId) },
    )
}

/**
 * Row menu "Role" (Admin or Standard acting, target not a Dispatcharr
 * admin): Standard and Child. Setting Child
 * walks the acting profile through every missing Admin or Standard PIN first
 * (cancel = no change); leaving Child asks for a PIN from an Admin or
 * Standard profile.
 */
@Composable
private fun RoleFlow(playlistId: String, p: DispatcharrProfile, vm: ProfilesViewModel, onClose: (String?) -> Unit) {
    var picked by remember(p.id) { mutableStateOf<com.aeriotv.android.core.profiles.ProfileRole?>(null) }
    var needCreate by remember(p.id) { mutableStateOf(false) }
    var needEntry by remember(p.id) { mutableStateOf(false) }
    val current = vm.roleOf(p.id)
    val currentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(p.id, picked) { if (picked == null) focusFirst(currentFocus) }
    fun apply(to: com.aeriotv.android.core.profiles.ProfileRole) {
        onClose(vm.setRole(playlistId, p.id, to).exceptionOrNull()?.message)
    }
    val to = picked
    when {
        to == null -> com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text("Role", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
            text = {
                Column {
                    Text(p.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    com.aeriotv.android.core.profiles.ProfileRole.optionsFor(p.userLevel).forEach { r ->
                        ChoiceRow(label = r.label, checked = r == current, radio = true, modifier = if (r == current) Modifier.focusRequester(currentFocus) else Modifier, onClick = {
                            when (val g = vm.roleChangeGate(playlistId, p.id, r)) {
                                is com.aeriotv.android.core.profiles.RoleRules.RoleChange.Denied -> onClose(g.reason)
                                com.aeriotv.android.core.profiles.RoleRules.RoleChange.Allowed -> apply(r)
                                com.aeriotv.android.core.profiles.RoleRules.RoleChange.NeedsPinsCreated -> { picked = r; needCreate = true }
                                com.aeriotv.android.core.profiles.RoleRules.RoleChange.NeedsPinEntry -> { picked = r; needEntry = true }
                            }
                        })
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "A Child profile sees only its own content and basic settings. Switching from a Child profile to a non-Child profile requires PIN entry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { onClose(null) }) },
        )
        needCreate -> ChildNeedsAdminPin(playlistId, vm, becomingChild = p.id) { ok ->
            // Cancelling any PIN cancels the role change.
            if (ok) apply(to) else onClose(null)
        }
        needEntry -> AdminPinDialog(playlistId, vm, title = "Enter a PIN from an Admin or Standard profile", onDismiss = { onClose(null) }) { apply(to) }
    }
}

/**
 * "Reset PIN" for another profile (Admin or Standard actor): the actor's own
 * PIN, or a confirm when it has none. While a Child exists an Admin or
 * Standard target keeps a PIN (assumption A), so the actor sets a temporary
 * one instead of clearing it.
 */
@Composable
private fun ResetPinFlow(playlistId: String, p: DispatcharrProfile, vm: ProfilesViewModel, onClose: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val mustKeep = remember(p.id) { vm.pinRemovalBlock(playlistId, p.id) != null }
    var ownEntries by remember(p.id) { mutableStateOf<List<String>?>(null) }
    ownEntries?.let { own ->
        SetPinFlow(playlistId, p, vm, onChosen = { t, seq ->
            scope.launch { onClose(vm.resetPin(playlistId, p.id, own, t to seq).exceptionOrNull()?.message) }
        }) { err -> onClose(err) }
        return
    }
    when (val g = vm.resetGate(playlistId)) {
        is com.aeriotv.android.core.profiles.RoleRules.ResetGate.Denied -> LaunchedEffect(p.id) { onClose(g.reason) }
        com.aeriotv.android.core.profiles.RoleRules.ResetGate.ConfirmOnly -> com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text("Reset PIN") },
            text = { Text("Remove the PIN for ${p.displayName}? ${p.displayName} can set a new one.") },
            confirmButton = {
                SettingsDialogTextButton(label = "Reset", destructive = true, onClick = {
                    scope.launch { onClose(vm.resetPin(playlistId, p.id, null).exceptionOrNull()?.message) }
                })
            },
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { onClose(null) }) },
        )
        com.aeriotv.android.core.profiles.RoleRules.ResetGate.EnterOwnPin -> {
            val admin = vm.activeProfile(playlistId) ?: return
            PinEntryDialog(
                title = "Enter your PIN to reset the PIN for ${p.displayName}",
                type = vm.pinType(admin.id) ?: com.aeriotv.android.core.profiles.PinType.Numbers,
                pinLength = vm.pinLength(admin.id),
                onDismiss = { onClose(null) },
                onEntered = { seq ->
                    val r = vm.verifyPin(admin.id, seq)
                    if (r != ProfileManager.PinCheck.Ok) {
                        pinCheckMessage(r)
                    } else {
                        if (mustKeep) ownEntries = seq
                        else scope.launch { onClose(vm.resetPin(playlistId, p.id, seq).exceptionOrNull()?.message) }
                        null
                    }
                },
                lockSeconds = { vm.pinLockSeconds(admin.id) },
            )
        }
    }
}

@Composable
internal fun AddProfileDialog(playlistId: String, vm: ProfilesViewModel, onClose: () -> Unit) {
    var users by remember { mutableStateOf<List<DispatcharrUserSummary>?>(null) }
    var picked by remember { mutableStateOf<DispatcharrUserSummary?>(null) }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstUser = remember { androidx.compose.ui.focus.FocusRequester() }
    var manual by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf(com.aeriotv.android.core.profiles.ProfileRole.User) }
    // Assumption A: an Admin or Standard add while a Child exists sets its PIN right after.
    var pinAfterAdd by remember { mutableStateOf(false) }
    fun added(p: DispatcharrProfile) { if (vm.needsPinAfterAdd(playlistId, p.id)) pinAfterAdd = true else onClose() }
    LaunchedEffect(playlistId) {
        vm.notePickerOpened(playlistId, "addProfile")
        vm.addCandidates(playlistId).fold(
            { c ->
                when (c) {
                    com.aeriotv.android.core.profiles.ProfileManager.AddCandidates.Manual -> manual = true
                    is com.aeriotv.android.core.profiles.ProfileManager.AddCandidates.ServerList -> users = c.users
                }
            },
            { error = it.message ?: "Could not load users" },
        )
    }
    if (pinAfterAdd) {
        PinAfterAdd(playlistId, vm) { onClose() }
        return
    }
    if (manual) {
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = onClose,
            title = { Text("Add Profile") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RosterSuggestions(playlistId, vm, users = null, onAdded = onClose)
                    RolePicker(playlistId, vm, role) { role = it }
                    com.aeriotv.android.feature.onboarding.components.ManualProfileForm(
                        onAdd = { u, pw, key ->
                            vm.addManual(playlistId, u, pw, key, role).fold({ added(it); null }, { it.message ?: "Could not add profile" })
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = onClose) },
        )
        return
    }
    // Roster users ("From your other devices") are listed above; the server
    // list below keeps the rest.
    val suggestFlow = remember(playlistId) { vm.rosterSuggestions(playlistId) }
    val suggested by suggestFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Hide users that already have a profile on this device.
    val localFlow = remember(playlistId) { vm.profiles(playlistId) }
    val local by localFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val rest = users?.filter { u ->
        suggested.none { it.username.equals(u.username, ignoreCase = true) } &&
            local.none { it.username.equals(u.username, ignoreCase = true) }
    }
    var serverOpen by remember { mutableStateOf(false) }
    val serverExpanded = serverOpen || suggested.isEmpty()
    var query by remember { mutableStateOf("") }
    val listed = rest?.let { r -> query.trim().let { q -> if (q.isEmpty()) r else r.filter { it.username.contains(q, ignoreCase = true) } } }
    // Role per row (owner review 2026-10-27), default Standard; Dispatcharr admins locked to Admin.
    val rowRoles = remember { androidx.compose.runtime.mutableStateMapOf<Int, com.aeriotv.android.core.profiles.ProfileRole>() }
    fun roleOf(u: DispatcharrUserSummary) = if (u.isServerAdmin()) com.aeriotv.android.core.profiles.ProfileRole.Admin else rowRoles[u.id] ?: com.aeriotv.android.core.profiles.ProfileRole.User
    var childGateFor by remember { mutableStateOf<DispatcharrUserSummary?>(null) }
    // Enable API access (server admin only): keys minted here, by user id.
    var canManageKeys by remember { mutableStateOf(false) }
    LaunchedEffect(playlistId) { canManageKeys = vm.canManageUserApiKeys(playlistId) }
    val minted = remember { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
    fun setUserKey(id: Int, key: String?) { users = users?.map { if (it.id == id) it.copy(apiKey = key) else it } }
    fun addUser(user: DispatcharrUserSummary) {
        if (busy) return
        if (roleOf(user) == com.aeriotv.android.core.profiles.ProfileRole.Child && !vm.childGateClear(playlistId)) { childGateFor = user; return }
        if (!user.apiKey.isNullOrBlank()) {
            // Server gave us this user's key: add now, no prompt.
            busy = true; error = null
            scope.launch {
                vm.add(playlistId, user, null, null, roleOf(user)).fold(
                    { added(it) },
                    { busy = false; picked = user; error = it.message ?: "Could not add profile" },
                )
            }
        } else {
            picked = user
        }
    }
    LaunchedEffect(users?.isNotEmpty() == true, serverExpanded) { if (users?.isNotEmpty() == true && serverExpanded && suggested.isEmpty()) focusFirst(firstUser) }
    com.aeriotv.android.ui.settings.SettingsAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val u = picked
                if (u == null) RosterSuggestions(playlistId, vm, users, enabled = !busy, onAdded = onClose)
                if (u == null && suggested.isNotEmpty() && rest?.isNotEmpty() == true) {
                    ExpandRow("Show other users on this server", serverOpen, enabled = !busy) {
                        serverOpen = !serverOpen; if (!serverOpen) query = ""
                    }
                }
                if (u == null && serverExpanded && rest?.isNotEmpty() == true) {
                    ExpandableSearch(query, { query = it }, enabled = !busy)
                }
                if (u == null) {
                    when {
                        users == null && error == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        rest?.isEmpty() == true -> if (suggested.isEmpty()) Text("Every Dispatcharr user on this server already has a profile.")
                        !serverExpanded -> {}
                        busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        listed?.isEmpty() == true -> Text("No users match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listed != null -> LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(listed, key = { it.id }) { user ->
                                var rowFocused by remember { mutableStateOf(false) }
                                Column {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    Modifier
                                        .weight(1f)
                                        .then(if (user.id == listed.firstOrNull()?.id) Modifier.focusRequester(firstUser) else Modifier)
                                        .onFocusChanged { rowFocused = it.isFocused }
                                        // Same focus ring as the Profiles rows.
                                        .settingsRowCard(rowFocused)
                                        .clickable { addUser(user) }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ProfileAvatar(user.username, 28)
                                    Spacer(Modifier.size(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(user.username, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        ServerUserCaption(user)
                                    }
                                }
                                RoleChip(roleOf(user), enabled = !busy, locked = user.isServerAdmin()) { rowRoles[user.id] = it }
                                }
                                if (canManageKeys && (user.apiKey.isNullOrBlank() || minted[user.id] == true)) {
                                    EnableApiAccessRow(
                                        playlistId, vm, user,
                                        enabled = minted[user.id] == true,
                                        interactive = !busy,
                                        onEnabled = { key ->
                                            minted[user.id] = true
                                            // Row now carries the key: Add takes the keyed path, no prompt.
                                            setUserKey(user.id, key)
                                        },
                                        onRevoked = { minted.remove(user.id); setUserKey(user.id, null) },
                                    )
                                }
                                }
                            }
                        }
                    }
                } else {
                    Text("Sign in as ${u.username} once. Enter the password or an API key.")
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
                        label = { Text("API Key") },
                    )
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val u = picked
            if (u != null) {
                SettingsDialogTextButton(
                    label = "Add Profile",
                    enabled = !busy && (password.isNotBlank() || apiKey.isNotBlank()),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            vm.add(playlistId, u, password.takeIf { it.isNotBlank() }, apiKey.trim().takeIf { it.isNotBlank() }, roleOf(u))
                                .fold({ added(it) }, { busy = false; error = it.message ?: "Could not add profile" })
                        }
                    },
                )
            }
        },
        dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = onClose) },
    )
    childGateFor?.let { g ->
        ChildNeedsAdminPin(playlistId, vm) { ok -> childGateFor = null; if (ok) addUser(g) }
    }
}

/** One line under each role in Add Profile. */
internal fun roleDescription(r: com.aeriotv.android.core.profiles.ProfileRole): String = when (r) {
    com.aeriotv.android.core.profiles.ProfileRole.Admin -> "Full access in AerioTV. Has admin rights on the Dispatcharr server."
    com.aeriotv.android.core.profiles.ProfileRole.User -> "Full access in AerioTV. A standard user on the Dispatcharr server."
    com.aeriotv.android.core.profiles.ProfileRole.Child -> "Sees only its own content and basic settings. Switching from a Child profile to a non-Child profile requires PIN entry."
}

/**
 * Add Profile "Role" choice for a typed username (level not known yet):
 * Standard or Child, default Standard. A Dispatcharr admin is stored as Admin. Picking Child
 * with no Admin PIN asks the acting Admin to set one first, same copy as the
 * row menu Role; cancelling keeps the previous choice.
 */
@Composable
internal fun RolePicker(playlistId: String, vm: ProfilesViewModel, selected: com.aeriotv.android.core.profiles.ProfileRole, vertical: Boolean = false, onSelect: (com.aeriotv.android.core.profiles.ProfileRole) -> Unit) {
    var gate by remember { mutableStateOf(false) }
    // Compact by design: one row of radio choices (Standard, Child) plus a single
    // helper line for the selected role, so the user list above it keeps
    // the height (owner 2026-10-27: three stacked rows squeezed the list).
    Column {
        Text("Role", style = MaterialTheme.typography.titleSmall)
        // TV onboarding card stacks the choices; elsewhere one row.
        val choice: @Composable (com.aeriotv.android.core.profiles.ProfileRole, Modifier) -> Unit = { r, m ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    m
                        .onFocusChanged { focused = it.isFocused }
                        .settingsRowCard(focused)
                        .selectable(selected = r == selected, role = androidx.compose.ui.semantics.Role.RadioButton) {
                            if (r == com.aeriotv.android.core.profiles.ProfileRole.Child && !vm.childGateClear(playlistId)) gate = true
                            else onSelect(r)
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = r == selected, onClick = null,
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(r.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
        }
        if (vertical) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                com.aeriotv.android.core.profiles.ProfileRole.optionsFor(serverAdmin = null).forEach { r -> choice(r, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.aeriotv.android.core.profiles.ProfileRole.optionsFor(serverAdmin = null).forEach { r -> choice(r, Modifier.weight(1f)) }
            }
        }
        Text(
            roleDescription(selected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
    if (gate) {
        ChildNeedsAdminPin(playlistId, vm) { ok ->
            gate = false
            if (ok) onSelect(com.aeriotv.android.core.profiles.ProfileRole.Child)
        }
    }
}
