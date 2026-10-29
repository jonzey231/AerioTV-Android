package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.core.profiles.PinType
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.profiles.ProfileRole
import com.aeriotv.android.core.sync.dsync.ProfileRow
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsRowCard
import com.aeriotv.android.ui.settings.dpadFocusRing
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch

/**
 * "From your other devices": synced roster users with no profile on this
 * device. Nothing is added by a pull; each row is added only when chosen
 * (TV: focus the row, Center adds; phone: the trailing Add). Adding uses the
 * admin-listed api key from [users] when there is one, else asks for a
 * password or API key, then applies the row's role, PIN, avatar, name, and
 * order. A Child row needs an Admin PIN on this device first.
 * Renders nothing when there are no suggestions.
 */
@Composable
internal fun RosterSuggestions(
    playlistId: String,
    vm: ProfilesViewModel,
    users: List<DispatcharrUserSummary>?,
    enabled: Boolean = true,
    onAdded: () -> Unit = {},
    /** Section header; onboarding passes its uppercase label. */
    title: String = "From your other devices",
    /** Onboarding: explains what adding copies, under the rows. */
    footnote: String? = null,
    /**
     * Settings dialog: true, an added row stays listed as "Added" with Remove.
     * Onboarding: false, an added row leaves this list (ON THIS PHONE above
     * lists it, with Remove there).
     */
    keepAdded: Boolean = true,
    /** Called with the new profile id after a successful add. */
    onAddedProfile: (String) -> Unit = {},
    /** Onboarding focus: the username whose row should take focus when listed. */
    focusUsername: String? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val flow = remember(playlistId) { vm.rosterSuggestions(playlistId) }
    val rows by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val isTv = rememberIsTvDevice()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var credFor by remember { mutableStateOf<ProfileRow?>(null) }
    var childGate by remember { mutableStateOf<ProfileRow?>(null) }
    // Rows added here leave the roster flow; keep them listed as "Added"
    // (not focusable) so the list does not jump under the D-pad.
    val added = remember { androidx.compose.runtime.mutableStateListOf<ProfileRow>() }
    // Username (lowercase) -> profile id from addFromRoster, for the undo.
    val addedIds = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    fun undo(row: ProfileRow) {
        val id = addedIds[row.username.lowercase()] ?: return
        if (busy != null) return
        busy = row.username
        error = null
        scope.launch {
            vm.undoAdd(id).fold(
                {
                    busy = null
                    addedIds.remove(row.username.lowercase())
                    added.removeAll { it.username.equals(row.username, ignoreCase = true) }
                },
                { busy = null; error = it.message ?: "Could not remove profile" },
            )
        }
    }
    // Per-row role before Add, prefilled from the roster role.
    val roles = remember { androidx.compose.runtime.mutableStateMapOf<String, ProfileRole>() }
    fun rosterRoleFor(r: ProfileRow) = roles[r.username.lowercase()] ?: ProfileRole.fromWire(r.role) ?: ProfileRole.User
    // Dispatcharr admins are always Admin (owner 2026-10-29): locked chip.
    fun rosterAdmin(r: ProfileRow) = users?.firstOrNull { it.username.equals(r.username, ignoreCase = true) }?.isServerAdmin() == true
    fun roleFor(r: ProfileRow) = if (rosterAdmin(r)) ProfileRole.Admin else rosterRoleFor(r)

    if (rows.isEmpty() && added.isEmpty()) return

    fun add(row: ProfileRow) {
        if (busy != null || !enabled) return
        val r = row.copy(role = roleFor(row).wire)
        error = null
        if (r.role == ProfileRole.Child.wire && !vm.adminHasPin(playlistId)) { childGate = r; return }
        val listed = users?.firstOrNull { it.username.equals(r.username, ignoreCase = true) }
        if (listed?.apiKey.isNullOrBlank()) { credFor = r; return }
        busy = r.username
        scope.launch {
            vm.addFromRoster(playlistId, r, listed!!, null, null).fold(
                { busy = null; addedIds[r.username.lowercase()] = it.id; if (keepAdded) added += r; onAddedProfile(it.id); onAdded() },
                { busy = null; error = it.message ?: "Could not add profile" },
            )
        }
    }

    val listedRows = rows + added.filter { a -> rows.none { it.username.equals(a.username, ignoreCase = true) } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        listedRows.forEach { r ->
            var focused by remember { mutableStateOf(false) }
            val rowFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            if (focusUsername != null && focusUsername.equals(r.username, ignoreCase = true)) {
                androidx.compose.runtime.LaunchedEffect(focusUsername) {
                    kotlinx.coroutines.delay(50)
                    runCatching { rowFocus.requestFocus() }
                    onFocusConsumed()
                }
            }
            val isAdded = added.any { it.username.equals(r.username, ignoreCase = true) }
            val canAdd = enabled && busy == null && !isAdded
            // TV: the row (Center adds) and the role chip are two focus
            // stops, the chip to the right of the row.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .then(if (isTv) Modifier.focusRequester(rowFocus) else Modifier)
                    .heightIn(min = 56.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .settingsRowCard(focused)
                    .then(if (isTv && !isAdded) Modifier.clickable(enabled = canAdd) { add(r) } else Modifier)
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileAvatar(name = r.displayName.ifBlank { r.username }, size = 28, username = r.username, spec = r.avatar)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.displayName.ifBlank { r.username }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        r.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Roster role (Admin, User, or Child), not the server level.
                if (isAdded) RoleBadge(roleFor(r))
                else if (!isTv) RoleChip(roleFor(r), enabled = canAdd, locked = rosterAdmin(r)) { roles[r.username.lowercase()] = it }
                Spacer(Modifier.size(8.dp))
                when {
                    busy == r.username -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    isAdded -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("Added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (!isTv && addedIds.containsKey(r.username.lowercase())) {
                            TextButton(onClick = { undo(r) }, enabled = busy == null) { Text("Remove") }
                        }
                    }
                    isTv -> {
                        // One focus stop per row: the row takes focus and
                        // Center; the pill mirrors that focus with a ring.
                        val shape = RoundedCornerShape(50)
                        Text(
                            "Add",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .then(
                                    if (focused) Modifier.border(2.dp, androidx.compose.ui.graphics.Color.White, shape)
                                    else Modifier,
                                )
                                .clip(shape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    else -> TextButton(onClick = { add(r) }, enabled = canAdd) { Text("Add") }
                }
            }
            if (isTv && !isAdded) RoleChip(roleFor(r), enabled = canAdd, locked = rosterAdmin(r)) { roles[r.username.lowercase()] = it }
            if (isTv && isAdded && addedIds.containsKey(r.username.lowercase())) {
                // TV undo: its own focus stop, styled like the Add pill.
                var pillFocused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(50)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Remove",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .onFocusChanged { pillFocused = it.isFocused }
                        .then(if (pillFocused) Modifier.border(2.dp, androidx.compose.ui.graphics.Color.White, shape) else Modifier)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = busy == null) { undo(r) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            }
        }
        footnote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    credFor?.let { r ->
        RosterCredentialDialog(
            username = r.username,
            onCancel = { credFor = null },
            onSubmit = { pw, key ->
                val user = users?.firstOrNull { it.username.equals(r.username, ignoreCase = true) }
                    ?: DispatcharrUserSummary(id = r.userId, username = r.username)
                vm.addFromRoster(playlistId, r, user, pw, key).fold(
                    { credFor = null; addedIds[r.username.lowercase()] = it.id; if (keepAdded) added += r; onAddedProfile(it.id); onAdded(); null },
                    { it.message ?: "Could not add profile" },
                )
            },
        )
    }
    childGate?.let { r ->
        ChildNeedsAdminPin(playlistId, vm, onDone = { ok -> childGate = null; if (ok) add(r) })
    }
}

@Composable
internal fun RoleBadge(role: ProfileRole) {
    Text(
        text = role.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Password or API key for a roster user the admin list has no key for. */
@Composable
private fun RosterCredentialDialog(username: String, onCancel: () -> Unit, onSubmit: suspend (String?, String?) -> String?) {
    var password by remember(username) { mutableStateOf("") }
    var apiKey by remember(username) { mutableStateOf("") }
    var busy by remember(username) { mutableStateOf(false) }
    var error by remember(username) { mutableStateOf<String?>(null) }
    var scannedUser by remember(username) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    com.aeriotv.android.ui.settings.SettingsAlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add $username") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sign in as $username once. Enter the password or an API key.")
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, singleLine = true, label = { Text("API Key") })
                // Dispatcharr user QR (phones and tablets): fill the key and add.
                val context = androidx.compose.ui.platform.LocalContext.current
                com.aeriotv.android.feature.qrscan.QrScanActions(
                    enabled = !busy,
                    onResult = { r ->
                        when (r) {
                            is com.aeriotv.android.core.qr.ScannedCode.Result.UserKey -> {
                                apiKey = r.apiKey; password = ""; scannedUser = r.username
                                if (!busy) {
                                    busy = true; error = null
                                    scope.launch { error = onSubmit(null, r.apiKey); busy = false }
                                }
                            }
                            is com.aeriotv.android.core.qr.ScannedCode.Result.SetupLink ->
                                com.aeriotv.android.feature.qrscan.openSetupLink(context, r.link)
                            com.aeriotv.android.core.qr.ScannedCode.Result.Invalid -> Unit
                        }
                    },
                )
                scannedUser?.let { Text("Signed in as $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            SettingsDialogTextButton(
                label = "Add Profile",
                enabled = !busy && (password.isNotBlank() || apiKey.isNotBlank()),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        error = onSubmit(password.takeIf { it.isNotBlank() }, apiKey.trim().takeIf { it.isNotBlank() })
                        busy = false
                    }
                },
            )
        },
        dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = onCancel) },
    )
}

/** Same rule as the Role picker: a Child needs an Admin PIN on this device first. */
@Composable
internal fun ChildNeedsAdminPin(playlistId: String, vm: ProfilesViewModel, onDone: (Boolean) -> Unit) {
    var explained by remember { mutableStateOf(false) }
    val admin = vm.activeProfile(playlistId)
    if (admin == null || admin.let { vm.roleOf(it.id) } != ProfileRole.Admin) {
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { onDone(false) },
            title = { Text("Admin PIN Required") },
            text = { Text("A Child profile needs an Admin PIN to leave it. Set an Admin PIN first.") },
            confirmButton = { SettingsDialogTextButton(label = "OK", onClick = { onDone(false) }) },
        )
        return
    }
    if (!explained) {
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { onDone(false) },
            title = { Text("Admin PIN Required") },
            text = { Text("A Child profile needs an Admin PIN to leave it. Set a PIN for ${admin.displayName} first.") },
            confirmButton = { SettingsDialogTextButton(label = "Set PIN", onClick = { explained = true }) },
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { onDone(false) }) },
        )
    } else {
        SetPinFlow(playlistId, admin, vm) { _ -> onDone(vm.adminHasPin(playlistId)) }
    }
}

/**
 * Set or change the device Admin PIN: (current device PIN when one
 * exists), PIN type, Enter PIN, Confirm PIN. Device-local, never synced.
 */
@Composable
internal fun DeviceAdminPinSetFlow(playlistId: String, vm: ProfilesViewModel, onboarding: Boolean = false, onClose: (String?) -> Unit) {
    val id = vm.deviceAdminPinId(playlistId)
    val had = remember { vm.hasDeviceAdminPin(playlistId) }
    var current by remember { mutableStateOf<List<String>?>(null) }
    var type by remember { mutableStateOf<PinType?>(null) }
    var first by remember { mutableStateOf<List<String>?>(null) }
    var mismatch by remember { mutableStateOf<String?>(null) }
    val title = if (had) "Change Device Admin PIN" else "Set Device Admin PIN"
    when {
        had && current == null -> PinEntryDialog(
            title = "Enter Device Admin PIN",
            type = vm.pinType(id) ?: PinType.Numbers,
            pinLength = vm.pinLength(id),
            onDismiss = { onClose(null) },
            onEntered = { seq ->
                val r = vm.verifyPin(id, seq)
                if (r == ProfileManager.PinCheck.Ok) { current = seq; null } else pinCheckMessage(r)
            },
            lockSeconds = { vm.pinLockSeconds(id) },
        )
        type == null -> com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text("PIN type") },
            text = {
                Column {
                    PinType.entries.forEach { t -> ChoiceRow(label = t.label, checked = type == t, radio = true, onClick = { type = t }) }
                }
            },
            confirmButton = {},
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { onClose(null) }) },
        )
        first == null -> PinEntryDialog(
            title = "$title: Enter PIN",
            type = type!!,
            onDismiss = { onClose(null) },
            initialError = mismatch,
            focusClear = true,
            setting = true,
            onEntered = { seq -> first = seq; mismatch = null; null },
        )
        else -> PinEntryDialog(
            title = "Confirm PIN",
            type = type!!,
            pinLength = first!!.size,
            focusClear = true,
            onDismiss = { onClose(null) },
            onEntered = { seq ->
                if (seq != first) {
                    mismatch = "PINs do not match"; first = null; null
                } else {
                    // The current PIN was verified above; the manager checks it again.
                    onClose(vm.setDeviceAdminPin(playlistId, type!!, seq, current, onboarding).exceptionOrNull()?.message)
                    null
                }
            },
        )
    }
}

/** Remove the device Admin PIN: the current device PIN first. */
@Composable
internal fun DeviceAdminPinRemoveFlow(playlistId: String, vm: ProfilesViewModel, onClose: (String?) -> Unit) {
    val id = vm.deviceAdminPinId(playlistId)
    vm.deviceAdminPinRemovalBlock(playlistId)?.let { reason ->
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text("Remove Device Admin PIN") },
            text = { Text(reason) },
            confirmButton = { SettingsDialogTextButton(label = "OK", onClick = { onClose(null) }) },
        )
        return
    }
    PinEntryDialog(
        title = "Remove Device Admin PIN",
        type = vm.pinType(id) ?: PinType.Numbers,
        pinLength = vm.pinLength(id),
        onDismiss = { onClose(null) },
        onEntered = { seq ->
            // removeDeviceAdminPin verifies the PIN (with the backoff).
            val r = vm.removeDeviceAdminPin(playlistId, seq)
            if (r.isSuccess) { onClose(null); null } else r.exceptionOrNull()?.message ?: "Wrong PIN"
        },
        lockSeconds = { vm.pinLockSeconds(id) },
    )
}

/**
 * Settings > Playlists > Profiles "Device Admin PIN": Set, Change, or
 * Remove. Change and Remove need the current device PIN.
 */
@Composable
internal fun DeviceAdminPinRow(playlistId: String, vm: ProfilesViewModel) {
    var tick by remember { mutableStateOf(0) }
    val has = remember(tick) { vm.hasDeviceAdminPin(playlistId) }
    var mode by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    Column {
        com.aeriotv.android.ui.settings.SettingsActionRow(
            label = if (has) "Change Device Admin PIN" else "Set Device Admin PIN",
            leadingIcon = androidx.compose.material.icons.Icons.Filled.Lock,
            subtitle = "Works on this device only, alongside Admin profile PINs. Never synced.",
            onClick = { status = null; mode = "set" },
        )
        if (has) {
            com.aeriotv.android.ui.settings.SettingsActionRow(
                label = "Remove Device Admin PIN",
                leadingIcon = androidx.compose.material.icons.Icons.Filled.Lock,
                destructive = true,
                onClick = { status = null; mode = "remove" },
            )
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    when (mode) {
        "set" -> DeviceAdminPinSetFlow(playlistId, vm) { err -> mode = null; status = err; tick++ }
        "remove" -> DeviceAdminPinRemoveFlow(playlistId, vm) { err -> mode = null; status = err; tick++ }
    }
}

/**
 * Per-row role chip (owner review 2026-10-27: role is per row, not one
 * global choice). Tap or Center opens the themed radio sheet. Picking Child
 * here only records it; the Admin PIN prompt runs at Add time.
 */
@Composable
internal fun RoleChip(role: ProfileRole, enabled: Boolean = true, locked: Boolean = false, onChange: (ProfileRole) -> Unit) {
    // Dispatcharr admins: a plain Admin chip, no picker and no focus stop.
    if (locked) { RoleBadge(ProfileRole.Admin); return }
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .padding(start = 6.dp)
            .clip(shape)
            .then(Modifier.dpadFocusRing(shape))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.DropdownList) { open = true }
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(role.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.ArrowDropDown,
            contentDescription = "Change role",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
    }
    if (open) {
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Role", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
            text = {
                Column {
                    ProfileRole.entries.forEach { r ->
                        ChoiceRow(label = r.label, checked = r == role, radio = true, onClick = { open = false; onChange(r) })
                        Text(
                            roleDescription(r),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { open = false }) },
        )
    }
}

/**
 * Server row subtitle: the server level (Admin or Standard) as a caption,
 * not a pill, plus a key note when the list has no api key for the user.
 */
@Composable
internal fun ServerUserCaption(user: DispatcharrUserSummary) {
    val level = com.aeriotv.android.core.profiles.ProfileLevel.of(serverLevel(user))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(level.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (user.apiKey.isNullOrBlank()) {
            Spacer(Modifier.size(8.dp))
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "Needs a password or API key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Focusable button row that expands a section in place (TV: one focus stop). */
@Composable
internal fun ExpandRow(label: String, expanded: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .settingsRowCard(focused)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        androidx.compose.material3.Icon(
            if (expanded) androidx.compose.material.icons.Icons.Filled.ExpandLess else androidx.compose.material.icons.Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Collapsed "Search" row that expands into the field; collapsing clears the query. */
@Composable
internal fun ExpandableSearch(query: String, onQuery: (String) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(query.isNotEmpty()) }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    ExpandRow("Search", open, enabled) { open = !open; if (!open) onQuery("") }
    if (open) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Search users") },
            modifier = Modifier.fillMaxWidth().focusRequester(fr),
        )
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    }
}

/**
 * Server user row, admin only: "Enable API access" for a user with no key.
 * On mints a key (POST /api/accounts/api-keys/generate/) and hands it to
 * [onEnabled] so the caller adds the profile with it, no password prompt.
 * Off (only after an on here) confirms, then revokes the key. Errors show
 * inline and leave the switch where it was. The key is never logged.
 * [enabled] is the switch state the caller keeps (true after a mint here).
 */
@Composable
internal fun EnableApiAccessRow(
    playlistId: String,
    vm: ProfilesViewModel,
    user: DispatcharrUserSummary,
    enabled: Boolean,
    onEnabled: (String) -> Unit,
    onRevoked: () -> Unit,
    interactive: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(user.id) { mutableStateOf(false) }
    var error by remember(user.id) { mutableStateOf<String?>(null) }
    var confirmRevoke by remember(user.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    fun toggle(on: Boolean) {
        if (busy || !interactive) return
        error = null
        if (on) {
            busy = true
            scope.launch {
                vm.generateUserApiKey(playlistId, user.id).fold(
                    { key -> busy = false; onEnabled(key) },
                    { busy = false; error = it.message ?: "Could not enable API access" },
                )
            }
        } else {
            confirmRevoke = true
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .dpadFocusRing(shape)
                .toggleable(
                    value = enabled,
                    enabled = interactive && !busy,
                    role = androidx.compose.ui.semantics.Role.Switch,
                    onValueChange = { toggle(it) },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Enable API access", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Lets this user sign in on AerioTV with a key instead of a password",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else androidx.compose.material3.Switch(checked = enabled, onCheckedChange = null, enabled = interactive)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmRevoke) {
        com.aeriotv.android.ui.settings.SettingsAlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Revoke API access?") },
            text = { Text("${user.username} will be signed out on every device that uses this key.") },
            confirmButton = {
                SettingsDialogTextButton(label = "Revoke", destructive = true, onClick = {
                    confirmRevoke = false
                    busy = true
                    scope.launch {
                        vm.revokeUserApiKey(playlistId, user.id).fold(
                            { busy = false; onRevoked() },
                            { busy = false; error = it.message ?: "Could not revoke API access" },
                        )
                    }
                })
            },
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { confirmRevoke = false }) },
        )
    }
}

/** The Dispatcharr level the server row caption shows (superuser or staff counts as Admin). */
internal fun serverLevel(user: DispatcharrUserSummary): Int = if (user.isSuperuser || user.isStaff) 10 else user.userLevel ?: 0

/** Same flag as the row caption "Admin": such users are always AerioTV Admin. */
internal fun DispatcharrUserSummary.isServerAdmin(): Boolean =
    com.aeriotv.android.core.profiles.ProfileLevel.of(serverLevel(this)) == com.aeriotv.android.core.profiles.ProfileLevel.Admin

