package com.aeriotv.android.feature.cast.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen pairing-code prompt shown on the TV (GH #33 companion remote) while a
 * phone is trying to pair. Driven purely by [CompanionHostController.pairingCode]:
 * a non-null code shows it, and it clears itself the moment the phone pairs (the
 * host nulls the code on authOk). Rendered as a sibling overlay of the NavHost so it
 * sits above whatever is playing.
 */
@Composable
fun CompanionPairingOverlay(code: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 56.dp, vertical = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Companion Remote",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Enter this code on your phone to control this TV",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    text = code.chunked(3).joinToString(" "),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 56.sp,
                    letterSpacing = 8.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
