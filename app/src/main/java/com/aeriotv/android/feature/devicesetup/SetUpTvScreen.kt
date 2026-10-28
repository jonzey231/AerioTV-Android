package com.aeriotv.android.feature.devicesetup

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 * Phone and tablet sender for "Set up a TV" (docs/DeviceSetupHandoff.md):
 * pick a TV found on the network (or arrive from its QR code), confirm the
 * 6-digit code it shows, choose what to send, and send it sealed to that TV.
 */
@HiltViewModel
class SetUpTvViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: ProfileManager,
    prefs: AppPreferences,
    private val repository: PlaylistRepository,
) : ViewModel() {

    sealed interface Step {
        data object Pick : Step
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

    fun load(pid: String?, link: String?) {
        if (playlistId != null) return
        viewModelScope.launch {
            val id = pid ?: repository.activePlaylist()?.id
            playlistId = id
            _source.value = id?.let { sender.source(it) }
            link?.let(SetupProtocol::parseLink)?.let { l ->
                val t = SetupTarget(l.name.ifBlank { "TV" }, l.ip, l.port, l.pk, code = l.code)
                _step.value = Step.Choose(t, l.code)
            }
        }
    }

    fun pick(t: SetupTarget) { _step.value = if (t.code != null) Step.Choose(t, t.code) else Step.Code(t) }

    fun back() {
        _step.value = when (val s = _step.value) {
            is Step.Choose -> if (s.target.code != null) Step.Pick else Step.Code(s.target)
            is Step.Error -> s.back
            else -> Step.Pick
        }
    }

    fun submitCode(t: SetupTarget, code: String) {
        val c = code.filter(Char::isDigit)
        if (c.length != 6) { _step.value = Step.Code(t, "Enter the 6-digit code shown on the TV."); return }
        _step.value = Step.Choose(t, c)
    }

    fun send(t: SetupTarget, code: String, profileIds: Set<String>, sync: Boolean) {
        val id = playlistId ?: return
        val back = Step.Choose(t, code)
        _step.value = Step.Sending
        viewModelScope.launch {
            val payload = sender.buildPayload(id, profileIds, sync)
                ?: run { _step.value = Step.Error("This playlist could not be read.", back); return@launch }
            _step.value = when (val r = sender.send(t, code, payload)) {
                SetupSender.SendResult.Ok -> Step.Sent
                SetupSender.SendResult.WrongCode -> Step.Code(t.copy(code = null), "The TV did not accept that code. Check the code on the TV and try again.")
                SetupSender.SendResult.Refused -> Step.Error("The TV is no longer waiting for setup. Start setup on the TV again.", Step.Pick)
                is SetupSender.SendResult.Failed -> Step.Error(r.message, back)
            }
        }
    }

    override fun onCleared() { discovery.stop() }
}

/** Full-screen "Set up a TV" flow. [playlistId] null = the active playlist; [link] = a scanned aeriotv://setup URL. */
@Composable
fun SetUpTvDialog(playlistId: String?, link: String? = null, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SetUpTvScreen(playlistId, link, onDismiss)
    }
}

@Composable
fun SetUpTvScreen(playlistId: String?, link: String?, onClose: () -> Unit, vm: SetUpTvViewModel = hiltViewModel()) {
    LaunchedEffect(playlistId, link) { vm.load(playlistId, link) }
    DisposableEffect(Unit) { vm.discovery.start(); onDispose { vm.discovery.stop() } }
    val step by vm.step.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val tvs by vm.discovery.tvs.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Set up a TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            when (val s = step) {
                SetUpTvViewModel.Step.Pick -> PickStep(tvs, vm::pick)
                is SetUpTvViewModel.Step.Code -> CodeStep(s, vm::submitCode)
                is SetUpTvViewModel.Step.Choose -> ChooseStep(s, source, vm::send)
                SetUpTvViewModel.Step.Sending -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text("Sending to the TV", color = MaterialTheme.colorScheme.onBackground)
                }
                SetUpTvViewModel.Step.Sent -> {
                    Text("Sent. The TV is finishing setup now.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Done") }
                }
                is SetUpTvViewModel.Step.Error -> {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::back, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Try Again") }
                }
            }
            if (step !is SetUpTvViewModel.Step.Sent && step !is SetUpTvViewModel.Step.Sending) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step !is SetUpTvViewModel.Step.Pick) OutlinedButton(onClick = vm::back, modifier = Modifier.weight(1f)) { Text("Back") }
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PickStep(tvs: List<SetupTarget>, onPick: (SetupTarget) -> Unit) {
    Text(
        "On the TV, open AerioTV and choose Set up from your phone. Then pick the TV below, or scan the code on the TV with your phone's camera.",
        style = MaterialTheme.typography.bodyMedium.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (tvs.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Looking for TVs on this network", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    tvs.forEach { t ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .clickable { onPick(t) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text("Check that the TV shows ${t.fingerprint}", style = MaterialTheme.typography.bodySmall.subtext(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(
        "Scan QR code: open your phone's camera and point it at the code on the TV. AerioTV opens here with that TV selected.",
        style = MaterialTheme.typography.bodySmall.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CodeStep(s: SetUpTvViewModel.Step.Code, onSubmit: (SetupTarget, String) -> Unit) {
    var code by remember(s.target.key) { mutableStateOf("") }
    Text("Enter the code shown on ${s.target.name}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        label = { Text("6-digit code") },
        singleLine = true,
        isError = s.error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Button(onClick = { onSubmit(s.target, code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Continue") }
}

@Composable
private fun ChooseStep(
    s: SetUpTvViewModel.Step.Choose,
    source: SetupSender.Source?,
    onSend: (SetupTarget, String, Set<String>, Boolean) -> Unit,
) {
    if (source == null) {
        Text("There is no playlist on this device to send.", color = MaterialTheme.colorScheme.error)
        return
    }
    val picked = remember(source) {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            addAll(source.choices.filter { (it.isDefault || it.isCurrent) && it.credentialLabel != null }.map { it.profileId })
        }
    }
    var sync by remember(source) { mutableStateOf(source.isDispatcharr && source.syncOnHere) }
    Text("Send to ${s.target.name}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Text("Playlist: ${source.playlist.name}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    if (source.isDispatcharr && source.choices.isNotEmpty()) {
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
                            else -> "No saved sign-in on this device. Add it on the TV later."
                        },
                        style = MaterialTheme.typography.bodySmall.subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable { sync = !sync }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = sync, onCheckedChange = null)
            Text("Turn on Dispatcharr Sync on the TV", modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onBackground)
        }
    }
    Spacer(Modifier.heightIn(min = 4.dp))
    Button(onClick = { onSend(s.target, s.code, picked.toSet(), sync) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Send") }
    Text(
        "Sent only to this TV over your network, encrypted with its code. Nothing goes through your server.",
        style = MaterialTheme.typography.bodySmall.subtext(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
