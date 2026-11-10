package com.aeriotv.android.feature.onboarding

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.security.ClientCertificate
import com.aeriotv.android.core.security.ClientCertificateStore
import com.aeriotv.android.core.security.ClientCertificates
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ClientCertificateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ClientCertificateStore,
) : ViewModel() {
    val certificates: StateFlow<Map<String, ClientCertificate>> get() = store.certificates

    /** Reads the picked file and keeps it for [host]; returns the error copy, or null on success. */
    suspend fun save(host: String, file: Uri, password: String): String? = withContext(Dispatchers.IO) {
        // At most MAX_BYTES + 1 is read, so a wrong pick never loads a whole video.
        val bytes = runCatching {
            context.contentResolver.openInputStream(file)?.use { input ->
                val buf = ByteArray(MAX_BYTES + 1)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        }.getOrNull() ?: return@withContext "Couldn't read the file."
        if (bytes.size > MAX_BYTES) return@withContext "That file is too large for a certificate."
        store.save(host, bytes, password).exceptionOrNull()?.let { it.message ?: "Couldn't open the certificate." }
    }

    fun remove(host: String) = store.remove(host)

    private companion object {
        /** A .p12 with a key and a short chain is a few KB. */
        const val MAX_BYTES = 64 * 1024
    }
}

/**
 * Client certificate (mTLS) for the server at [serverUrl], kept per host so it
 * is in place before the first connection test. Phones and tablets pick a
 * .p12 file; a TV has no document picker, so it only shows a certificate that
 * arrived from another device, and draws nothing otherwise.
 */
@Composable
internal fun ClientCertificateFields(
    serverUrl: String,
    enabled: Boolean,
    viewModel: ClientCertificateViewModel = hiltViewModel(),
) {
    val host = remember(serverUrl) { ClientCertificates.hostOf(serverUrl) } ?: return
    val certificates by viewModel.certificates.collectAsStateWithLifecycle()
    val current = certificates[host]
    val isTv = rememberIsTvDevice()
    if (current == null && isTv) return

    val scope = rememberCoroutineScope()
    var picked by remember(host) { mutableStateOf<Uri?>(null) }
    var password by remember(host) { mutableStateOf("") }
    var error by remember(host) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { picked = uri; error = null }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledField(label = "Client Certificate (Optional)") {
            when {
                current != null -> {
                    val expired = current.notAfter.before(Date())
                    val until = DateFormat.getDateInstance().format(current.notAfter)
                    Text(
                        text = "${current.subject}, " + if (expired) "expired on $until" else "valid until $until",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    if (enabled) ImportFileLink(label = "Remove certificate") { viewModel.remove(host) }
                }
                picked == null -> ImportFileLink(label = "Choose a .p12 file") {
                    // .p12 / .pfx MIME registration varies by file manager: accept anything.
                    picker.launch(arrayOf("application/x-pkcs12", "*/*"))
                }
                else -> {
                    PasswordField(
                        label = "Certificate Password",
                        value = password,
                        onValueChange = { password = it; error = null },
                        placeholder = "Password of the .p12 file",
                        enabled = enabled,
                    )
                    ImportFileLink(label = "Use this certificate") {
                        val file = picked ?: return@ImportFileLink
                        scope.launch {
                            error = viewModel.save(host, file, password)
                            if (error == null) { picked = null; password = "" }
                        }
                    }
                }
            }
        }
        error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            text = "Only needed when a proxy in front of the server asks for one (mTLS: Pangolin, " +
                "Traefik, nginx). AerioTV shows it to $host and nowhere else.",
            style = MaterialTheme.typography.bodySmall.subtext(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
