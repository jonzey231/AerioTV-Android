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
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    private val prefs: com.aeriotv.android.core.preferences.AppPreferences,
) : ViewModel() {

    /** Dispatcharr Sync is on: roster suggestions can still land from the first pull. */
    suspend fun syncOn(): Boolean =
        prefs.syncProviderOnce() == com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager.PROVIDER

    fun suggestions(playlistId: String) = manager.rosterSuggestions(playlistId)

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

private const val TAG = "AddProfilesStep"

@Composable
fun AddProfilesStepScreen(
    playlistId: String,
    onDone: () -> Unit,
    /** Phone and tablet only: "Set up a TV with this playlist" (docs/DeviceSetupHandoff.md). */
    onSetUpTv: (() -> Unit)? = null,
    /** Onboarding: "Step 2 of 3" caption. */
    onboarding: Boolean = false,
    vm: AddProfilesStepViewModel = hiltViewModel(),
) {
    var users by remember { mutableStateOf<List<DispatcharrUserSummary>?>(null) }
    val selected = remember { mutableStateListOf<Int>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Users whose key was missing: prompt for a password or key, one at a time.
    val needsCredential = remember { mutableStateListOf<DispatcharrUserSummary>() }
    // Role per checked row (owner review 2026-10-27), default User.
    val rowRoles = remember { androidx.compose.runtime.mutableStateMapOf<Int, com.aeriotv.android.core.profiles.ProfileRole>() }
    fun roleOf(u: DispatcharrUserSummary) = rowRoles[u.id] ?: com.aeriotv.android.core.profiles.ProfileRole.User
    var childGate by remember { mutableStateOf(false) }
    // Plain holder (not state): the Add action to run after the Child PIN gate.
    val pendingAdd = remember { arrayOfNulls<() -> Unit>(1) }
    val profilesVm: com.aeriotv.android.feature.settings.ProfilesViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    var manual by remember { mutableStateOf(false) }
    // "From your other devices" (synced roster, opt-in). The step runs after
    // the Dispatcharr Sync step; it observes the roster, so suggestions show
    // up when the first pull lands even if the step rendered first.
    val suggestFlow = remember(playlistId) { vm.suggestions(playlistId) }
    val suggested by suggestFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val localFlow = remember(playlistId) { profilesVm.profiles(playlistId) }
    val localProfiles = localFlow.collectAsStateWithLifecycle(initialValue = emptyList()).value
    val localCount = localProfiles.size
    LaunchedEffect(playlistId) {
        when (val step = vm.candidates(playlistId)) {
            AddProfilesStepViewModel.Step.Skip -> {
                // Nothing from the server list: wait briefly for the first
                // pull's roster before skipping (sync off: skip now).
                val rows = if (vm.syncOn()) kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                    suggestFlow.first { it.isNotEmpty() }
                } else null
                Log.i(TAG, "[DSYNC] onboarding add step: ${rows?.size ?: 0} suggestions")
                if (rows.isNullOrEmpty()) onDone() else users = emptyList()
            }
            AddProfilesStepViewModel.Step.Manual -> manual = true
            is AddProfilesStepViewModel.Step.Pick -> users = step.users
        }
    }
    if (manual) {
        ManualAddProfilesStep(playlistId, vm, onDone, onSetUpTv)
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
    // Enable API access (server admin only): keys minted here, by user id.
    var canManageKeys by remember { mutableStateOf(false) }
    LaunchedEffect(playlistId) { canManageKeys = profilesVm.canManageUserApiKeys(playlistId) }
    val minted = remember { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
    var query by remember { mutableStateOf("") }
    // Suggestions are the headline; the server list sits behind "Show other
    // users on this server" unless there are no suggestions.
    var serverOpen by remember { mutableStateOf(false) }
    val serverExpanded = serverOpen
    // Server users not suggested and not already a profile on this device.
    val rest = remember(list, suggested, localProfiles) {
        list.filter { u ->
            suggested.none { it.username.equals(u.username, ignoreCase = true) } &&
                localProfiles.none { it.username.equals(u.username, ignoreCase = true) }
        }
    }
    val shown = remember(rest, query) {
        val q = query.trim()
        if (q.isEmpty()) rest else rest.filter { it.username.contains(q, ignoreCase = true) }
    }
    // The user list is the primary content and takes the height; search only
    // filters it. TV (landscape, ~540 dp tall) puts the list on the left and
    // Role plus the buttons on the right so rows are never squeezed (owner
    // 2026-10-27: one row was left ~85 px and overlapped by Role). Phones
    // stack it: list, compact Role, buttons pinned at the bottom.
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val device = rememberDeviceNoun()
    val header: @Composable () -> Unit = {
        if (onboarding) com.aeriotv.android.feature.onboarding.components.StepCaption(2, 3)
        ProfilesStepHeader(device)
    }
    val listScroll = rememberScrollState()
    val userList: @Composable (Modifier) -> Unit = { listModifier ->
                Column(
                    modifier = listModifier.fillMaxWidth().verticalScroll(listScroll),
                    verticalArrangement = Arrangement.spacedBy(if (isTv) 4.dp else 0.dp),
                ) {
                    OnThisDeviceSection(device, localProfiles)
                    Spacer(Modifier.height(10.dp))
                    com.aeriotv.android.feature.settings.RosterSuggestions(
                        playlistId, profilesVm, list, enabled = !busy,
                        title = "FROM YOUR OTHER DEVICES",
                        footnote = ROSTER_FOOTNOTE,
                    )
                    if (rest.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        AddFromDispatcharrRow(enabled = !busy) { serverOpen = !serverOpen; if (!serverOpen) query = "" }
                    }
                    if (serverExpanded && rest.isNotEmpty()) {
                        com.aeriotv.android.feature.settings.ExpandableSearch(query, { query = it }, enabled = !busy)
                    }
                    if (serverExpanded) shown.forEach { user ->
                        val checked = user.id in selected
                        // TV: the row and the role chip are two focus stops.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
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
                            Column(Modifier.weight(1f)) {
                                Text(user.username, style = MaterialTheme.typography.titleMedium)
                                com.aeriotv.android.feature.settings.ServerUserCaption(user)
                            }
                        }
                        if (checked) com.aeriotv.android.feature.settings.RoleChip(roleOf(user), enabled = !busy) { rowRoles[user.id] = it }
                        }
                        if (canManageKeys && (user.apiKey.isNullOrBlank() || minted[user.id] == true)) {
                            com.aeriotv.android.feature.settings.EnableApiAccessRow(
                                playlistId, profilesVm, user,
                                enabled = minted[user.id] == true,
                                interactive = !busy,
                                // The row now carries the key: Add selected takes the keyed path, no prompt.
                                onEnabled = { key ->
                                    minted[user.id] = true
                                    users = users?.map { if (it.id == user.id) it.copy(apiKey = key) else it }
                                },
                                onRevoked = {
                                    minted.remove(user.id)
                                    users = users?.map { if (it.id == user.id) it.copy(apiKey = null) else it }
                                },
                            )
                        }
                    }
                    val noneOnServer = rest.isEmpty()
                    if (serverExpanded && shown.isEmpty() && (suggested.isEmpty() || !noneOnServer)) {
                        Text(
                            if (noneOnServer) "No other users on this server." else "No users match.",
                            style = MaterialTheme.typography.bodyMedium.subtext(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

    }
    val controlsTail: @Composable () -> Unit = {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val addSelected: () -> Unit = {
                        busy = true; error = null
                        scope.launch {
                            val failures = mutableListOf<String>()
                            list.filter { it.id in selected }.forEach { u ->
                                if (u.apiKey.isNullOrBlank()) {
                                    needsCredential.add(u)
                                } else {
                                    vm.add(playlistId, u, null, null, roleOf(u)).onFailure { failures += u.username }
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
                }
                pendingAdd[0] = addSelected
                Button(
                    onClick = {
                        when {
                            selected.isEmpty() -> onDone()
                            // A Child row needs an Admin PIN on this device first.
                            list.any { it.id in selected && roleOf(it) == com.aeriotv.android.core.profiles.ProfileRole.Child } &&
                                !profilesVm.adminHasPin(playlistId) -> childGate = true
                            else -> addSelected()
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50), onFilled = true),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text(if (selected.isNotEmpty()) "Add selected" else "Continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                ProfileCountLine(device, localProfiles)
                if (onSetUpTv != null) {
                    androidx.compose.material3.TextButton(
                        onClick = onSetUpTv,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Set up a TV with this playlist", style = MaterialTheme.typography.titleMedium) }
                }
    }
    val controls: @Composable () -> Unit = {
                controlsTail()
                // Extra bottom inset on phones so a toast does not cover the footer.
                Spacer(Modifier.height(16.dp))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = vp.gutter, vertical = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (isTv) {
            Column(
                modifier = Modifier.widthIn(max = 1100.dp).fillMaxWidth().fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Title and subtitle centered across the full content width.
                Column(
                    modifier = Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { header() }
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Left: search, suggestions, and users as one scrolling
                    // list, with a bottom fade while more is below.
                    Box(Modifier.weight(0.58f).fillMaxHeight()) {
                        userList(Modifier.fillMaxHeight())
                        if (listScroll.canScrollForward) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            listOf(
                                                androidx.compose.ui.graphics.Color.Transparent,
                                                MaterialTheme.colorScheme.background,
                                            ),
                                        ),
                                    ),
                            )
                        }
                    }
                    // Right: the actions.
                    Column(
                        modifier = Modifier.weight(0.42f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        controlsTail()
                    }
                }
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

    if (childGate) {
        com.aeriotv.android.feature.settings.ChildNeedsAdminPin(playlistId, profilesVm) { ok ->
            childGate = false
            if (ok) pendingAdd[0]?.invoke()
        }
    }
    needsCredential.firstOrNull()?.let { u ->
        CredentialDialog(
            user = u,
            onSubmit = { password, key ->
                vm.add(playlistId, u, password, key, roleOf(u)).fold(
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
private fun ManualAddProfilesStep(playlistId: String, vm: AddProfilesStepViewModel, onDone: () -> Unit, onSetUpTv: (() -> Unit)?) {
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
            ProfilesStepHeader(rememberDeviceNoun())
            com.aeriotv.android.feature.settings.RosterSuggestions(
                playlistId, profilesVm, users = null,
                title = "FROM YOUR OTHER DEVICES",
                footnote = ROSTER_FOOTNOTE,
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
            if (onSetUpTv != null) {
                androidx.compose.material3.TextButton(
                    onClick = onSetUpTv,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Set up a TV with this playlist", style = MaterialTheme.typography.titleMedium) }
            }
            Text(
                "You can add or remove profiles later in Settings > Playlists.",
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
