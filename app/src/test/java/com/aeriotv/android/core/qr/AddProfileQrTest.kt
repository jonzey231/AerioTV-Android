package com.aeriotv.android.core.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Add Profile scans the user's Dispatcharr QR in API Key mode (Apple f4cf2e9, same cases). */
class AddProfileQrTest {
    private val mine = listOf("https://tv.example.com", "http://192.168.1.20:9191")
    private fun key(base: String?) = ScannedCode.Result.UserKey("k", base, "bob")
    private fun problem(r: ScannedCode.Result, urls: List<String?> = mine) = ScannedCode.addProfileProblem(r, urls)

    @Test fun thisServersCodeFillsTheKey() {
        assertNull(problem(key("https://tv.example.com")))
        // The same host over another scheme and port.
        assertNull(problem(key("http://TV.example.com:9191")))
        // The local URL's host.
        assertNull(problem(key("http://192.168.1.20:9191")))
        // A code without a server address is taken as is.
        assertNull(problem(key(null)))
    }

    @Test fun anotherServersCodeIsRefused() {
        assertEquals(ScannedCode.OTHER_SERVER_MESSAGE, problem(key("https://other.example.com")))
        // A playlist without an address takes the code.
        assertNull(problem(key("https://other.example.com"), listOf("", null)))
    }

    @Test fun setupCodesAndAnythingElseAreRefused() {
        assertEquals(ScannedCode.SETUP_CODE_IN_ADD_PROFILE, problem(ScannedCode.Result.SetupLink("aeriotv://setup?v=1")))
        assertEquals(ScannedCode.INVALID_MESSAGE, problem(ScannedCode.Result.Invalid))
    }

    @Test fun hostsAndCopy() {
        assertEquals("tv.example.com", ScannedCode.host(" TV.Example.com:9191/x "))
        assertNull(ScannedCode.host(""))
        assertEquals("Point the camera at a Dispatcharr user QR code.", ScannedCode.ADD_PROFILE_PROMPT)
        assertEquals("This is an AerioTV setup code. Scan a Dispatcharr user QR code.", ScannedCode.SETUP_CODE_IN_ADD_PROFILE)
        assertEquals("This user code is for a different Dispatcharr server.", ScannedCode.OTHER_SERVER_MESSAGE)
    }
}
