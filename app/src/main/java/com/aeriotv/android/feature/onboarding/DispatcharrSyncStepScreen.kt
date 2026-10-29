package com.aeriotv.android.feature.onboarding

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.sync.DriveSyncWorker
import com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dispatcharr Direct Connect only, after the "Add profiles for other users?"
 * step (shown, skipped, or auto-skipped): "Turn on Dispatcharr Sync?".
 * Turn On picks the same provider as Settings > Sync and waits for the first
 * pull so the guide opens with restored data. Skip leaves the provider alone.
 */
@HiltViewModel
class DispatcharrSyncStepViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val dsync: DispatcharrSyncManager,
    private val client: com.aeriotv.android.core.network.DispatcharrClient,
    private val providersStore: com.aeriotv.android.core.sync.SyncProvidersStore,
) : ViewModel() {

    /**
     * After a Dispatcharr user QR sign-in: does this user already have AerioTV
     * sync data on the server? One call to the existing App Data list
     * endpoint with the scanned key. Any failure counts as "none".
     */
    suspend fun serverHasData(signIn: com.aeriotv.android.core.qr.QrImportBus.SignIn): Boolean {
        val r = runCatching { client.appDataList(signIn.baseUrl, signIn.apiKey) }.getOrNull()
        val found = r?.ok == true && com.aeriotv.android.core.qr.DsyncRestoreProbe.listsMainKey(r.body)
        val who = signIn.username ?: "this user"
        Log.i(TAG, if (found) "[DSYNC] server data found for $who, offering restore" else "[DSYNC] server data none for $who")
        return found
    }

    suspend fun alreadyOn(): Boolean = prefs.dispatcharrSyncEnabledOnce()

    /** Turns Dispatcharr Sync on; Drive Sync is independent and stays as it is. */
    suspend fun turnOn(fromCard: Boolean = false, where: String = "onboarding") {
        prefs.setDispatcharrSyncEnabled(true)
        Log.i(TAG, if (fromCard) "[DSYNC] enabled from $where (sync card)" else "[DSYNC] enabled from $where")
        val ok = dsync.awaitFirstPull()
        Log.i(TAG, "[DSYNC] $where first pull ${if (ok) "done" else "not finished; continuing"}")
        // Onboarding first pull for the base user: evaluate the providers offer once in Main.
        if (where == "onboarding" && com.aeriotv.android.core.profiles.ProfileScope.token.value.isEmpty()) providersStore.arm()
    }

    private companion object { const val TAG = "AerioDsync" }
}

@Composable
fun DispatcharrSyncStepScreen(
    /** Came from the Welcome "Sync via Dispatcharr" card: turn on without asking. */
    autoEnable: Boolean = false,
    /** Set when the playlist was just signed in from a Dispatcharr user QR. */
    qrSignIn: com.aeriotv.android.core.qr.QrImportBus.SignIn? = null,
    onDone: () -> Unit,
    vm: DispatcharrSyncStepViewModel = hiltViewModel(),
) {
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Server already holds this user's AerioTV data: lead with the restore
    // copy and put focus on Turn On.
    var restore by remember { mutableStateOf(false) }
    val turnOnFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Already on (e.g. a second Dispatcharr playlist): nothing to ask.
        when {
            vm.alreadyOn() -> onDone()
            autoEnable -> { vm.turnOn(fromCard = true); onDone() }
            else -> {
                restore = qrSignIn != null && vm.serverHasData(qrSignIn)
                ready = true
            }
        }
    }
    LaunchedEffect(ready, restore) { if (ready && restore) runCatching { turnOnFocus.requestFocus() } }
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vp.gutter, vertical = 24.dp),
        // Centered like the Welcome and Connect steps before it (Logan, 2026-10-29).
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = if (vp.onboardingMaxWidth != androidx.compose.ui.unit.Dp.Unspecified)
                Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth()
            else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Turn on Dispatcharr Sync?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (restore) {
                Text(
                    QR_RESTORE_COPY,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "Your favorites, watch progress, reminders, and preferences are stored on your " +
                    "Dispatcharr server under each profile and follow you to every device. " +
                    "Admins on the server can view this data.",
                style = MaterialTheme.typography.bodyMedium.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            // Buttons are never disabled mid-flow (TV focus would fall away);
            // taps are ignored while busy instead.
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        vm.turnOn()
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    .focusRequester(turnOnFocus)
                    .dpadFocusRing(RoundedCornerShape(50), onFilled = true),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text("Turn On", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { if (!busy) onDone() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50)),
            ) { Text("Skip", style = MaterialTheme.typography.titleMedium) }
            Text(
                "You can change this later in Settings > Sync.",
                style = MaterialTheme.typography.bodySmall.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal const val QR_RESTORE_COPY = "Restore your favorites, progress, and settings from your server."

/**
 * Settings > Playlists > Add Playlist, when the Dispatcharr playlist was added
 * from a user QR: if the server already holds this user's AerioTV data, offer
 * Dispatcharr Sync in one tap. Shows nothing when there is no data or sync is
 * already on; [onClose] runs either way.
 */
@Composable
fun QrRestoreOfferDialog(
    signIn: com.aeriotv.android.core.qr.QrImportBus.SignIn,
    onClose: () -> Unit,
    vm: DispatcharrSyncStepViewModel = hiltViewModel(),
) {
    var show by remember(signIn) { mutableStateOf(false) }
    var busy by remember(signIn) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(signIn) {
        if (vm.alreadyOn() || !vm.serverHasData(signIn)) onClose() else show = true
    }
    if (!show) return
    com.aeriotv.android.ui.settings.SettingsAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Turn on Dispatcharr Sync?") },
        text = { Text(QR_RESTORE_COPY) },
        confirmButton = {
            com.aeriotv.android.ui.settings.SettingsDialogTextButton(
                label = if (busy) "Turning On" else "Turn On",
                onClick = {
                    if (busy) return@SettingsDialogTextButton
                    busy = true
                    scope.launch { vm.turnOn(where = "add playlist QR restore"); onClose() }
                },
            )
        },
        dismissButton = {
            com.aeriotv.android.ui.settings.SettingsDialogTextButton(label = "Not Now", onClick = { if (!busy) onClose() })
        },
    )
}
