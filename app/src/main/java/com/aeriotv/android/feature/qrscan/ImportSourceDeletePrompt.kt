package com.aeriotv.android.feature.qrscan

import android.app.Activity
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.qr.ImportSourceCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun logOutcome(deleted: Boolean) {
    android.util.Log.i("AerioTV", if (deleted) "[QR] import source deleted" else "[QR] import source kept")
}

/**
 * "Delete this image from Photos?" / "Delete this file?" after a successful
 * import ([ImportSourceCleanup]). Mounted once at the app root. A photo goes
 * through MediaStore.createDeleteRequest, so the system asks again and the OS
 * deletes it; a document goes through DocumentsContract.deleteDocument.
 */
@Composable
fun ImportSourceDeletePrompt() {
    val source by ImportSourceCleanup.pending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        logOutcome(it.resultCode == Activity.RESULT_OK)
    }
    val s = source ?: return
    val isPhoto = s is ImportSourceCleanup.Source.Photo
    AlertDialog(
        onDismissRequest = { ImportSourceCleanup.clear(); logOutcome(false) },
        text = {
            Text(
                if (isPhoto) "Delete this image from Photos? It contains your sign-in code."
                else "Delete this file? It contains your sign-in code.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                ImportSourceCleanup.clear()
                when (s) {
                    is ImportSourceCleanup.Source.Photo -> {
                        val sent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
                            val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(s.mediaUri))
                            deleteRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }.isSuccess
                        if (!sent) logOutcome(false)
                    }
                    is ImportSourceCleanup.Source.Document -> scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, s.uri) }.getOrDefault(false)
                        }
                        logOutcome(ok)
                    }
                }
            }) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { ImportSourceCleanup.clear(); logOutcome(false) }) { Text("Keep") }
        },
    )
}
