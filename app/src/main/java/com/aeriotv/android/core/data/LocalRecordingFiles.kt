package com.aeriotv.android.core.data

import android.content.Context

/** On-device recording files (local DVR captures and Save to Device copies). */
object LocalRecordingFiles {
    /** How a recording's path is stored. */
    enum class Kind { ContentUri, FileUri, Path }

    /**
     * A recording saved to the public Downloads (MediaStore, the default on
     * Android 10 and later) or a custom SAF folder is a content:// URI; the
     * app-private fallback is a bare path; a file:// URI is a path too.
     */
    fun kind(path: String): Kind = when {
        path.startsWith("content://") -> Kind.ContentUri
        path.startsWith("file://") -> Kind.FileUri
        else -> Kind.Path
    }

    /**
     * Delete a recording's bytes, whichever kind its path is ([kind]): a
     * SAF document through DocumentsContract, a MediaStore item through the
     * content resolver, a file directly. `File(path).delete()` alone does
     * nothing for a content:// path, which left the file behind while its row
     * was dropped. Best-effort; the caller drops the row regardless.
     */
    fun delete(context: Context, path: String) {
        runCatching {
            when (kind(path)) {
                Kind.ContentUri -> {
                    val uri = android.net.Uri.parse(path)
                    if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                        android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
                    } else {
                        context.contentResolver.delete(uri, null, null)
                    }
                }
                Kind.FileUri -> android.net.Uri.parse(path).path?.let { java.io.File(it).delete() }
                Kind.Path -> java.io.File(path).delete()
            }
        }
    }
}
