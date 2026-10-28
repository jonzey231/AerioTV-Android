package com.aeriotv.android.core.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Reads a QR code out of an image the user picked (system photo picker) or
 * shared to AerioTV. Downscales to at most 1600 px on the long side, then runs
 * the [QrLuminanceDecoder.decodeStill] passes. Returns the decoded text, or
 * null when no QR code was found or the image could not be read.
 */
object QrImageImport {
    private const val MAX_SIDE = 1600

    suspend fun decode(context: Context, uri: Uri): String? = withContext(Dispatchers.Default) {
        val bitmap = runCatching { load(context, uri) }.getOrNull() ?: return@withContext null
        try {
            val w = bitmap.width; val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            QrLuminanceDecoder.decodeStill(px, w, h)
        } finally { bitmap.recycle() }
    }

    private fun load(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation and reads HEIC.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
                val f = QrLuminanceDecoder.downscaleFactor(info.size.width, info.size.height, MAX_SIDE)
                if (f < 1f) dec.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val f = QrLuminanceDecoder.downscaleFactor(raw.width, raw.height, MAX_SIDE)
        if (f >= 1f) return raw
        return Bitmap.createScaledBitmap(raw, (raw.width * f).roundToInt().coerceAtLeast(1), (raw.height * f).roundToInt().coerceAtLeast(1), true)
            .also { if (it !== raw) raw.recycle() }
    }
}
