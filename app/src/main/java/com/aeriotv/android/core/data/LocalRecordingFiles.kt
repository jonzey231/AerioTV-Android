package com.aeriotv.android.core.data

import android.content.Context

/** On-device recording files (local DVR captures and Save to Device copies). */
object LocalRecordingFiles {
    /**
     * Delete a recording's bytes: a public-Downloads / SAF content:// URI, a
     * file:// URI, or a bare filesystem path (the app-private fallback).
     * Best-effort; the caller drops the row regardless.
     */
    fun delete(context: Context, path: String) {
        runCatching {
            when {
                path.startsWith("content://") ->
                    context.contentResolver.delete(android.net.Uri.parse(path), null, null)
                path.startsWith("file://") ->
                    android.net.Uri.parse(path).path?.let { java.io.File(it).delete() }
                else -> java.io.File(path).delete()
            }
        }
    }
}
