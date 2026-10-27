package com.aeriotv.android.feature.onboarding

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dispatcharr-only add step, right after the server connection succeeds:
 * "Add profiles for other users?". Lists the server's users (admin list,
 * GET /api/accounts/users/) minus the connected login, which is already the
 * Admin/default profile. A non-admin login (403, or a level below admin)
 * gets manual add rows instead: username, then password or API key.
 */
@HiltViewModel
class AddProfilesStepViewModel @Inject constructor(
    private val manager: ProfileManager,
    private val client: DispatcharrClient,
    private val repository: PlaylistRepository,
) : ViewModel() {

    sealed interface Step {
        data class Pick(val users: List<DispatcharrUserSummary>) : Step
        /** The base login is not a server admin (or the list answered 403). */
        data object Manual : Step
        data object Skip : Step
    }

    /** Which form to show, or Skip. Never throws. */
    suspend fun candidates(playlistId: String): Step = runCatching {
        when (val c = manager.addCandidates(playlistId).getOrThrow()) {
            ProfileManager.AddCandidates.Manual -> Step.Manual
            is ProfileManager.AddCandidates.ServerList -> {
                val row = repository.playlistById(playlistId)
                val key = row?.apiKey?.takeIf { it.isNotBlank() }
                val meName = key?.let { runCatching { client.fetchMe(repository.effectiveBaseUrl(row), it)?.username }.getOrNull() }
                    ?: row?.username
                val listed = c.users.filterNot { meName != null && it.username.equals(meName, ignoreCase = true) }
                if (listed.isEmpty()) Step.Skip else Step.Pick(listed)
            }
        }
    }.getOrElse {
        Log.i(TAG, "[PROFILE] onboarding step skipped: ${it.message}")
        Step.Skip
    }

    suspend fun addManual(playlistId: String, username: String, password: String?, apiKey: String?, role: com.aeriotv.android.core.profiles.ProfileRole? = null) =
        if (apiKey != null) manager.addProfileByApiKey(playlistId, apiKey, role)
        else manager.addProfileManual(playlistId, username, password, null, role)

    suspend fun add(playlistId: String, user: DispatcharrUserSummary, password: String?, apiKey: String?, role: com.aeriotv.android.core.profiles.ProfileRole? = null) =
        manager.addProfile(playlistId, user, password, apiKey, role)

    private companion object { const val TAG = "AddProfilesStep" }
}

@Composable
fun AddProfilesStepScreen(
    playlistId: String,
    onDone: () -> Unit,
    vm: AddProfilesStepViewModel = hiltViewModel(),
) {
    var users by remember { mutableStateOf<List<DispatcharrUserSummary>?>(null) }
    val selected = remember { mutableStateListOf<Int>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Users whose key was missing: prompt for a password or key, one at a time.
    val needsCredential = remember { mutableStateListOf<DispatcharrUserSummary>() }
    var role by remember { mutableStateOf(com.aeriotv.android.core.profiles.ProfileRole.User) }
    val profilesVm: com.aeriotv.android.feature.settings.ProfilesViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(playlistId) {
        when (val step = vm.candidates(playlistId)) {
            AddProfilesStepViewModel.Step.Skip -> onDone()
            AddProfilesStepViewModel.Step.Manual -> manual = true
            is AddProfilesStepViewModel.Step.Pick -> users = step.users
        }
    }
    if (manual) {
        ManualAddProfilesStep(playlistId, vm, onDone)
        return
    }

    val list = users
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
    var query by remember { mutableStateOf("") }
    val shown = remember(list, query) {
        val q = query.trim()
        if (q.isEmpty()) list else list.filter { it.username.contains(q, ignoreCase = true) }
    }
    // The user list is the primary content and takes the height; search only
    // filters it. TV (landscape, ~540 dp tall) puts the list on the left and
    // Role plus the buttons on the right so rows are never squeezed (owner
    // 2026-10-27: one row was left ~85 px and overlapped by Role). Phones
    // stack it: list, compact Role, buttons pinned at the bottom.
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val header: @Composable () -> Unit = {
                Text(
                    "Add profiles for other users?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Pick the Dispatcharr users who also watch on this device.",
                    style = MaterialTheme.typography.bodyMedium.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search users") },
                    modifier = Modifier.fillMaxWidth().dpadFocusRing(RoundedCornerShape(4.dp)),
                )

    }
    val userList: @Composable (Modifier) -> Unit = { listModifier ->
                Column(
                    modifier = listModifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    shown.forEach { user ->
                        val checked = user.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .dpadFocusRing(RoundedCornerShape(12.dp), washTint = MaterialTheme.colorScheme.primary)
                                .toggleable(
                                    value = checked,
                                    enabled = !busy,
                                    role = Role.Checkbox,
                                    onValueChange = { on -> if (on) selected.add(user.id) else selected.remove(user.id) },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.size(12.dp))
                            Text(user.username, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            com.aeriotv.android.feature.settings.LevelBadge(
                                com.aeriotv.android.core.profiles.ProfileLevel.of(
                                    if (user.isSuperuser || user.isStaff) 10 else user.userLevel ?: 0,
                                ),
                            )
                        }
                    }
                    if (shown.isEmpty()) {
                        Text(
                            "No users match.",
                            style = MaterialTheme.typography.bodyMedium.subtext(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

    }
    val controls: @Composable () -> Unit = {
                com.aeriotv.android.feature.settings.RolePicker(playlistId, profilesVm, role) { role = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            val failures = mutableListOf<String>()
                            list.filter { it.id in selected }.forEach { u ->
                                if (u.apiKey.isNullOrBlank()) {
                                    needsCredential.add(u)
                                } else {
                                    vm.add(playlistId, u, null, null, role).onFailure { failures += u.username }
                                }
                            }
                            busy = false
                            if (failures.isNotEmpty()) {
                                error = "Could not add: ${failures.joinToString(", ")}"
                                selected.retainAll(list.filter { it.username in failures }.map { it.id })
                            } else if (needsCredential.isEmpty()) {
                                onDone()
                            }
                        }
                    },
                    enabled = !busy && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50), onFilled = true),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text("Add selected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onDone,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50)),
                ) { Text("Skip", style = MaterialTheme.typography.titleMedium) }
                Text(
                    "You can add or remove profiles later in Settings > Playlists.",
                    style = MaterialTheme.typography.bodySmall.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = vp.gutter, vertical = if (isTv) 16.dp else 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (isTv) {
            Row(
                modifier = Modifier.widthIn(max = 1100.dp).fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1.3f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    header()
                    userList(Modifier.weight(1f))
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { controls() }
            }
        } else {
            Column(
                modifier = (if (vp.onboardingMaxWidth != androidx.compose.ui.unit.Dp.Unspecified)
                    Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth()
                else Modifier.fillMaxWidth()).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                header()
                userList(Modifier.weight(1f))
                controls()
            }
        }
    }

    needsCredential.firstOrNull()?.let { u ->
        CredentialDialog(
            user = u,
            onSubmit = { password, key ->
                vm.add(playlistId, u, password, key, role).fold(
                    { needsCredential.remove(u); if (needsCredential.isEmpty() && error == null) onDone(); null },
                    { it.message ?: "Could not add profile" },
                )
            },
            onCancel = { needsCredential.remove(u); if (needsCredential.isEmpty() && error == null) onDone() },
        )
    }
}

/** Fallback when the users list carried no api_key for [user]. */
@Composable
private fun CredentialDialog(
    user: DispatcharrUserSummary,
    onSubmit: suspend (String?, String?) -> String?,
    onCancel: () -> Unit,
) {
    var password by remember(user.id) { mutableStateOf("") }
    var apiKey by remember(user.id) { mutableStateOf("") }
    var busy by remember(user.id) { mutableStateOf(false) }
    var error by remember(user.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add ${user.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sign in as ${user.username} once. Enter the password or an API key.")
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
                    label = { Text("API Key") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (password.isNotBlank() || apiKey.isNotBlank()),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        error = onSubmit(password.takeIf { it.isNotBlank() }, apiKey.trim().takeIf { it.isNotBlank() })
                        busy = false
                    }
                },
            ) { Text("Add Profile") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Non-admin base: type each user's name and sign in as them once. */
@Composable
private fun ManualAddProfilesStep(playlistId: String, vm: AddProfilesStepViewModel, onDone: () -> Unit) {
    val added = remember { mutableStateListOf<String>() }
    var role by remember { mutableStateOf(com.aeriotv.android.core.profiles.ProfileRole.User) }
    val profilesVm: com.aeriotv.android.feature.settings.ProfilesViewModel = hiltViewModel()
    val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vp.gutter, vertical = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = if (vp.onboardingMaxWidth != androidx.compose.ui.unit.Dp.Unspecified)
                Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth()
            else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Add profiles for other users?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                "Add the Dispatcharr users who also watch on this device.",
                style = MaterialTheme.typography.bodyMedium.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            added.forEach { name ->
                Text("Added $name", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            }
            com.aeriotv.android.feature.settings.RolePicker(playlistId, profilesVm, role) { role = it }
            com.aeriotv.android.feature.onboarding.components.ManualProfileForm(
                onAdd = { u, pw, key ->
                    vm.addManual(playlistId, u, pw, key, role).fold(
                        { added += it.username; null },
                        { it.message ?: "Could not add profile" },
                    )
                },
            )
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50)),
            ) { Text(if (added.isEmpty()) "Skip" else "Done", style = MaterialTheme.typography.titleMedium) }
            Text(
                "You can add or remove profiles later in Settings > Playlists.",
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
