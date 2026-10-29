package com.aeriotv.android.feature.onboarding

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.profiles.ProfileScope
import com.aeriotv.android.core.sync.DriveSyncManager
import com.aeriotv.android.core.sync.SyncProviderId
import com.aeriotv.android.core.sync.SyncProvidersRules
import com.aeriotv.android.core.sync.SyncProvidersStore
import com.aeriotv.android.core.sync.dsync.DispatcharrSyncManager
import com.aeriotv.android.feature.settings.SyncSettingsViewModel
import com.aeriotv.android.ui.settings.SettingsAlertDialog
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Schema section 10b: after onboarding's first Drive restore or first
 * Dispatcharr Sync pull (base profile only), offer ONCE per playlist the
 * providers the owner's other devices use. iCloud is never offered here.
 */
@HiltViewModel
class SyncProvidersOfferViewModel @Inject constructor(
    private val store: SyncProvidersStore,
    private val prefs: AppPreferences,
    private val playlistDao: PlaylistDao,
) : ViewModel() {
    val armed = store.armed

    data class Offer(val playlistId: String, val providers: List<SyncProviderId>)

    /** Null = nothing to offer (disarms). The owner's records only: Drive and the base user's Dispatcharr key. */
    suspend fun evaluate(): Offer? {
        if (ProfileScope.token.value.isNotEmpty()) return null // a sub-profile is active: never offer
        val active = playlistDao.allOnce().firstOrNull { it.isActive } ?: return null
        val offers = if (store.offerShown(active.id)) emptyList() else {
            val isD = active.isDispatcharrDirectConnect()
            // The onboarding restore already asked about Dispatcharr Sync for this playlist.
            val offerD = isD && !com.aeriotv.android.feature.onboarding.components.RestoreSyncStep.wasShown(active.id)
            val drive = store.record(SyncProvidersStore.DRIVE)
            val dsync = if (isD) store.record(SyncProvidersStore.dsyncScope(active.id)) else null
            // Either copy saying "in use" counts; both describe the same owner.
            val union = if (drive == null && dsync == null) null else com.aeriotv.android.core.sync.SyncProvidersRecord(
                dispatcharr = drive?.dispatcharr == true || dsync?.dispatcharr == true,
                googleDrive = drive?.googleDrive == true || dsync?.googleDrive == true,
            )
            SyncProvidersRules.offers(
                union,
                driveOnHere = prefs.syncMasterEnabled.first(),
                dispatcharrOnHere = prefs.dispatcharrSyncEnabledOnce(),
                activeIsDispatcharr = offerD,
            )
        }
        store.disarm()
        if (offers.isEmpty()) return null
        store.markOfferShown(active.id)
        Log.i(TAG, "[SYNC] offer providers=${offers.map { it.wire }}")
        return Offer(active.id, offers)
    }

    /** Dispatcharr: turn it on for the active user, leaving Drive as it is. */
    suspend fun enableDispatcharr() {
        prefs.setDispatcharrSyncEnabled(true)
        Log.i(TAG, "[DSYNC] enabled from sync providers offer")
    }

    private companion object { const val TAG = "AerioSync" }
}

@Composable
fun SyncProvidersOfferHost(
    syncVm: SyncSettingsViewModel,
    vm: SyncProvidersOfferViewModel = hiltViewModel(),
) {
    val armed by vm.armed.collectAsStateWithLifecycle()
    var offer by remember { mutableStateOf<SyncProvidersOfferViewModel.Offer?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(armed) { if (armed && offer == null) offer = vm.evaluate() }

    val consent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        scope.launch {
            syncVm.acceptConsentResultNow(result.data)
            syncVm.setMasterEnabled(true)
        }
    }

    val o = offer ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(o) { runCatching { focus.requestFocus() } }
    SettingsAlertDialog(
        onDismissRequest = { offer = null },
        title = { Text("Keep your devices in sync") },
        text = { Text(SyncProvidersRules.offerText(o.providers)) },
        confirmButton = {
            Box(Modifier.focusRequester(focus)) {
                SettingsDialogTextButton(label = "Turn on", onClick = {
                    offer = null
                    scope.launch {
                        if (SyncProviderId.Dispatcharr in o.providers) vm.enableDispatcharr()
                        if (SyncProviderId.GoogleDrive in o.providers) {
                            // Same path as the Welcome Google card: sign in, Drive scope, then setMasterEnabled.
                            val activity = context.findActivity() ?: return@launch
                            if (syncVm.signInWithGoogle(activity) == null) return@launch
                            when (val r = syncVm.requestDriveScope()) {
                                is DriveSyncManager.RequestResult.Authorized -> syncVm.setMasterEnabled(true)
                                is DriveSyncManager.RequestResult.NeedsConsent -> consent.launch(
                                    androidx.activity.result.IntentSenderRequest.Builder(r.intentSender).build(),
                                )
                                else -> Unit
                            }
                        }
                    }
                })
            }
        },
        dismissButton = { SettingsDialogTextButton(label = "Not now", onClick = { offer = null }) },
    )
}

