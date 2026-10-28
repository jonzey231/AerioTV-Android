package com.aeriotv.android.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * zxing core QR decoding over plain pixel arrays, shared by the camera
 * analyzer and the photo import. Pure JVM (no android.graphics) so the
 * fallback passes are unit tested.
 */
object QrLuminanceDecoder {
    private val stillHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        // Dark-theme renders are light-on-dark.
        DecodeHintType.ALSO_INVERTED to true,
    )
    private val frameHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.ALSO_INVERTED to true,
    )

    /** One camera frame. Fast path: one binarizer, no try-harder. */
    fun decodeFrame(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply { setHints(frameHints) }
        return try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text } catch (_: ReaderException) { null } finally { reader.reset() }
    }

    /** One pass over [source] with both binarizers (local, then global threshold). */
    private fun decodeStill(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply { setHints(stillHints) }
        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
            try { return reader.decodeWithState(bitmap).text } catch (_: ReaderException) { } finally { reader.reset() }
        }
        return null
    }

    /**
     * A still image (photo, screenshot) as ARGB [pixels] of [width] x [height],
     * already downscaled by the caller. Passes, in order: the full image, a
     * center crop (the middle 60 percent, which drops screen chrome and busy
     * surroundings), then the full image rotated 90 degrees.
     */
    fun decodeStill(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        decodeStill(RGBLuminanceSource(width, height, pixels))?.let { return it }
        val cw = (width * 0.6).toInt(); val ch = (height * 0.6).toInt()
        if (cw >= 21 && ch >= 21) {
            val crop = crop(pixels, width, (width - cw) / 2, (height - ch) / 2, cw, ch)
            decodeStill(RGBLuminanceSource(cw, ch, crop))?.let { return it }
        }
        return decodeStill(RGBLuminanceSource(height, width, rotate90(pixels, width, height)))
    }

    internal fun crop(px: IntArray, w: Int, x0: Int, y0: Int, cw: Int, ch: Int): IntArray {
        val out = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(px, (y0 + y) * w + x0, out, y * cw, cw)
        return out
    }

    /** Clockwise: the result is [h] wide and [w] tall. */
    internal fun rotate90(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = px[y * w + x]
        return out
    }

    /** Scale factor that brings the long side to at most [maxSide] (1 = no change). */
    fun downscaleFactor(width: Int, height: Int, maxSide: Int = 1600): Float {
        val long = maxOf(width, height)
        return if (long <= maxSide || long <= 0) 1f else maxSide.toFloat() / long
    }
}
