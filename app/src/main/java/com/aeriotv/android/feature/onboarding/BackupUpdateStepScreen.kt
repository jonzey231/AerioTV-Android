package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.sync.SetupSourceChoice
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing

/**
 * "Your backup needs an update" (Logan, 2026-10-30): offers the one-time
 * Drive id migration (DriveCanon) after a restore finds an old-format
 * backup next to a Dispatcharr playlist. Same look as
 * DriveBackupOfferStepScreen. [inDialog] drops the full-screen frame so
 * Settings shows the same content inside its themed dialog surface.
 * Buttons are never disabled mid-flow (TV focus); taps are ignored while [busy].
 */
@Composable
fun BackupUpdateStepScreen(
    busy: Boolean,
    onUpdate: () -> Unit,
    onNotNow: () -> Unit,
    inDialog: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
    val content: @Composable () -> Unit = {
        Column(
            modifier = if (!inDialog && vp.onboardingMaxWidth != androidx.compose.ui.unit.Dp.Unspecified)
                Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth()
            else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                SetupSourceChoice.UPDATE_TITLE,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                SetupSourceChoice.UPDATE_BODY,
                style = MaterialTheme.typography.bodyMedium.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (!busy) onUpdate() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    .focusRequester(focus)
                    .dpadFocusRing(RoundedCornerShape(50), onFilled = true),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text(SetupSourceChoice.UPDATE_ACCEPT, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { if (!busy) onNotNow() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50)),
            ) { Text(SetupSourceChoice.UPDATE_NOT_NOW, style = MaterialTheme.typography.titleMedium) }
        }
    }
    if (inDialog) { content(); return }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vp.gutter, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Settings: the same step inside the themed dialog surface. */
@Composable
fun BackupUpdateDialog(busy: Boolean, onUpdate: () -> Unit, onNotNow: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!busy) onNotNow() },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Box(Modifier.padding(24.dp)) { BackupUpdateStepScreen(busy, onUpdate, onNotNow, inDialog = true) }
        }
    }
}
