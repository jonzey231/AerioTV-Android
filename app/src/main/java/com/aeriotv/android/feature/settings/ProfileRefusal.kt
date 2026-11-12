package com.aeriotv.android.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * A refused profile action (Logan, device testing 2026-11-12): the reason
 * shows in a dialog instead of small red text under the Profiles list, where
 * it was missed. [title] is the action's existing title ("Role", "Reset PIN",
 * and so on; null where the action has none), [reason] the existing reason
 * string word for word, and the only button is "OK". No other new copy.
 */
data class ProfileRefusal(val title: String?, val reason: String) {
    companion object {
        const val OK = "OK"

        /** The dialog for a failed [title] action, or null when it went through ([reason] null or blank). */
        fun of(title: String?, reason: String?): ProfileRefusal? =
            reason?.takeIf { it.isNotBlank() }?.let { ProfileRefusal(title, it) }
    }
}

/** The refusal dialog: the existing Settings dialog style, the reason, and OK. */
@Composable
fun ProfileRefusalDialog(refusal: ProfileRefusal, onDismiss: () -> Unit) {
    ProfileAlertDialog(
        onDismissRequest = onDismiss,
        // OK only: a tap outside does nothing.
        outsideTapCancels = false,
        title = refusal.title?.let { t -> { Text(t) } },
        text = { Text(refusal.reason) },
        confirmButton = { com.aeriotv.android.ui.settings.SettingsDialogTextButton(label = ProfileRefusal.OK, onClick = onDismiss, emphasized = true) },
    )
}
