package com.aeriotv.android.core.qr

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * After a successful import from Photos or Import file, the picked image or
 * document still holds the sign-in code. This offers to delete it: the
 * pickers [offer] the source, and ImportSourceDeletePrompt (mounted once at
 * the app root, so the offer survives the form navigating away) asks the
 * user. Nothing is deleted without the user's tap. Uris are never logged.
 */
object ImportSourceCleanup {
    sealed interface Source {
        /** A MediaStore item; deleted through MediaStore.createDeleteRequest (system confirmation). */
        data class Photo(val mediaUri: Uri) : Source
        /** A document whose provider supports delete; deleted through DocumentsContract.deleteDocument. */
        data class Document(val uri: Uri) : Source
    }

    private val _pending = MutableStateFlow<Source?>(null)
    val pending: StateFlow<Source?> = _pending.asStateFlow()

    fun offer(source: Source?) { if (source != null) _pending.value = source }

    fun clear() { _pending.value = null }

    private const val LOCAL_PICKER_AUTHORITY = "com.android.providers.media.photopicker"

    /**
     * The MediaStore item behind a Photos pick, or null when it cannot be
     * deleted through MediaStore (Android 10 and earlier, a cloud item, or a
     * provider other than the device's media store).
     */
    suspend fun photoSource(context: Context, picked: Uri): Source.Photo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (picked.scheme != "content") return null
        val mediaUri: Uri? = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    picked.authority == MediaStore.AUTHORITY && picked.pathSegments.contains("picker") -> {
                        // content://media/picker/<user>/<provider authority>/media/<id>; only
                        // local items map to a MediaStore row (the id is its _ID).
                        if (!picked.pathSegments.contains(LOCAL_PICKER_AUTHORITY)) null
                        else picked.lastPathSegment?.toLongOrNull()?.let {
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                                it,
                            )
                        }
                    }
                    picked.authority == MediaStore.AUTHORITY -> picked
                    DocumentsContract.isDocumentUri(context, picked) -> MediaStore.getMediaUri(context, picked)
                    else -> null
                }
            }.getOrNull()
        }
        return mediaUri?.let { Source.Photo(it) }
    }

    /** The picked document when its provider says it can be deleted, else null. */
    suspend fun documentSource(context: Context, picked: Uri): Source.Document? = withContext(Dispatchers.IO) {
        runCatching {
            if (!DocumentsContract.isDocumentUri(context, picked)) return@runCatching null
            val flags = context.contentResolver.query(
                picked,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
            if (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) Source.Document(picked) else null
        }.getOrNull()
    }
}
