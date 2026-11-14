package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Lock
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
import com.aeriotv.android.core.profiles.RoleRules
import com.aeriotv.android.core.sync.dsync.ProfileRow
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsRowCard
import com.aeriotv.android.ui.settings.dpadFocusRing
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.draw.alpha

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
    /**
     * The manual form follows these rows (Add Profile): a divider and "Or
     * sign in as another Dispatcharr user" close the block (Apple d711ba3).
     */
    formFollows: Boolean = false,
) {
    val flow = remember(playlistId) { vm.rosterSuggestions(playlistId) }
    val rows by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val isTv = rememberIsTvDevice()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var credFor by remember { mutableStateOf<ProfileRow?>(null) }
    var childGate by remember { mutableStateOf<ProfileRow?>(null) }
    // Assumption A: an Admin or Standard add while a Child exists sets its PIN right after.
    var pinAfter by remember { mutableStateOf<Pair<ProfileRow, String>?>(null) }
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
    fun rosterRoleFor(r: ProfileRow) = (roles[r.username.lowercase()] ?: ProfileRole.fromWire(r.role))?.takeIf { it != ProfileRole.Admin } ?: ProfileRole.User
    // Dispatcharr admins are always Admin (owner 2026-10-29): locked chip.
    fun rosterAdmin(r: ProfileRow) = users?.firstOrNull { it.username.equals(r.username, ignoreCase = true) }?.isServerAdmin() == true
    fun roleFor(r: ProfileRow) = if (rosterAdmin(r)) ProfileRole.Admin else rosterRoleFor(r)

    if (rows.isEmpty() && added.isEmpty()) return

    fun finishAdd(r: ProfileRow, id: String) {
        if (vm.needsPinAfterAdd(playlistId, id)) pinAfter = r to id else { onAddedProfile(id); onAdded() }
    }

    fun add(row: ProfileRow) {
        if (busy != null || !enabled) return
        val r = row.copy(role = roleFor(row).wire)
        error = null
        if (r.role == ProfileRole.Child.wire && !vm.childGateClear(playlistId)) { childGate = r; return }
        val listed = users?.firstOrNull { it.username.equals(r.username, ignoreCase = true) }
        if (listed?.apiKey.isNullOrBlank()) { credFor = r; return }
        busy = r.username
        scope.launch {
            vm.addFromRoster(playlistId, r, listed!!, null, null).fold(
                { busy = null; addedIds[r.username.lowercase()] = it.id; if (keepAdded) added += r; finishAdd(r, it.id) },
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
                    .heightIn(min = if (LocalTvCompactForm.current) ProfileDialogRules.TvForm.SUGGESTION_ROW_DP.dp else 56.dp)
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
        if (com.aeriotv.android.core.profiles.ProfileRoster.showsManualFormHeading(listedRows.size, formFollows)) {
            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(top = if (LocalTvCompactForm.current) ProfileDialogRules.TvForm.DIVIDER_TOP_DP.dp else 4.dp),
                thickness = 1.dp,
            )
            // Same style as the "From your other devices" title above.
            Text(com.aeriotv.android.core.profiles.ProfileRoster.MANUAL_FORM_HEADING, style = MaterialTheme.typography.titleSmall)
        }
    }

    credFor?.let { r ->
        RosterCredentialDialog(
            username = r.username,
            onCancel = { credFor = null },
            onSubmit = { pw, key ->
                val user = users?.firstOrNull { it.username.equals(r.username, ignoreCase = true) }
                    ?: DispatcharrUserSummary(id = r.userId, username = r.username)
                vm.addFromRoster(playlistId, r, user, pw, key).fold(
                    { credFor = null; addedIds[r.username.lowercase()] = it.id; if (keepAdded) added += r; finishAdd(r, it.id); null },
                    { it.message ?: "Could not add profile" },
                )
            },
        )
    }
    pinAfter?.let { (r, id) ->
        PinAfterAdd(playlistId, vm, listOf(id)) { ok ->
            pinAfter = null
            if (ok) { onAddedProfile(id); onAdded() } else {
                addedIds.remove(r.username.lowercase())
                added.removeAll { it.username.equals(r.username, ignoreCase = true) }
            }
        }
    }
    childGate?.let { r ->
        ChildNeedsAdminPin(playlistId, vm, onDone = { ok -> childGate = null; if (ok) add(r) })
    }
}

@Composable
internal fun RoleBadge(role: ProfileRole) {
    // Apple's ProfileRoleBadge: the accent label on an 18% accent capsule.
    Text(
        text = role.label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Password or API key for a roster user the admin list has no key for. */
@Composable
internal fun RosterCredentialDialog(
    username: String,
    onCancel: () -> Unit,
    onSubmit: suspend (String?, String?) -> String?,
    title: String = "Add $username",
    confirmLabel: String = "Add Profile",
) {
    var password by remember(username) { mutableStateOf("") }
    var apiKey by remember(username) { mutableStateOf("") }
    var busy by remember(username) { mutableStateOf(false) }
    var error by remember(username) { mutableStateOf<String?>(null) }
    var scannedUser by remember(username) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ProfileAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
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
                    // Inside Add Profile: never "Show User QR Code" (Apple f4cf2e9).
                    showUserQr = false,
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
                label = confirmLabel,
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

/**
 * Assumption A, add flow: an Admin or Standard profile added while a Child
 * exists sets its PIN right after the add (same sheets). Cancelling undoes
 * only [addedIds], the adds of the action that opened this step; any other
 * profile still waiting for its PIN stays as it was. onDone(true) = kept.
 */
@Composable
internal fun PinAfterAdd(playlistId: String, vm: ProfilesViewModel, addedIds: List<String>, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    ChildNeedsAdminPin(playlistId, vm) { ok ->
        if (ok) onDone(true) else scope.launch { vm.discardPendingAdds(playlistId, addedIds); onDone(false) }
    }
}

/**
 * Assumption A (owner rule 2026-10-29): while a Child exists every Admin and
 * Standard profile holds a PIN. Walks the acting profile through each
 * missing PIN, its own first, then one prompt per other profile (a temporary
 * PIN that profile can change later or have reset). [becomingChild] is the
 * profile about to turn Child (not asked). onDone(true) once none is missing;
 * cancelling any step is onDone(false).
 */
@Composable
internal fun ChildNeedsAdminPin(playlistId: String, vm: ProfilesViewModel, becomingChild: String? = null, onDone: (Boolean) -> Unit) {
    var round by remember { mutableStateOf(0) }
    var explained by remember { mutableStateOf<String?>(null) }
    val actor = vm.activeProfile(playlistId)
    val missing = remember(round) { vm.pinsMissingForChild(playlistId, becomingChild) }
    val next = missing.firstOrNull()
    if (next == null) {
        if (vm.adminHasPin(playlistId)) {
            androidx.compose.runtime.LaunchedEffect(round) { onDone(true) }
        } else {
            ProfileAlertDialog(
                // OK only: a tap outside does nothing (no Cancel to stand for).
                outsideTapCancels = false,
                onDismissRequest = { onDone(false) },
                title = { Text("PIN Required") },
                text = { Text(com.aeriotv.android.core.profiles.PinCopy.NO_PIN_LEAVES_CHILD) },
                confirmButton = { SettingsDialogTextButton(label = "OK", onClick = { onDone(false) }) },
            )
        }
        return
    }
    val own = next.id == actor?.id
    // Only an Admin or Standard profile sets another profile's PIN.
    if (!own && !vm.isRoleAdmin(playlistId) && !vm.isPendingPinAdd(next.id)) {
        ProfileAlertDialog(
            // OK only: a tap outside does nothing (no Cancel to stand for).
            outsideTapCancels = false,
            onDismissRequest = { onDone(false) },
            title = { Text("PIN Required") },
            text = { Text("${RoleRules.KEEP_PIN_REASON} Set a PIN for ${next.displayName} first.") },
            confirmButton = { SettingsDialogTextButton(label = "OK", onClick = { onDone(false) }) },
        )
        return
    }
    if (explained != next.id) {
        ProfileAlertDialog(
            onDismissRequest = { onDone(false) },
            title = { Text("PIN Required") },
            text = {
                Text(
                    if (own) "${RoleRules.KEEP_PIN_REASON} Set a PIN for ${next.displayName} first."
                    else "${RoleRules.KEEP_PIN_REASON} Set a temporary PIN for ${next.displayName}. It can be changed later, or reset from the profile's menu."
                )
            },
            confirmButton = { SettingsDialogTextButton(label = "Set PIN", onClick = { explained = next.id }) },
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { onDone(false) }) },
        )
    } else {
        SetPinFlow(playlistId, next, vm) { _ ->
            if (!vm.hasPin(next.id)) onDone(false) else round++
        }
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
        type == null -> ProfileAlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text(com.aeriotv.android.core.profiles.PinCopy.DEVICE_ADMIN_PIN_TYPE) },
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
            title = com.aeriotv.android.core.profiles.PinCopy.CONFIRM_DEVICE_ADMIN_PIN,
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
        ProfileAlertDialog(
            // OK only: a tap outside does nothing (no Cancel to stand for).
            outsideTapCancels = false,
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
            subtitle = com.aeriotv.android.core.profiles.PinCopy.DEVICE_ADMIN_PIN_ROW,
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
    // Apple's ProfileRoleChip: the unfilled ProfilePillStyle, an accent label
    // and chevron on a 14% accent capsule, 8 from the row.
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .padding(start = 8.dp)
            .clip(shape)
            .then(Modifier.dpadFocusRing(shape))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.DropdownList) { open = true }
            .background(accent.copy(alpha = 0.14f))
            .padding(start = 16.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            role.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
        Spacer(Modifier.size(2.dp))
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
            contentDescription = "Change role",
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
    }
    if (open) {
        ProfileAlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Role") },
            text = {
                Column {
                    // Not a Dispatcharr admin (or unknown): Standard and Child only.
                    ProfileRole.optionsFor(serverAdmin = false).forEach { r ->
                        ChoiceRow(label = r.label, checked = r == role, radio = true, subtitle = roleDescription(r), onClick = { open = false; onChange(r) })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { ProfileDialogCapsule(label = "Cancel", onClick = { open = false }, filled = false) },
        )
    }
}

/**
 * Server row subtitle: the server level (Admin or Standard) as a caption,
 * not a pill, plus a key note when the list has no api key for the user.
 * Apple's ServerUserCaption (12dbe4c): one line while it fits; otherwise the
 * note moves under the level and wraps, and the level never splits, so both
 * read in full at every Text Size. The key glyph rides the note's first
 * line, 6 dp before its text. Wrapped lines start at the key, not under
 * the text: in the Add Profile dialog on a phone at 150% that indent left
 * too little room and split "password" mid-word.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ServerUserCaption(user: DispatcharrUserSummary) {
    val level = com.aeriotv.android.core.profiles.ProfileLevel.of(serverLevel(user))
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(level.label, style = style, color = color, maxLines = 1, softWrap = false)
        if (user.apiKey.isNullOrBlank()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            // The glyph keeps its 13 dp at every Text Size, plus the 6 dp gap.
            val slot = with(density) { 19.dp.toSp() }
            val glyph = with(density) { 13.dp.toSp() }
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    appendInlineContent(KEY_GLYPH_ID, "[key]")
                    append("Needs a password or API key")
                },
                style = style,
                color = color,
                inlineContent = mapOf(
                    KEY_GLYPH_ID to androidx.compose.foundation.text.InlineTextContent(
                        androidx.compose.ui.text.Placeholder(slot, glyph, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.Key,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    },
                ),
            )
        }
    }
}

private const val KEY_GLYPH_ID = "key"

/** Focusable button row that expands a section in place (TV: one focus stop). */
@Composable
internal fun ExpandRow(label: String, expanded: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Apple's ProfileExpandRow: inside a profile dialog on phones and tablets
    // the row has no inset, so its label shares the card's left edge with
    // the user rows below; TV keeps it for the focus card.
    val inDialogTouch = LocalInProfileDialog.current && !com.aeriotv.android.ui.settings.rememberIsTvDevice()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .settingsRowCard(focused)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = if (inDialogTouch) 0.dp else 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (inDialogTouch) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
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
        if (com.aeriotv.android.ui.settings.rememberIsTvDevice()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("Search users") },
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
            )
        } else {
            ProfileFieldBox(value = query, onValueChange = onQuery, placeholder = "Search users", modifier = Modifier.focusRequester(fr))
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    }
}

/**
 * Apple's profileFieldBox on phones and tablets: a light fill and a hairline
 * on the dialog card, 10 dp corners and inner padding, body text. The tones
 * follow the card's text color, so the box shows on a light card too.
 */
@Composable
internal fun ProfileFieldBox(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val ink = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodyLarge.copy(color = ink)
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(ink.copy(alpha = 0.08f))
                    .border(1.dp, ink.copy(alpha = 0.12f), shape)
                    .padding(10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                inner()
            }
        },
    )
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
    val touchDialog = LocalInProfileDialog.current && !com.aeriotv.android.ui.settings.rememberIsTvDevice()
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = if (touchDialog) 0.dp else 8.dp, bottom = if (touchDialog) 0.dp else 4.dp)) {
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
                .padding(horizontal = if (touchDialog) 0.dp else 8.dp, vertical = if (touchDialog) 10.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Enable API access", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Lets this user sign in on AerioTV with a key instead of a password",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            // Apple's control: an empty circle, filled with a check when on.
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else androidx.compose.material3.Icon(
                if (enabled) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).alpha(if (interactive) 1f else 0.45f),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmRevoke) {
        ProfileAlertDialog(
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

