package com.aeriotv.android.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aeriotv.android.core.debug.LogShareServer
import com.aeriotv.android.core.tv.qrCodeBitmap
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import java.io.File

/**
 * TV-only log-share dialog: spins up [LogShareServer] for the lifetime of the
 * dialog and shows a QR code + URL the user scans with their phone to
 * download the log. Port of the tvOS TvOSLogShareSheet (Aerio
 * DeveloperSettingsView.swift); phones keep the ACTION_SEND share sheet.
 */
@Composable
fun TvLogShareDialog(
    file: File,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        // Pre-create an empty file so the first share works before any line
        // has flushed (matches the tvOS shareFile placeholder behavior).
        runCatching {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
        }
        val server = LogShareServer(file)
        url = server.start()
        failed = url == null
        // Close button, BACK, anything that drops the dialog: server dies.
        onDispose { server.stop() }
    }

    // Keep the screensaver away while the user fumbles for their phone; the
    // server is only reachable while this dialog is on screen.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.55f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Text(
                    text = "Share Log File",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "On your phone, scan the QR code or open this URL. " +
                        "The .txt file will download. Attach it to a GitHub Issue or email.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))

                val shareUrl = url
                if (shareUrl != null) {
                    val qr = remember(shareUrl) { qrCodeBitmap(shareUrl) }
                    if (qr != null) {
                        // White box = quiet zone; FilterQuality.None keeps the
                        // modules crisp (the iOS .interpolation(.none) analog).
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(16.dp),
                        ) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "QR code for the log download URL",
                                filterQuality = FilterQuality.None,
                                modifier = Modifier.size(240.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        text = shareUrl,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "The server stops when you close this screen. " +
                            "The TV and your phone must be on the same Wi-Fi network.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                    )
                } else if (failed) {
                    Text(
                        text = "Could not start LAN server. Check the TV's network connection.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(14.dp))
                SettingsDialogTextButton(label = "Close", onClick = onDismiss)
            }
        }
    }
}
