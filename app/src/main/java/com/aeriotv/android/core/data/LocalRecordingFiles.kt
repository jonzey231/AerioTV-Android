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

    /** A recording file's size and last write; [bytes] null when the file is not there. */
    data class Probe(val bytes: Long?, val lastWriteAt: Long?)

    /**
     * Size and last write of a recording's file, whichever kind its path is.
     * A content:// file is measured through its descriptor, which also works
     * for a MediaStore item still pending after the app was killed mid-write.
     */
    fun probe(context: Context, path: String): Probe = runCatching {
        when (kind(path)) {
            Kind.ContentUri -> context.contentResolver
                .openFileDescriptor(android.net.Uri.parse(path), "r")
                ?.use { pfd ->
                    val st = android.system.Os.fstat(pfd.fileDescriptor)
                    Probe(st.st_size, st.st_mtime * 1000L)
                } ?: Probe(null, null)
            Kind.FileUri, Kind.Path -> {
                val file = java.io.File(if (kind(path) == Kind.FileUri) android.net.Uri.parse(path).path.orEmpty() else path)
                if (file.exists()) Probe(file.length(), file.lastModified().takeIf { it > 0L }) else Probe(null, null)
            }
        }
    }.getOrElse { Probe(null, null) }

    /**
     * The recording file's name, whose extension is its format (Program
     * Info's Format row). A Downloads (MediaStore) or picked-folder file is a
     * content:// URI with no extension, so its name is read from the content
     * resolver. Null when there is no file to name.
     */
    fun displayName(context: Context, path: String): String? = runCatching {
        if (path.isBlank()) return@runCatching null
        when (kind(path)) {
            Kind.ContentUri -> context.contentResolver.query(
                android.net.Uri.parse(path),
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            Kind.FileUri -> android.net.Uri.parse(path).lastPathSegment
            Kind.Path -> java.io.File(path).name
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Make a MediaStore recording visible in Downloads (clear IS_PENDING), as
     * a normal finish does; a capture cut short by a process death was left
     * pending, and pending items are hidden and eventually reaped. A no-op
     * for SAF documents and plain files.
     */
    fun publish(context: Context, path: String) {
        if (kind(path) != Kind.ContentUri) return
        val uri = android.net.Uri.parse(path)
        if (uri.authority != android.provider.MediaStore.AUTHORITY) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        runCatching {
            val done = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
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
