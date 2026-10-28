package com.aeriotv.android.core.qr

import com.aeriotv.android.core.setup.SetupProtocol
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedCodeTest {

    private val full = """{"v":1,"type":"user_api_key","api_key":"k3y-ABC","base_url":"https://tv.example.com/","username":"alice","first_name":"","last_name":"","email":""}"""
    private val trimmed = """{"v":1,"type":"user_api_key","api_key":"k3y-ABC","base_url":"https://tv.example.com"}"""
    private val setupLink = SetupProtocol.buildLink("192.168.1.20", 41234, "123456", ByteArray(32) { it.toByte() }, "Living Room")

    @Test fun validFullPayload() {
        val r = ScannedCode.parse(full) as ScannedCode.Result.UserKey
        assertEquals("k3y-ABC", r.apiKey)
        assertEquals("https://tv.example.com", r.baseUrl)
        assertEquals("alice", r.username)
    }

    @Test fun trimmedPayloadHasNoIdentity() {
        val r = ScannedCode.parse(trimmed) as ScannedCode.Result.UserKey
        assertEquals("k3y-ABC", r.apiKey)
        assertEquals("https://tv.example.com", r.baseUrl)
        assertNull(r.username)
    }

    @Test fun blankUsernameIsNull() {
        val r = ScannedCode.parse(full.replace("\"alice\"", "\"  \"")) as ScannedCode.Result.UserKey
        assertNull(r.username)
    }

    @Test fun wrongTypeRejected() {
        assertEquals(ScannedCode.Result.Invalid, ScannedCode.parse(full.replace("user_api_key", "api_key")))
    }

    @Test fun wrongVersionRejected() {
        assertEquals(ScannedCode.Result.Invalid, ScannedCode.parse(full.replace("\"v\":1", "\"v\":2")))
        assertEquals(ScannedCode.Result.Invalid, ScannedCode.parse(full.replace("\"v\":1,", "")))
    }

    @Test fun missingKeyOrBadUrlRejected() {
        assertEquals(ScannedCode.Result.Invalid, ScannedCode.parse(trimmed.replace("k3y-ABC", "")))
        assertEquals(ScannedCode.Result.Invalid, ScannedCode.parse(trimmed.replace("https://", "ftp://")))
    }

    @Test fun garbageRejected() {
        for (g in listOf("", "   ", "hello", "{", "[1,2]", "https://example.com", "{\"type\":5}", "aeriotv://setup?v=1", "aeriotv://channel/5")) {
            assertEquals(g, ScannedCode.Result.Invalid, ScannedCode.parse(g))
        }
        assertEquals(ScannedCode.Result.Invalid, ScannedCode.parse(null))
    }

    @Test fun setupLinkVsUserKeyDispatch() {
        assertTrue(ScannedCode.parse(setupLink) is ScannedCode.Result.SetupLink)
        assertEquals(setupLink, (ScannedCode.parse("  $setupLink\n") as ScannedCode.Result.SetupLink).link)
        assertTrue(ScannedCode.parse(full) is ScannedCode.Result.UserKey)
    }

    @Test fun toStringNeverShowsKey() {
        assertFalse(ScannedCode.parse(full).toString().contains("k3y-ABC"))
        assertFalse(QrImportBus.SignIn("k3y-ABC", "https://x", "a").toString().contains("k3y-ABC"))
    }

    @Test fun signInMemoOnlyMatchesSavedKey() {
        QrImportBus.noteSignIn(QrImportBus.SignIn("k3y-ABC", "https://x", "a"))
        assertNull(QrImportBus.takeSignIn("typed-other"))
        assertNull(QrImportBus.takeSignIn("k3y-ABC")) // taken once
        QrImportBus.noteSignIn(QrImportBus.SignIn("k3y-ABC", "https://x", "a"))
        assertEquals("a", QrImportBus.takeSignIn("k3y-ABC")?.username)
    }

    @Test fun restoreProbeFindsMainKey() {
        assertTrue(DsyncRestoreProbe.listsMainKey("""{"apps":[{"key":"app.molinete.aerio","bytes":120}]}"""))
        assertFalse(DsyncRestoreProbe.listsMainKey("""{"apps":[{"key":"app.molinete.aerio.favorites","bytes":1}]}"""))
        assertFalse(DsyncRestoreProbe.listsMainKey("""{"apps":[]}"""))
        assertFalse(DsyncRestoreProbe.listsMainKey("nope"))
        assertFalse(DsyncRestoreProbe.listsMainKey(null))
    }

    // ---- still-image decode passes ----

    private fun qrPixels(text: String, size: Int, canvasW: Int, canvasH: Int, invert: Boolean = false): IntArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val dark = if (invert) -1 else 0xFF000000.toInt()
        val light = if (invert) 0xFF000000.toInt() else -1
        val px = IntArray(canvasW * canvasH) { light }
        val x0 = (canvasW - size) / 2; val y0 = (canvasH - size) / 2
        for (y in 0 until size) for (x in 0 until size) if (m[x, y]) px[(y0 + y) * canvasW + x0 + x] = dark
        return px
    }

    @Test fun decodesPlainImage() {
        assertEquals(full, QrLuminanceDecoder.decodeStill(qrPixels(full, 400, 600, 900), 600, 900))
    }

    @Test fun decodesInvertedImage() {
        assertEquals(full, QrLuminanceDecoder.decodeStill(qrPixels(full, 400, 600, 900, invert = true), 600, 900))
    }

    @Test fun rotateAndCropHelpers() {
        val px = intArrayOf(1, 2, 3, 4, 5, 6) // 3 wide, 2 tall
        assertEquals(listOf(4, 1, 5, 2, 6, 3), QrLuminanceDecoder.rotate90(px, 3, 2).toList())
        assertEquals(listOf(2, 3, 5, 6), QrLuminanceDecoder.crop(px, 3, 1, 0, 2, 2).toList())
    }

    @Test fun downscaleFactor() {
        assertEquals(1f, QrLuminanceDecoder.downscaleFactor(1200, 800), 0f)
        assertEquals(0.5f, QrLuminanceDecoder.downscaleFactor(3200, 1000), 0.0001f)
    }

    @Test fun noQrReturnsNull() {
        assertNull(QrLuminanceDecoder.decodeStill(IntArray(300 * 300) { -1 }, 300, 300))
    }
}
