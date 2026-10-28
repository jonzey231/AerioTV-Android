package com.aeriotv.android.core.qr

import com.aeriotv.android.core.setup.SetupProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CodeTextImportTest {

    private val json = """{"v":1,"type":"user_api_key","api_key":"k3y-ABC","base_url":"https://tv.example.com/","username":"alice"}"""
    private val setupLink = SetupProtocol.buildLink("192.168.1.20", 41234, "123456", ByteArray(32) { it.toByte() }, "Living Room")

    private fun stream(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

    @Test fun jsonFileParses() {
        val r = CodeTextImport.parse(stream(json)) as ScannedCode.Result.UserKey
        assertEquals("k3y-ABC", r.apiKey)
        assertEquals("https://tv.example.com", r.baseUrl)
        assertEquals("alice", r.username)
    }

    @Test fun prettyPrintedJsonWithBomParses() {
        val pretty = "﻿{\n  \"v\": 1,\n  \"type\": \"user_api_key\",\n  \"api_key\": \"k3y-ABC\"\n}\n"
        val r = CodeTextImport.parse(stream(pretty)) as ScannedCode.Result.UserKey
        assertEquals("k3y-ABC", r.apiKey)
        assertNull(r.baseUrl)
    }

    @Test fun textFileWithSetupLinkParses() {
        assertEquals(ScannedCode.Result.SetupLink(setupLink), CodeTextImport.parse(stream("$setupLink\n")))
    }

    @Test fun setupLinkAmongOtherTextParses() {
        val msg = "Open this on your phone: $setupLink. Thanks!"
        assertEquals(ScannedCode.Result.SetupLink(setupLink), CodeTextImport.parse(msg))
    }

    @Test fun jsonAmongOtherTextParses() {
        val r = CodeTextImport.parse("Here is your code:\n$json\nSee you.") as ScannedCode.Result.UserKey
        assertEquals("k3y-ABC", r.apiKey)
    }

    @Test fun otherTextIsInvalid() {
        assertEquals(ScannedCode.Result.Invalid, CodeTextImport.parse("https://example.com/some/page"))
        assertEquals(ScannedCode.Result.Invalid, CodeTextImport.parse(""))
        assertEquals(ScannedCode.Result.Invalid, CodeTextImport.parse(null as String?))
        assertEquals(ScannedCode.Result.Invalid, CodeTextImport.parse(json.replace("user_api_key", "other")))
        assertEquals(ScannedCode.Result.Invalid, CodeTextImport.parse("aeriotv://setup?nothing=here"))
    }

    @Test fun binaryFileIsInvalid() {
        val bytes = ByteArray(2048) { (it * 31).toByte() }
        assertEquals(ScannedCode.Result.Invalid, CodeTextImport.parse(ByteArrayInputStream(bytes)))
    }

    @Test fun readStopsAtLimit() {
        assertEquals("abc", CodeTextImport.readLimited(stream("abc"), maxBytes = 3))
        assertNull(CodeTextImport.readLimited(stream("abcd"), maxBytes = 3))
    }

    @Test fun oversizedFileIsInvalidEvenWithACode() {
        val padded = json + " ".repeat(CodeTextImport.MAX_BYTES)
        assertEquals(ScannedCode.Result.Invalid, CodeTextImport.parse(stream(padded)))
    }

    @Test fun fileAtLimitIsRead() {
        val padded = json + " ".repeat(CodeTextImport.MAX_BYTES - json.length)
        assertTrue(CodeTextImport.parse(stream(padded)) is ScannedCode.Result.UserKey)
    }

    @Test fun canonicalTextRoundTrips() {
        val key = ScannedCode.parse(json) as ScannedCode.Result.UserKey
        assertEquals(key, ScannedCode.parse(CodeTextImport.canonicalText(key)))
        val link = ScannedCode.Result.SetupLink(setupLink)
        assertEquals(link, ScannedCode.parse(CodeTextImport.canonicalText(link)))
        assertNull(CodeTextImport.canonicalText(ScannedCode.Result.Invalid))
    }

    @Test fun failureCopy() {
        assertEquals("That file is not a Dispatcharr user code or an AerioTV setup code.", CodeTextImport.FILE_INVALID_MESSAGE)
    }
}
