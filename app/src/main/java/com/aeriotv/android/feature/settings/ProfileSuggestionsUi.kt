package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.clickable
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

    if (rows.isEmpty() && added.isEmpty()) return

    fun add(r: ProfileRow) {
        if (busy != null || !enabled) return
        error = null
        if (r.role == ProfileRole.Child.wire && !vm.adminHasPin(playlistId)) { childGate = r; return }
        val listed = users?.firstOrNull { it.username.equals(r.username, ignoreCase = true) }
        if (listed?.apiKey.isNullOrBlank()) { credFor = r; return }
        busy = r.username
        scope.launch {
            vm.addFromRoster(playlistId, r, listed!!, null, null).fold(
                { busy = null; added += r; onAdded() },
                { busy = null; error = it.message ?: "Could not add profile" },
            )
        }
    }

    val listedRows = rows + added.filter { a -> rows.none { it.username.equals(a.username, ignoreCase = true) } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("From your other devices", style = MaterialTheme.typography.titleSmall)
        listedRows.forEach { r ->
            var focused by remember { mutableStateOf(false) }
            val isAdded = added.any { it.username.equals(r.username, ignoreCase = true) }
            val canAdd = enabled && busy == null && !isAdded
            Row(
                Modifier
                    .fillMaxWidth()
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
                ProfileRole.fromWire(r.role)?.let { RoleBadge(it) }
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
                    { credFor = null; added += r; onAdded(); null },
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
private fun ChildNeedsAdminPin(playlistId: String, vm: ProfilesViewModel, onDone: (Boolean) -> Unit) {
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
