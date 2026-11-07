package com.aeriotv.android.feature.devicesetup

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.setup.SetupDiscovery
import com.aeriotv.android.core.setup.SetupProtocol
import com.aeriotv.android.core.setup.SetupSender
import com.aeriotv.android.core.setup.SetupTarget
import com.aeriotv.android.ui.scale.subtext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phone and tablet sender for "Set up another device" (docs/DeviceSetupHandoff.md):
 * pick a TV found on the network (or arrive from its QR code), confirm the
 * 6-digit code it shows, choose what to send, and send it sealed to that TV.
 */
@HiltViewModel
class SetUpTvViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileManager,
    prefs: AppPreferences,
    private val repository: PlaylistRepository,
) : ViewModel() {

    sealed interface Step {
        data object Pick : Step
        /** First step for a target: "Copy entire setup from this device" or "Import this user only". */
        data class Scope(val target: SetupTarget) : Step
        /** "Import this user only" for a profile with no saved sign-in: the roster credential prompt. */
        data class Credential(val target: SetupTarget, val profileId: String, val username: String) : Step
        data class Code(val target: SetupTarget, val error: String? = null) : Step
        data class Choose(val target: SetupTarget, val code: String) : Step
        data object Sending : Step
        data object Sent : Step
        data class Error(val message: String, val back: Step) : Step
    }

    val discovery = SetupDiscovery(context)
    private val sender = SetupSender(profiles, prefs)

    private val _step = MutableStateFlow<Step>(Step.Pick)
    val step: StateFlow<Step> = _step.asStateFlow()
    private val _source = MutableStateFlow<SetupSender.Source?>(null)
    val source: StateFlow<SetupSender.Source?> = _source.asStateFlow()
    private var playlistId: String? = null
    private val _userOnly = MutableStateFlow(false)
    val userOnly: StateFlow<Boolean> = _userOnly.asStateFlow()

    /**
     * Back to a fresh flow. Called when the sheet leaves (Done, Cancel, or a
     * swipe): the view model outlives the sheet, so after one successful
     * send every later entry reopened on the Sent step with only Done and a
     * second setup could not start (Nothing Phone 2026-11-07). Apple builds
     * a fresh SetUpTvView state per presentation.
     */
    fun reset() {
        playlistId = null
        _userOnly.value = false
        _step.value = Step.Pick
    }

    fun load(pid: String?, link: String?, target: SetupTarget? = null) {
        if (playlistId != null) return
        viewModelScope.launch {
            val id = pid ?: repository.activePlaylist()?.id
            playlistId = id
            _source.value = id?.let { sender.source(it) }
            if (target != null) _step.value = afterTarget(target)
            link?.let(SetupProtocol::parseLink)?.let { l ->
                val t = SetupTarget(l.name.ifBlank { "AerioTV" }, l.ip, l.port, l.pk, code = l.code)
                _step.value = afterTarget(t)
            }
        }
    }

    /** A setup code read by the in-app scanner (or picked from Photos). */
    fun useLink(link: String) {
        val l = SetupProtocol.parseLink(link) ?: return
        val t = SetupTarget(l.name.ifBlank { "AerioTV" }, l.ip, l.port, l.pk, code = l.code)
        _step.value = afterTarget(t)
    }

    private val isDispatcharr: Boolean get() = _source.value?.isDispatcharr == true

    /** Scope applies to Dispatcharr only; other playlists go straight to the code. */
    private fun afterTarget(t: SetupTarget): Step = when {
        isDispatcharr -> Step.Scope(t)
        t.code != null -> Step.Choose(t, t.code)
        else -> Step.Code(t)
    }

    fun pick(t: SetupTarget) { _userOnly.value = false; _step.value = afterTarget(t) }

    fun chooseScope(t: SetupTarget, userOnly: Boolean) {
        _userOnly.value = userOnly
        val id = playlistId
        val active = id?.let { profiles.activeProfile(it) }
        _step.value = when {
            userOnly && active != null && !profiles.hasSavedCredential(active.id) -> Step.Credential(t, active.id, active.username)
            t.code != null -> Step.Choose(t, t.code)
            else -> Step.Code(t)
        }
    }

    /** Roster prompt submit: null = saved, else the error to show. */
    suspend fun saveCredential(s: Step.Credential, password: String?, apiKey: String?): String? {
        val id = playlistId ?: return "This playlist could not be read."
        return profiles.saveProfileCredential(id, s.profileId, password, apiKey).fold(
            onSuccess = {
                _source.value = sender.source(id)
                _step.value = if (s.target.code != null) Step.Choose(s.target, s.target.code) else Step.Code(s.target)
                null
            },
            onFailure = { it.message ?: "Could not sign in." },
        )
    }

    fun cancelCredential(s: Step.Credential) { _step.value = Step.Scope(s.target) }

    private fun afterTargetBack(t: SetupTarget): Step = if (isDispatcharr) Step.Scope(t) else Step.Pick

    fun back() {
        _step.value = when (val s = _step.value) {
            is Step.Choose -> if (s.target.code != null) afterTargetBack(s.target) else Step.Code(s.target)
            is Step.Code -> afterTargetBack(s.target)
            is Step.Credential -> Step.Scope(s.target)
            is Step.Error -> s.back
            else -> Step.Pick
        }
    }

    fun submitCode(t: SetupTarget, code: String) {
        val c = code.filter(Char::isDigit)
        if (c.length != 6) { _step.value = Step.Code(t, "Enter the 6-digit code shown on the other device."); return }
        _step.value = Step.Choose(t, c)
    }

    fun send(target: SetupTarget, code: String, profileIds: Set<String>, sync: Boolean) {
        val id = playlistId ?: return
        val back = Step.Choose(target, code)
        _step.value = Step.Sending
        viewModelScope.launch {
            // Resolve again right before every send: the device may have started a
            // new session (new port and key) since the card or list last saw it.
            val t = SetupDiscovery.fresh(context, target)
            val payload = sender.buildPayload(id, profileIds, sync, userOnly = _userOnly.value)
                ?: run { _step.value = Step.Error("This playlist could not be read.", back); return@launch }
            _step.value = when (val r = sender.send(t, code, payload)) {
                SetupSender.SendResult.Ok -> {
                    // Never offered again under this key, even if the advert goodbye is lost.
                    SetupDockDismissals.dismiss(t)
                    Step.Sent
                }
                SetupSender.SendResult.WrongCode -> Step.Code(t.copy(code = null), "The device did not accept that code. Check the code on the device and try again.")
                SetupSender.SendResult.Refused -> Step.Error("The device is no longer waiting. Start setup on the device again.", Step.Pick)
                is SetupSender.SendResult.Failed -> Step.Error(r.message, back)
            }
        }
    }

    override fun onCleared() { discovery.stop() }
}

/**
 * "Set up another device" flow. [playlistId] null = the active playlist; [link] = a scanned aeriotv://setup URL.
 * Phones and tablets: styled as the remote-session (Cast) sheet, hugging its content. TV: full screen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SetUpTvDialog(playlistId: String?, link: String? = null, target: SetupTarget? = null, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isTv = androidx.compose.runtime.remember {
        ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    }
    if (isTv) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SetUpTvScreen(playlistId, link, onDismiss, target = target)
        }
    } else {
        // The remote-session (Cast) sheet's container: black, drag handle, hugs its content.
        com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss, sheetContainerColor = androidx.compose.ui.graphics.Color.Black) {
            androidx.compose.runtime.CompositionLocalProvider(LocalSetupSheetStyle provides true) {
                SetUpTvScreen(playlistId, link, onDismiss, target = target, inSheet = true)
            }
        }
    }
}

@Composable
fun SetUpTvScreen(playlistId: String?, link: String?, onClose: () -> Unit, target: SetupTarget? = null, inSheet: Boolean = false, vm: SetUpTvViewModel = hiltViewModel()) {
    LaunchedEffect(playlistId, link) { vm.load(playlistId, link, target) }
    DisposableEffect(Unit) { vm.discovery.start(); onDispose { vm.discovery.stop(); vm.reset() } }
    val step by vm.step.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val tvs by vm.discovery.tvs.collectAsStateWithLifecycle()

    Column(
        (if (inSheet) Modifier.fillMaxWidth().imePadding()
        else Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing))
            .verticalScroll(rememberScrollState())
            .then(if (inSheet) Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 18.dp) else Modifier.padding(horizontal = 16.dp, vertical = 20.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (inSheet) 14.dp else 12.dp)) {
            if (inSheet) {
                // Remote-session sheet header: accent glyph, bold title, accent device line.
                val t = step.setupTarget()
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                        Icon(t?.let { setupKindIcon(it.kind) } ?: Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    }
                    Text("Set up another device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
                    if (t != null) Text(t.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                Text("Set up another device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            }
            when (val s = step) {
                SetUpTvViewModel.Step.Pick -> PickStep(tvs, vm::pick, vm::useLink)
                is SetUpTvViewModel.Step.Scope -> ScopeStep(s, vm.userOnly.collectAsStateWithLifecycle().value, vm::chooseScope)
                is SetUpTvViewModel.Step.Credential -> {
                    ScopeStep(SetUpTvViewModel.Step.Scope(s.target), true) { _, _ -> }
                    com.aeriotv.android.feature.settings.RosterCredentialDialog(
                        username = s.username,
                        onCancel = { vm.cancelCredential(s) },
                        onSubmit = { pw, key -> vm.saveCredential(s, pw, key) },
                        title = "Sign in as ${s.username}",
                        confirmLabel = "Continue",
                    )
                }
                is SetUpTvViewModel.Step.Code -> CodeStep(s, vm::submitCode)
                is SetUpTvViewModel.Step.Choose -> ChooseStep(s, source, vm.userOnly.collectAsStateWithLifecycle().value, vm::send)
                SetUpTvViewModel.Step.Sending -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text("Sending to the other device", color = MaterialTheme.colorScheme.onBackground)
                }
                SetUpTvViewModel.Step.Sent -> {
                    Text("Sent. The device is finishing setup now.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    SetupPrimaryButton("Done", onClick = onClose)
                }
                is SetUpTvViewModel.Step.Error -> {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    SetupPrimaryButton("Try Again", onClick = vm::back)
                }
            }
            if (step !is SetUpTvViewModel.Step.Sent && step !is SetUpTvViewModel.Step.Sending) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step !is SetUpTvViewModel.Step.Pick) SetupSecondaryButton("Back", onClick = vm::back, modifier = Modifier.weight(1f))
                    SetupSecondaryButton("Cancel", onClick = onClose, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PickStep(tvs: List<SetupTarget>, onPick: (SetupTarget) -> Unit, onLink: (String) -> Unit) {
    Text(
        "On the other device, open AerioTV and choose Set up from another device. Then pick it below, or scan the code it shows.",
        style = MaterialTheme.typography.bodyMedium.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (tvs.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Looking for devices on this network", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    tvs.forEach { t ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(setupRowRadius()))
                .background(setupRowBackground())
                .clickable { onPick(t) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(setupKindIcon(t.kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text("Check that the device shows ${t.fingerprint}", style = MaterialTheme.typography.bodySmall.subtext(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    com.aeriotv.android.feature.qrscan.QrScanActions(
        prompt = "Point the camera at the code on the other device.",
        accept = { r ->
            when (r) {
                is com.aeriotv.android.core.qr.ScannedCode.Result.SetupLink -> null
                is com.aeriotv.android.core.qr.ScannedCode.Result.UserKey ->
                    "This is a Dispatcharr user code. Scan the code shown on the other device."
                com.aeriotv.android.core.qr.ScannedCode.Result.Invalid -> com.aeriotv.android.core.qr.ScannedCode.INVALID_MESSAGE
            }
        },
        onResult = { r -> if (r is com.aeriotv.android.core.qr.ScannedCode.Result.SetupLink) onLink(r.link) },
    )
}

@Composable
private fun ScopeStep(s: SetUpTvViewModel.Step.Scope, userOnly: Boolean, onChoose: (SetupTarget, Boolean) -> Unit) {
    var pickedUserOnly by remember(s.target.key) { mutableStateOf(userOnly) }
    if (LocalSetupSheetStyle.current) {
        // The sheet header already names the device; match the iPhone sheet.
        Text("What to send", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("Send to ${s.target.name}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(setupRowRadius()))
            .background(setupRowBackground())
            .selectableGroup(),
    ) {
        listOf(false to "Copy entire setup from this device", true to "Import this user only").forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = pickedUserOnly == value, onClick = { pickedUserOnly = value }, role = Role.RadioButton)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = pickedUserOnly == value, onClick = null)
                Text(label, modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
    SetupPrimaryButton("Continue", onClick = { onChoose(s.target, pickedUserOnly) })
}

@Composable
private fun CodeStep(s: SetUpTvViewModel.Step.Code, onSubmit: (SetupTarget, String) -> Unit) {
    // State-based field with an input transformation (2026-11-07): the
    // value/onValueChange form kept only 3 of 6 digits when they arrived in
    // one burst (adb typing on the Nothing Phone; a paste takes the same
    // path), because each filtered write raced the IME's next edit. The
    // transformation edits the buffer in place, so a 6-digit paste lands
    // intact.
    val codeState = remember(s.target.key) { androidx.compose.foundation.text.input.TextFieldState() }
    val code = codeState.text.toString()
    val prompt = if (LocalSetupSheetStyle.current) "Enter the code shown on the other device." else "Enter the code shown on ${s.target.name}."
    Text(prompt, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    OutlinedTextField(
        state = codeState,
        inputTransformation = {
            val digits = asCharSequence().filter(Char::isDigit).take(6).toString()
            if (digits != asCharSequence().toString()) replace(0, length, digits)
        },
        label = { Text("6-digit code") },
        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
        isError = s.error != null,
        // Number, not NumberPassword: a password field made the password
        // manager offer "Save password" for a one-time pairing code. Apple's
        // field is a plain number pad with no content type, the same.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    SetupPrimaryButton("Continue", onClick = { onSubmit(s.target, code) }, enabled = code.length == 6)
}

@Composable
private fun ChooseStep(
    s: SetUpTvViewModel.Step.Choose,
    source: SetupSender.Source?,
    userOnly: Boolean,
    onSend: (SetupTarget, String, Set<String>, Boolean) -> Unit,
) {
    if (source == null) {
        Text("There is no playlist on this device to send.", color = MaterialTheme.colorScheme.error)
        return
    }
    val picked = remember(source) {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            // Copy entire setup: every profile that can be sent.
            addAll(source.choices.filter { it.isDefault || it.credentialLabel != null }.map { it.profileId })
        }
    }
    var sync by remember(source, userOnly) { mutableStateOf(source.isDispatcharr && !userOnly) }
    if (!LocalSetupSheetStyle.current) {
        Text("Send to ${s.target.name}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    }
    Text("Playlist: ${source.playlist.name}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    if (source.isDispatcharr && source.choices.isNotEmpty() && !userOnly) {
        Text("Profiles", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        source.choices.forEach { c ->
            val enabled = c.credentialLabel != null && !c.isDefault
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) {
                    if (c.profileId in picked) picked.remove(c.profileId) else picked.add(c.profileId)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = c.profileId in picked, onCheckedChange = null, enabled = enabled)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(c.displayName, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        when {
                            c.isDefault -> "Signs in with the playlist login. Always sent."
                            c.credentialLabel != null -> c.credentialLabel
                            else -> "No saved sign-in on this device. Add it on the other device later."
                        },
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (source.isDispatcharr) {
        Row(Modifier.fillMaxWidth().clickable { sync = !sync }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = sync, onCheckedChange = null)
            Text("Turn on Dispatcharr Sync on the other device", modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onBackground)
        }
    }
    Spacer(Modifier.heightIn(min = 4.dp))
    SetupPrimaryButton("Send", onClick = { onSend(s.target, s.code, picked.toSet(), sync) })
    Text(
        "Sent only to this device over your network, encrypted with its code. Nothing goes through your server.",
        style = MaterialTheme.typography.bodySmall.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** True inside the phone/tablet sheet: the remote-session (Cast) sheet's styling. */
private val LocalSetupSheetStyle = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun setupRowBackground(): androidx.compose.ui.graphics.Color =
    if (LocalSetupSheetStyle.current) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f)
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)

@Composable
private fun setupRowRadius() = if (LocalSetupSheetStyle.current) 14.dp else 12.dp

private fun SetUpTvViewModel.Step.setupTarget(): SetupTarget? = when (this) {
    is SetUpTvViewModel.Step.Scope -> target
    is SetUpTvViewModel.Step.Credential -> target
    is SetUpTvViewModel.Step.Code -> target
    is SetUpTvViewModel.Step.Choose -> target
    else -> null
}

/** Primary action: the remote-session sheet's wide button (14 dp corners, accent fill) in the sheet, a Material button on TV. */
@Composable
private fun SetupPrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    if (LocalSetupSheetStyle.current) {
        SetupWideButton(label, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, onClick, enabled, Modifier.fillMaxWidth())
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(label) }
    }
}

/** Secondary action: the remote-session sheet's 12% white wide button in the sheet, an outlined button on TV. */
@Composable
private fun SetupSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (LocalSetupSheetStyle.current) {
        SetupWideButton(label, androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f), onClick, true, modifier)
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun SetupWideButton(label: String, content: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(14.dp)).background(background)
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = content)
    }
}
