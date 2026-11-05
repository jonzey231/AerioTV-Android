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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.platform.LocalContext
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
import com.aeriotv.android.R
import com.aeriotv.android.core.tv.qrCodeBitmap
import com.aeriotv.android.feature.settings.PinEntryDialog
import com.aeriotv.android.feature.settings.pinCheckMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * "Show User QR Code" (docs/DeviceSetupHandoff.md): shows a
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val prefs: com.aeriotv.android.core.preferences.AppPreferences,
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

    /**
     * While the code is on screen, a scanner on this network may ask for this
     * device's whole setup (docs/DeviceSetupHandoff.md). Sends the same full
     * payload as the banner flow's "Copy entire setup from this device".
     */
    fun source(t: Target, text: String): com.aeriotv.android.core.setup.UserQrSource? {
        val key = com.aeriotv.android.core.qr.ScannedCode.parseUserKey(text)?.apiKey ?: return null
        val sender = com.aeriotv.android.core.setup.SetupSender(manager, prefs)
        return com.aeriotv.android.core.setup.UserQrSource(
            context = appContext,
            apiKey = key,
            canSend = { manager.canSetUpTv(t.playlistId) },
            send = send@{ link ->
                val src = sender.source(t.playlistId)
                    ?: return@send com.aeriotv.android.core.setup.SetupSender.SendResult.Failed("This playlist could not be read.")
                val ids = src.choices.filter { !it.isDefault && it.credentialLabel != null }.map { it.profileId }.toSet()
                val payload = sender.buildPayload(t.playlistId, ids, turnOnSync = src.isDispatcharr && src.syncOnHere)
                    ?: return@send com.aeriotv.android.core.setup.SetupSender.SendResult.Failed("This playlist could not be read.")
                val target = com.aeriotv.android.core.setup.SetupTarget(link.name.ifBlank { "AerioTV" }, link.ip, link.port, link.pk, code = link.code)
                sender.send(target, link.code, payload)
            },
        )
    }
}

const val SHOW_USER_QR_LABEL = "Show User QR Code"

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
        val src = remember(text) { text?.let { vm.source(t, it) } }
        DisposableEffect(src) {
            onDispose { src?.stop() }
        }
        LaunchedEffect(src) { src?.start() }
        UserQrScreen(text, t.username, src, onClose)
    }
}

@Composable
private fun UserQrScreen(text: String?, username: String, source: com.aeriotv.android.core.setup.UserQrSource?, onClose: () -> Unit) {
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
    val context = LocalContext.current
    val qr = remember(text) { text?.let { qrCodeBitmap(it, 512)?.let { b -> withLogo(context, b) }?.asImageBitmap() } }
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
                "User QR Code",
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
                        contentDescription = "User QR code",
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
            source?.let { SetupRequestCard(it) }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
        }
    }
}

/** Share of the QR image's width the white logo plate covers; level H restores up to 30 percent. Apple matches. */
private const val LOGO_PLATE_FRACTION = 0.20f
/** Logo inset inside the plate, as a share of the plate side. */
private const val LOGO_MARGIN_FRACTION = 0.12f

/**
 * The AerioTV mark centered on a rounded white plate over the code. Same payload and level H;
 * the plate masks the modules under it. Falls back to the plain code if the mark fails to load.
 */
private fun withLogo(context: Context, qr: Bitmap): Bitmap {
    val logo = runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.aerio_logo) }.getOrNull() ?: return qr
    val out = qr.copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(out)
    val side = Math.round(minOf(out.width, out.height) * LOGO_PLATE_FRACTION).toFloat()
    val plate = RectF((out.width - side) / 2f, (out.height - side) / 2f, (out.width + side) / 2f, (out.height + side) / 2f)
    c.drawRoundRect(plate, side * 0.22f, side * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    val m = side * LOGO_MARGIN_FRACTION
    c.drawBitmap(logo, null, RectF(plate.left + m, plate.top + m, plate.right - m, plate.bottom - m), Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

/** A scanner on this network asked for this device's setup (docs/DeviceSetupHandoff.md). */
@Composable
private fun SetupRequestCard(source: com.aeriotv.android.core.setup.UserQrSource) {
    val st by source.state.collectAsStateWithLifecycle()
    if (st == com.aeriotv.android.core.setup.UserQrSource.State.Idle) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = st) {
            com.aeriotv.android.core.setup.UserQrSource.State.Idle -> Unit
            is com.aeriotv.android.core.setup.UserQrSource.State.Confirm -> {
                Text("Send setup to ${s.name}?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("This copies this device's playlist, profiles, and preferences to ${s.name}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { source.answer(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Send") }
                OutlinedButton(onClick = { source.answer(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Don't send") }
            }
            is com.aeriotv.android.core.setup.UserQrSource.State.Sending -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Sending setup to ${s.name}", color = MaterialTheme.colorScheme.onSurface)
            }
            is com.aeriotv.android.core.setup.UserQrSource.State.Sent ->
                Text("Setup sent to ${s.name}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            is com.aeriotv.android.core.setup.UserQrSource.State.Failed ->
                Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
    }
}
