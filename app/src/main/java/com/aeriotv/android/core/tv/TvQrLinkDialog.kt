package com.aeriotv.android.core.tv

import android.graphics.Bitmap
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
import androidx.compose.runtime.remember
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
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The link a [TvQrLinkDialog] should present: the dialog title (usually the
 * label of the button the user pressed), a one-line caption explaining what
 * scanning does, and the URL itself. Hoist a nullable instance of this as
 * screen state; non-null shows the dialog.
 */
data class TvQrLink(
    val title: String,
    val caption: String,
    val url: String,
)

/**
 * TV-only dialog that presents an external link as a QR code. Android TV has
 * no browser, so buttons that would open a URL on a phone (Trailer, View on
 * TMDB, Developer Website, Report an Issue) instead show this dialog and the
 * user scans the code with a phone camera. Ported pattern from the tvOS
 * inline trailer QR (white rounded card, crisp modules) and the TV log-share
 * dialog ([com.aeriotv.android.feature.settings.TvLogShareDialog]); phones
 * and tablets keep their ACTION_VIEW intents and never see this.
 *
 * If QR generation fails the URL is still shown as text so the user can type
 * it. The screen is kept awake while the dialog is up because the user is
 * aiming a phone camera at the TV.
 */
@Composable
fun TvQrLinkDialog(
    title: String,
    caption: String,
    url: String,
    onDismiss: () -> Unit,
) {
    // Keep the screensaver away while the user fumbles for their phone.
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
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))

                val qr = remember(url) { qrCodeBitmap(url) }
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
                            contentDescription = "QR code for $title",
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.size(240.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                // Always show the URL (long ones may wrap) so the user can
                // type it if the QR failed to generate or won't scan.
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )

                Spacer(Modifier.height(14.dp))
                SettingsDialogTextButton(label = "Close", onClick = onDismiss)
            }
        }
    }
}

/**
 * Encode [content] as a QR bitmap. Error correction H matches the iOS choice
 * (a phone camera reads a TV screen at an angle through living-room
 * lighting); 512 px keeps the modules sharp after TV upscaling. Shared by
 * [TvQrLinkDialog] and the TV log-share dialog.
 */
internal fun qrCodeBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 2,
        ),
    )
    val px = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(px, matrix.width, matrix.height, Bitmap.Config.RGB_565)
}.getOrNull()
