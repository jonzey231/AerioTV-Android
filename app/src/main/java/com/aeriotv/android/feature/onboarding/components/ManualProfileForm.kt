package com.aeriotv.android.feature.onboarding.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.unit.dp
import com.aeriotv.android.feature.onboarding.DispatcharrAuthMode
import com.aeriotv.android.feature.onboarding.SegmentedAuthControl
import com.aeriotv.android.feature.onboarding.LabeledField
import com.aeriotv.android.feature.onboarding.IconTextField
import com.aeriotv.android.feature.onboarding.PasswordField
import com.aeriotv.android.ui.textfield.SecretRevealIconButton
import com.aeriotv.android.ui.textfield.rememberSecretRevealState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.Person
import com.aeriotv.android.ui.settings.dpadFocusRing
import kotlinx.coroutines.launch

/**
 * The form's Add button, run from the dialog's own button row instead (Add
 * Profile on TV, matching Apple: Cancel and Add Profile sit together below
 * the scrolling form, so they are always on screen). With one passed in, the
 * form draws no button of its own; [submit] does what that button did.
 */
@androidx.compose.runtime.Stable
class ManualProfileSubmit {
    internal val busyState = mutableStateOf(false)
    internal val readyState = mutableStateOf(false)
    internal var action: () -> Unit = {}

    /** A sign-in is running. */
    val busy: Boolean get() = busyState.value

    /** The fields hold what a sign-in needs (see [ManualProfileFormRules.ready]). */
    val ready: Boolean get() = readyState.value

    fun submit() = action()
}

object ManualProfileFormRules {
    /** Username and password both filled in, or a key. */
    fun ready(isPassword: Boolean, username: String, secret: String): Boolean =
        secret.isNotBlank() && (!isPassword || username.isNotBlank())
}

/**
 * Manual Add Profile (the base login is not a Dispatcharr admin, so the
 * users list is not readable). The connect form's toggle comes first:
 * Username & Password shows Username and Password; API Key shows only the
 * key, and the server's /me/ answer names the user. [onAdd] gets
 * (username, password, null) or ("", null, apiKey), signs in once, and
 * returns an error message, or null on success (the fields then clear).
 */
@Composable
fun ManualProfileForm(
    onAdd: suspend (username: String, password: String?, apiKey: String?) -> String?,
    modifier: Modifier = Modifier,
    /** Scan / Photos / Import file buttons above the fields (Settings); onboarding has its own card. */
    showQr: Boolean = true,
    /** Onboarding: a small "Use an API key instead" link in place of the segmented control. */
    modeAsLink: Boolean = false,
    /** Drawn between the fields and the Add button (onboarding Role choice). */
    beforeSubmit: @Composable () -> Unit = {},
    submitLabel: String = "Add Profile",
    /** Focus target for the first field (TV hand-off when the form opens). */
    firstFieldModifier: Modifier = Modifier,
    /** The gap between the form's parts (Add Profile on TV passes a tighter one). */
    spacing: androidx.compose.ui.unit.Dp = 8.dp,
    /** The dialog's button row runs the Add instead of a button in the form (Add Profile on TV). */
    submit: ManualProfileSubmit? = null,
) {
    var username by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DispatcharrAuthMode.UsernamePassword) }
    var secret by remember { mutableStateOf("") }
    val ownBusy = remember { mutableStateOf(false) }
    var busy by (submit?.busyState ?: ownBusy)
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isPassword = mode == DispatcharrAuthMode.UsernamePassword
    var scannedUser by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        val selectMode: (DispatcharrAuthMode) -> Unit = { mode = it; username = ""; secret = ""; error = null; scannedUser = null }
        if (!modeAsLink) SegmentedAuthControl(selected = mode, onSelect = selectMode, enabled = !busy)
        // Dispatcharr user QR (phones and tablets): the key signs in the same
        // way as a pasted key. The username in the code is display only.
        if (showQr) com.aeriotv.android.feature.qrscan.QrScanActions(
            enabled = !busy,
            onResult = { r ->
                when (r) {
                    is com.aeriotv.android.core.qr.ScannedCode.Result.UserKey -> {
                        mode = DispatcharrAuthMode.ApiKey; username = ""; secret = r.apiKey
                        scannedUser = r.username; error = null
                        if (!busy) {
                            busy = true
                            scope.launch {
                                error = onAdd("", null, r.apiKey)
                                busy = false
                                if (error == null) { secret = ""; scannedUser = null }
                            }
                        }
                    }
                    is com.aeriotv.android.core.qr.ScannedCode.Result.SetupLink ->
                        com.aeriotv.android.feature.qrscan.openSetupLink(context, r.link)
                    com.aeriotv.android.core.qr.ScannedCode.Result.Invalid -> Unit
                }
            },
        )
        scannedUser?.let { Text("Signed in as $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (!modeAsLink) Text(
            if (isPassword) "Sign in as that Dispatcharr user once." else "The API key identifies the Dispatcharr user.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Same fields as the Dispatcharr connect form (label above, leading
        // icon, placeholder, reveal eye).
        if (isPassword) androidx.compose.foundation.layout.Box(firstFieldModifier.then(Modifier.focusGroup())) {
            LabeledField(label = "Username") {
                IconTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    placeholder = "Dispatcharr username",
                    leading = Icons.Outlined.Person,
                    enabled = !busy,
                )
            }
        }
        if (isPassword) {
            PasswordField(
                label = "Password",
                value = secret,
                onValueChange = { secret = it; error = null },
                placeholder = "Dispatcharr password",
                enabled = !busy,
            )
        } else {
            androidx.compose.foundation.layout.Box(firstFieldModifier.then(Modifier.focusGroup())) {
            LabeledField(label = "API Key") {
                val reveal = rememberSecretRevealState()
                IconTextField(
                    value = secret,
                    onValueChange = { secret = it; error = null },
                    placeholder = "Paste the user's API key",
                    leading = Icons.Filled.Key,
                    visualTransformation = reveal.transformation,
                    trailing = { SecretRevealIconButton(state = reveal, iconSize = 18.dp, contentLabel = "API key") },
                    trailingFocused = { reveal.controlFocused },
                    enabled = !busy,
                )
            }
            }
        }
        if (modeAsLink) {
            androidx.compose.material3.TextButton(
                onClick = { selectMode(if (isPassword) DispatcharrAuthMode.ApiKey else DispatcharrAuthMode.UsernamePassword) },
                enabled = !busy,
                modifier = Modifier.heightIn(min = 40.dp).dpadFocusRing(RoundedCornerShape(50)),
            ) { Text(if (isPassword) "Use an API key instead" else "Use a username and password instead", style = MaterialTheme.typography.bodyMedium) }
        }
        beforeSubmit()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val submitNow: () -> Unit = submitNow@{
            if (busy || !ManualProfileFormRules.ready(isPassword, username, secret)) return@submitNow
            busy = true; error = null
            scope.launch {
                error = if (isPassword) onAdd(username.trim(), secret, null) else onAdd("", null, secret.trim())
                busy = false
                if (error == null) { username = ""; secret = "" }
            }
        }
        if (submit != null) {
            val ready = ManualProfileFormRules.ready(isPassword, username, secret)
            androidx.compose.runtime.SideEffect {
                submit.action = submitNow
                submit.readyState.value = ready
            }
        } else {
            // Never disabled mid-flow (TV focus); taps are ignored while busy.
            Button(
                onClick = submitNow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).dpadFocusRing(RoundedCornerShape(50), onFilled = true),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text(submitLabel)
            }
        }
    }
}
