package com.aeriotv.android.core.qr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a Dispatcharr user code or AerioTV setup code from a document the
 * user picked (Import file). At most
 * [CodeTextImport.MAX_BYTES]; nothing is copied or kept.
 */
object CodeFileImport {
    suspend fun read(context: Context, uri: Uri): ScannedCode.Result = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { CodeTextImport.parse(it) }
        }.getOrNull() ?: ScannedCode.Result.Invalid
    }
}
