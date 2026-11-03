package com.aeriotv.android.feature.qrscan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.profiles.PinType
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.tv.qrCodeBitmap
import com.aeriotv.android.feature.settings.PinEntryDialog
import com.aeriotv.android.feature.settings.pinCheckMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * "Show Dispatcharr User QR Code" (docs/DeviceSetupHandoff.md): shows a
 * profile's Dispatcharr user sign-in as the same `user_api_key` JSON QR the
 * Dispatcharr web UI draws, so another device's Scan QR code or Choose from
 * Photos accepts it unchanged.
 *
 * Guarded: that profile's PIN or the Device Admin PIN first when either is
 * set; the code is blurred until tapped, hides again after 60 seconds or when
 * the app leaves the foreground, and the dialog window is FLAG_SECURE (no
 * screenshots or recordings). The text is never logged, copied, shared, or
 * saved.
 */
@HiltViewModel
class UserQrViewModel @Inject constructor(
    private val manager: ProfileManager,
    private val playlistDao: PlaylistDao,
) : ViewModel() {
    data class Target(val playlistId: String, val profileId: String, val username: String)

    /** The active playlist's active profile, when it has an API key saved here. */
    suspend fun activeTarget(): Target? {
        val pid = playlistDao.observeActive().first().firstOrNull()?.id ?: return null
        val p = manager.activeProfile(pid) ?: return null
        return target(pid, p.id)
    }

    suspend fun target(playlistId: String, profileId: String): Target? {
        manager.userQrText(playlistId, profileId) ?: return null
        val p = manager.profiles(playlistId).first().firstOrNull { it.id == profileId } ?: return null
        return Target(playlistId, profileId, p.username)
    }

    suspend fun text(t: Target): String? = manager.userQrText(t.playlistId, t.profileId)
    fun needsPin(t: Target) = manager.userQrNeedsPin(t.playlistId, t.profileId)
    fun pinType(t: Target) = manager.userQrPinType(t.playlistId, t.profileId)
    fun verify(t: Target, entries: List<String>) = manager.verifyUserQrPin(t.playlistId, t.profileId, entries)
}

const val SHOW_USER_QR_LABEL = "Show Dispatcharr User QR Code"

/**
 * The button beside Scan QR code and Choose from Photos. Hidden when the
 * active profile has no Dispatcharr API key saved on this device.
 */
@Composable
fun ShowUserQrButton(linksOnly: Boolean, enabled: Boolean, modifier: Modifier = Modifier, vm: UserQrViewModel = hiltViewModel()) {
    val target by produceState<UserQrViewModel.Target?>(null) { value = vm.activeTarget() }
    val t = target ?: return
    var open by remember { mutableStateOf(false) }
    if (linksOnly) {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = modifier) {
            Text(SHOW_USER_QR_LABEL, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(SHOW_USER_QR_LABEL)
        }
    }
    if (open) UserQrFlow(t, vm, onClose = { open = false })
}

/** Roster entry point: an Admin shows another profile's code, same gating. */
@Composable
fun UserQrFlow(playlistId: String, profileId: String, onClose: () -> Unit, vm: UserQrViewModel = hiltViewModel()) {
    val target by produceState<UserQrViewModel.Target?>(null, playlistId, profileId) { value = vm.target(playlistId, profileId) }
    target?.let { UserQrFlow(it, vm, onClose) }
}

@Composable
private fun UserQrFlow(t: UserQrViewModel.Target, vm: UserQrViewModel, onClose: () -> Unit) {
    var unlocked by remember(t) { mutableStateOf(!vm.needsPin(t)) }
    if (!unlocked) {
        PinEntryDialog(
            title = "Enter the PIN to show this code",
            type = vm.pinType(t) ?: PinType.Numbers,
            onDismiss = onClose,
            onEntered = { seq ->
                val r = vm.verify(t, seq)
                if (r == ProfileManager.PinCheck.Ok) { unlocked = true; null } else pinCheckMessage(r)
            },
        )
        return
    }
    val text by produceState<String?>(null, t) { value = vm.text(t) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        UserQrScreen(text, t.username, onClose)
    }
}

@Composable
private fun UserQrScreen(text: String?, username: String, onClose: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) { if (revealed) { delay(60_000L); revealed = false } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_STOP) revealed = false
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val qr = remember(text) { text?.let { qrCodeBitmap(it, 512)?.asImageBitmap() } }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Dispatcharr User QR Code",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Box(
                Modifier.size(260.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .clickable { revealed = !revealed }.padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "Dispatcharr user QR code",
                        modifier = Modifier.fillMaxSize().then(if (revealed) Modifier else Modifier.blur(24.dp)),
                    )
                }
                if (!revealed) {
                    // blur() needs Android 12; the near-opaque wash hides the code on older releases too.
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                        Text("Tap to show", color = Color.Black, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Text(
                "Anyone who scans this code can sign in as $username. It hides after 60 seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
        }
    }
}
