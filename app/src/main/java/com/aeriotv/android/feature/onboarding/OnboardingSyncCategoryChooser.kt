package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aeriotv.android.core.sync.SyncCategory
import com.aeriotv.android.ui.settings.SettingsToggleRow

/**
 * Shown the moment the user asks to sign into Drive during onboarding, BEFORE
 * sign-in runs and therefore before anything is pulled.
 *
 * Discord (Glitzbr, reported on Apple): he enabled sync on a second TV and a
 * remote button map customised for a different remote came down with the rest.
 * The per-category toggles already existed - but they live in Settings, and by
 * the time you go looking the data has landed. Logan 2026-08-10: ask during
 * setup, while the answer still changes the outcome. Apple's WelcomeView has
 * the same sheet.
 *
 * Everything defaults ON, so the common case is one press. Confirming writes
 * the same `syncCategory.<suffix>` keys the Sync settings screen binds to, so
 * there is one source of truth and the choice is visible and reversible later
 * in the place users expect.
 *
 * TV FOCUS: the first cut used a Material3 AlertDialog with bare Switch rows.
 * On the Streamer that rendered correctly and was completely unusable - D-pad
 * Down scrolled the content but nothing ever showed a focus ring, so a remote
 * user could not tell what was selected or reach Continue. This is the same
 * class of trap as [[feedback_compose_tv_focus_gotchas]]: it compiled, it
 * looked right in a screenshot, and only the device showed it was dead.
 *
 * Rebuilt on the components that already handle TV focus everywhere else in
 * the app: WhatsNewSheet's Dialog + Surface shape, and SettingsToggleRow for
 * each row (the same row the Sync settings screen uses, which draws a proper
 * focus treatment). Initial focus is requested onto the first row so the panel
 * opens with something clearly selected.
 */
@Composable
fun OnboardingSyncCategoryChooser(
    onConfirm: (Map<SyncCategory, Boolean>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local until Confirm, so backing out cannot leave a half-applied set.
    val selection = remember {
        mutableStateMapOf<SyncCategory, Boolean>().apply {
            SyncCategory.entries.forEach { put(it, true) }
        }
    }
    val firstRowFocus = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.62f).heightIn(max = 620.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "What should come in from Drive?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "You can change any of this later in Settings, and each " +
                        "device chooses for itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SyncCategory.entries.forEachIndexed { index, category ->
                        SettingsToggleRow(
                            title = category.displayName,
                            subtitle = category.subtitle,
                            checked = selection[category] ?: true,
                            onCheckedChange = { selection[category] = it },
                            modifier = if (index == 0) {
                                Modifier.focusRequester(firstRowFocus)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Not now") }
                    TextButton(onClick = { onConfirm(selection.toMap()) }) {
                        Text("Continue")
                    }
                }
            }
        }
    }

    // Same retry shape as WhatsNewSheet: a single requestFocus lands before the
    // row has realised and is dropped, so try briefly until it takes.
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { firstRowFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(16L)
        }
    }
}
