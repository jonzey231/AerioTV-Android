package com.aeriotv.android.feature.onboarding

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.profiles.ProfileRole
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Onboarding "Who is this profile for?" (Dispatcharr), right after the
 * login succeeds. Shown only when the server user is a Standard user (not
 * admin, staff, or superuser); an admin login stays the local Admin as
 * before. User keeps today's behavior. Child sets a Device Admin PIN right
 * here (device-local, never synced), then the profile becomes a Child: a
 * kid-only device whose Admin is the device PIN.
 */
@HiltViewModel
class ProfileRoleStepViewModel @Inject constructor(
    private val client: DispatcharrClient,
    private val repository: PlaylistRepository,
) : ViewModel() {
    /** True when the login is a Standard user (read live from /me/). */
    suspend fun isStandardUser(playlistId: String): Boolean = runCatching {
        val row = repository.playlistById(playlistId) ?: return false
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: return false
        val me = client.fetchMe(repository.effectiveBaseUrl(row), key) ?: return false
        val admin = (me.userLevel ?: 0) >= 10 || me.isSuperuser || me.isStaff
        Log.i("AerioProfiles", "[PROFILE] onboarding role step: ${me.username} level=${me.userLevel} admin=$admin")
        !admin
    }.getOrDefault(false)
}

@Composable
fun ProfileRoleStepScreen(
    playlistId: String,
    onDone: (child: Boolean) -> Unit,
    vm: ProfileRoleStepViewModel = hiltViewModel(),
) {
    val profilesVm: com.aeriotv.android.feature.settings.ProfilesViewModel = hiltViewModel()
    var standard by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(playlistId) {
        val s = vm.isStandardUser(playlistId)
        if (!s) onDone(false) else standard = true
    }
    if (standard != true) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var choice by remember { mutableStateOf(ProfileRole.User) }
    var settingPin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ProfileRoleStepContent(
        choice = choice,
        onChoice = { choice = it },
        error = error,
        onContinue = {
            error = null
            if (choice == ProfileRole.Child) {
                if (profilesVm.hasDeviceAdminPin(playlistId)) {
                    profilesVm.makeBaseChild(playlistId).fold({ onDone(true) }, { error = it.message })
                } else settingPin = true
            } else onDone(false)
        },
    )
    if (settingPin) {
        com.aeriotv.android.feature.settings.DeviceAdminPinSetFlow(playlistId, profilesVm, onboarding = true) { err ->
            settingPin = false
            if (err != null) { error = err; return@DeviceAdminPinSetFlow }
            if (!profilesVm.hasDeviceAdminPin(playlistId)) return@DeviceAdminPinSetFlow // cancelled
            profilesVm.makeBaseChild(playlistId).fold({ onDone(true) }, { error = it.message })
        }
    }
}

/**
 * The step's page. TV: Apple TV's ProfileRoleStepView at half scale on the
 * 960 x 540 dp canvas (900 pt column, 20 pt gaps, 80 / 60 pt padding, the
 * 60 pt Continue pill), with the first choice focused as tvOS focuses the
 * topmost control. The phone 640 dp column and 54 dp button filled two
 * thirds of a 1080p TV. On TV the block is centered with the same space
 * above and below; the scroll keeps the screen height as the minimum, so
 * that only applies when it fits (Logan, 2026-11-11).
 */
@Composable
internal fun ProfileRoleStepContent(
    choice: ProfileRole,
    onChoice: (ProfileRole) -> Unit,
    error: String?,
    onContinue: () -> Unit,
) {
    val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
    val isTv = com.aeriotv.android.ui.settings.rememberIsTvDevice()
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (isTv) LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val pageScroll = rememberScrollState()
    // TV: focus back on a control near the top scrolls the page to its top.
    com.aeriotv.android.ui.tv.TvTopSnapPage(pageScroll, enabled = isTv) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(pageScroll)
            .padding(horizontal = vp.gutter, vertical = if (isTv) 30.dp else 24.dp),
        contentAlignment = if (isTv) Alignment.Center else Alignment.TopCenter,
    ) {
        Inside {
        Column(
            Modifier.widthIn(max = if (isTv) 450.dp else 640.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Who is this profile for?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                com.aeriotv.android.core.profiles.PinCopy.ONBOARDING_ROLE,
                style = MaterialTheme.typography.bodyMedium.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            listOf(ProfileRole.User, ProfileRole.Child).forEachIndexed { index, r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .dpadFocusRing(RoundedCornerShape(12.dp), washTint = MaterialTheme.colorScheme.primary)
                        .selectable(selected = r == choice, role = Role.RadioButton) { onChoice(r) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = r == choice, onClick = null)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            com.aeriotv.android.feature.settings.roleDescription(r),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (isTv) com.aeriotv.android.feature.onboarding.components.TvOnboardingPillButton(
                label = "Continue",
                filled = true,
                onClick = onContinue,
            )
            else Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50), onFilled = true),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text("Continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        }
        }
    }
    }
}
