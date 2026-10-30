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
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.dpadFocusRing

/**
 * Onboarding step after "Turn on Dispatcharr Sync?" when Drive is off
 * (Logan, 2026-10-30): one tap signs in to Google and turns Drive on.
 * Buttons are never disabled mid-flow (TV focus); taps are ignored while [busy].
 */
@Composable
fun DriveBackupOfferStepScreen(
    busy: Boolean,
    onSignIn: () -> Unit,
    onNotNow: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val vp = com.aeriotv.android.ui.adaptive.rememberViewport()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vp.gutter, vertical = 24.dp),
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
                DRIVE_OFFER_TITLE,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                DRIVE_OFFER_BODY,
                style = MaterialTheme.typography.bodyMedium.subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (!busy) onSignIn() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    .focusRequester(focus)
                    .dpadFocusRing(RoundedCornerShape(50), onFilled = true),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text("Sign in with Google", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = { if (!busy) onNotNow() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).dpadFocusRing(RoundedCornerShape(50)),
            ) { Text("Not now", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

internal const val DRIVE_OFFER_TITLE = "Also back up to Google Drive?"
internal const val DRIVE_OFFER_BODY = "Google Drive keeps a backup of your playlists and settings for a new phone or TV. " +
    "Dispatcharr Sync stays in charge of your favorites, progress, and reminders."
