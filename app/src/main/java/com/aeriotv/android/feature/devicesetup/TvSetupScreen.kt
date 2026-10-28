package com.aeriotv.android.feature.devicesetup

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.profiles.AvatarSpec
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.setup.SetupProtocol
import com.aeriotv.android.core.setup.SetupReceiver
import com.aeriotv.android.core.sync.DriveSyncWorker
import com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager
import com.aeriotv.android.core.sync.dsync.ProfileRow
import com.aeriotv.android.core.tv.qrCodeBitmap
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * TV receiver for "Set up from your phone" (docs/DeviceSetupHandoff.md):
 * offers a one-time code and key over the LAN, then applies the sealed
 * payload without any typing on the remote.
 */
@HiltViewModel
class TvSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileManager,
    private val prefs: AppPreferences,
    private val dsync: DispatcharrSyncManager,
) : ViewModel() {

    sealed interface Phase {
        data object Starting : Phase
        data class Offering(val offer: SetupReceiver.Offer) : Phase
        data object NoNetwork : Phase
        data object Expired : Phase
        data class Applying(val step: String) : Phase
        data class Failed(val message: String) : Phase
        data object Done : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Starting)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private var receiver: SetupReceiver? = null
    private var timeout: Job? = null
    private var pending: SetupProtocol.Payload? = null
    private var addPlaylist: (suspend (PlaylistRepository.SaveRequest) -> Result<PlaylistEntity>)? = null

    fun bind(add: suspend (PlaylistRepository.SaveRequest) -> Result<PlaylistEntity>) { addPlaylist = add }

    fun start() {
        if (receiver != null) return
        val r = SetupReceiver(context) { payload -> viewModelScope.launch { onPayload(payload) } }
        receiver = r
        viewModelScope.launch {
            if (!r.start()) { receiver = null; _phase.value = Phase.NoNetwork; return@launch }
            r.offer.collect { o -> if (o != null && _phase.value.isOfferish()) _phase.value = Phase.Offering(o) }
        }
        armTimeout()
    }

    private fun Phase.isOfferish() = this is Phase.Starting || this is Phase.Offering

    private fun armTimeout() {
        timeout?.cancel()
        timeout = viewModelScope.launch {
            delay(SetupProtocol.WINDOW_MS)
            if (_phase.value.isOfferish()) {
                stop()
                _phase.value = Phase.Expired
            }
        }
    }

    fun restart() {
        stop()
        _phase.value = Phase.Starting
        start()
    }

    fun stop() {
        timeout?.cancel()
        receiver?.stop()
        receiver = null
    }

    fun retry() {
        val p = pending ?: return restart()
        viewModelScope.launch { onPayload(p) }
    }

    private suspend fun onPayload(p: SetupProtocol.Payload) {
        pending = p
        timeout?.cancel()
        receiver?.stop()
        receiver = null
        val add = addPlaylist ?: run { _phase.value = Phase.Failed("Setup is not ready. Try again."); return }
        _phase.value = Phase.Applying("Connecting to your server")
        val pl = p.playlist
        val cred = pl.credential
        val type = when (pl.type) {
            "dispatcharr" -> if (!cred?.apiKey.isNullOrBlank()) SourceType.DispatcharrApiKey else SourceType.DispatcharrUserPass
            "xtream" -> SourceType.XtreamCodes
            else -> SourceType.M3uUrl
        }
        val isDispatcharr = type == SourceType.DispatcharrApiKey || type == SourceType.DispatcharrUserPass
        val entity = add(
            PlaylistRepository.SaveRequest(
                sourceType = type,
                name = pl.name.ifBlank { null },
                url = pl.url,
                lanUrl = pl.localUrl,
                epgUrl = pl.xmltvUrl,
                apiKey = cred?.apiKey?.ifBlank { null },
                username = cred?.username?.ifBlank { null },
                password = cred?.password?.ifBlank { null },
                dispatcharrProfileId = if (isDispatcharr) pl.channelProfileIds.firstOrNull() else null,
                vodEnabled = pl.vodEnabled,
                epgRetentionDays = pl.guideDays,
                customUserAgent = pl.userAgent?.takeIf { isDispatcharr },
            ),
        ).getOrElse {
            Log.w(TAG, "[SETUP] playlist add failed: ${it.message}")
            _phase.value = Phase.Failed(it.message ?: "Could not connect to your server.")
            return
        }
        if (type == SourceType.XtreamCodes || isDispatcharr) {
            prefs.setCatchupTimeOffsetMinutes(entity.id, pl.catchupOffsetMinutes)
        }
        val problems = mutableListOf<String>()
        if (isDispatcharr && p.profiles.isNotEmpty()) {
            _phase.value = Phase.Applying("Adding profiles")
            // Default first (its PIN may be the Admin PIN a Child row needs), then Admins, then the rest.
            val ordered = p.profiles.sortedWith(
                compareBy({ !it.isDefault }, { it.role == "child" }, { it.order.let { o -> if (o < 0) Int.MAX_VALUE else o } }),
            )
            ordered.forEach { pp ->
                val row = ProfileRow(
                    userId = 0,
                    username = pp.username,
                    displayName = pp.displayName,
                    pinType = pp.pinType,
                    pinHash = pp.pinHash,
                    pinSalt = pp.pinSalt,
                    role = pp.role,
                    avatar = pp.avatar?.let { AvatarSpec(it.icon, it.color) },
                    order = pp.order,
                )
                profiles.applySetupProfile(entity.id, row, pp.isDefault, pp.credential?.password, pp.credential?.apiKey)
                    .onFailure {
                        Log.w(TAG, "[SETUP] profile ${pp.username} not added: ${it.message}")
                        problems += pp.displayName.ifBlank { pp.username }
                    }
            }
        }
        if (isDispatcharr && p.sync?.enabled == true && p.sync.provider == DispatcharrSyncManager.PROVIDER) {
            _phase.value = Phase.Applying("Syncing")
            prefs.setSyncProvider(DispatcharrSyncManager.PROVIDER)
            prefs.setSyncMasterEnabled(false)
            DriveSyncWorker.cancel(context)
            Log.i(TAG, "[DSYNC] enabled from phone setup")
            dsync.awaitFirstPull()
        }
        pending = null
        if (problems.isNotEmpty()) Log.w(TAG, "[SETUP] finished; not added: ${problems.size}")
        Log.i(TAG, "[SETUP] applied (${p.profiles.size - problems.size} profiles)")
        _phase.value = Phase.Done
    }

    override fun onCleared() {
        stop()
    }

    private companion object { const val TAG = "AerioSetup" }
}

@Composable
fun TvSetupScreen(
    addPlaylist: suspend (PlaylistRepository.SaveRequest) -> Result<PlaylistEntity>,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    vm: TvSetupViewModel = hiltViewModel(),
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.bind(addPlaylist); vm.start() }
    DisposableEffect(Unit) { onDispose { vm.stop() } }
    LaunchedEffect(phase) { if (phase is TvSetupViewModel.Phase.Done) onDone() }
    val cancel = { vm.stop(); onCancel() }
    BackHandler(enabled = phase !is TvSetupViewModel.Phase.Applying) { cancel() }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val p = phase) {
            TvSetupViewModel.Phase.Starting -> CircularProgressIndicator()
            is TvSetupViewModel.Phase.Offering -> Offering(p.offer, onCancel = cancel)
            TvSetupViewModel.Phase.NoNetwork -> Message(
                title = "This TV is not on a network",
                body = "Connect this TV to the same network as your phone, then try again.",
                primary = "Try Again" to vm::restart,
                onCancel = cancel,
            )
            TvSetupViewModel.Phase.Expired -> Message(
                title = "The setup code expired",
                body = "Codes last 10 minutes. Get a new code to try again.",
                primary = "New Code" to vm::restart,
                onCancel = cancel,
            )
            is TvSetupViewModel.Phase.Applying -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Text(p.step, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }
            is TvSetupViewModel.Phase.Failed -> Message(
                title = "Setup did not finish",
                body = p.message,
                primary = "Retry" to vm::retry,
                onCancel = cancel,
            )
            TvSetupViewModel.Phase.Done -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun Offering(offer: SetupReceiver.Offer, onCancel: () -> Unit) {
    val qr = remember(offer.link) { qrCodeBitmap(offer.link, 512)?.asImageBitmap() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(280.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) Image(bitmap = qr, contentDescription = "Setup QR code", modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.widthIn(max = 460.dp)) {
            Text("Set up from your phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(
                "Open AerioTV on your phone, then Settings > Playlists > Set up a TV, or scan this code.",
                style = MaterialTheme.typography.bodyLarge.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                offer.code.chunked(3).joinToString(" "),
                fontSize = 56.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text("Key ${offer.fingerprint}", fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(offer.deviceName, style = MaterialTheme.typography.bodyMedium.subtext(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.focusRequester(focus).secondaryButtonFrame(),
            ) { Text("Cancel", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun Message(title: String, body: String, primary: Pair<String, () -> Unit>, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { focus.requestFocus() } }
    Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge.subtext(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = primary.second, modifier = Modifier.focusRequester(focus).dpadFocusRing(RoundedCornerShape(50))) { Text(primary.first) }
            OutlinedButton(onClick = onCancel, modifier = Modifier.secondaryButtonFrame()) { Text("Cancel", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/**
 * Secondary outlined button frame, as Skip on the Dispatcharr Sync step: a
 * 54 dp tall pill so the focus ring sits exactly on the button's own outline
 * (a wrap-content OutlinedButton is drawn smaller than its 48 dp touch box,
 * which left the ring floating outside the outline as a double border), plus
 * the accent wash on focus.
 */
@Composable
private fun Modifier.secondaryButtonFrame(): Modifier = this
    .widthIn(min = 220.dp)
    .heightIn(min = 54.dp)
    .dpadFocusRing(RoundedCornerShape(50), washTint = MaterialTheme.colorScheme.primary)
