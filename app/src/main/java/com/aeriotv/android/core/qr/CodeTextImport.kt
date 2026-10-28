package com.aeriotv.android.core.qr

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.InputStream

/**
 * The Dispatcharr user code or AerioTV setup code arriving as text instead of
 * an image: a picked file (Import file). Same payloads as [ScannedCode]: the
 * `user_api_key` JSON, or an `aeriotv://setup?...` link, which in a text file
 * may sit among other words.
 *
 * Pure JVM so the unit tests cover it. Never log the text: it holds the key.
 */
object CodeTextImport {
    /** Files larger than this are not a code; reading stops here. */
    const val MAX_BYTES = 64 * 1024
    const val FILE_INVALID_MESSAGE = "That file is not a Dispatcharr user code or an AerioTV setup code."

    /** Mime types offered to the system document picker; the last is the fallback for files the provider types loosely. */
    val PICKER_MIME_TYPES = arrayOf("application/json", "text/plain", "*/*")

    private val SETUP_LINK = Regex("""aeriotv://setup\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /**
     * Reads at most [MAX_BYTES] as UTF-8 (a leading BOM dropped). Returns null
     * when the stream holds more than that, so an oversized file is rejected
     * rather than parsed from a truncated prefix.
     */
    fun readLimited(input: InputStream, maxBytes: Int = MAX_BYTES): String? {
        val buf = ByteArray(maxBytes + 1)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        if (n > maxBytes) return null
        return String(buf, 0, n, Charsets.UTF_8).removePrefix("﻿")
    }

    /**
     * The whole text as a code first; then an `aeriotv://setup` link anywhere
     * in it; then the outermost `{...}` as the user code JSON. Anything else is
     * [ScannedCode.Result.Invalid].
     */
    fun parse(text: String?): ScannedCode.Result {
        val t = text?.removePrefix("﻿")?.trim().orEmpty()
        if (t.isEmpty()) return ScannedCode.Result.Invalid
        ScannedCode.parse(t).let { if (it !is ScannedCode.Result.Invalid) return it }
        SETUP_LINK.find(t)?.value?.trimEnd('.', ',', ')', ']', ';')?.let { link ->
            ScannedCode.parse(link).let { if (it !is ScannedCode.Result.Invalid) return it }
        }
        val open = t.indexOf('{')
        val close = t.lastIndexOf('}')
        if (open in 0 until close) {
            ScannedCode.parseUserKey(t.substring(open, close + 1))?.let { return it }
        }
        return ScannedCode.Result.Invalid
    }

    /** [readLimited] then [parse]; an unreadable or oversized stream is Invalid. */
    fun parse(input: InputStream): ScannedCode.Result =
        parse(runCatching { readLimited(input) }.getOrNull() ?: return ScannedCode.Result.Invalid)

    /**
     * Text that [ScannedCode.parse] reads back as [r]: the link, or the user
     * code as minimal JSON. Null for Invalid. Lets a file import go through a
     * scan's text-based validate and result path.
     */
    fun canonicalText(r: ScannedCode.Result): String? = when (r) {
        is ScannedCode.Result.SetupLink -> r.link
        is ScannedCode.Result.UserKey -> buildJsonObject {
            put("v", JsonPrimitive(ScannedCode.USER_KEY_VERSION))
            put("type", JsonPrimitive(ScannedCode.USER_KEY_TYPE))
            put("api_key", JsonPrimitive(r.apiKey))
            r.baseUrl?.let { put("base_url", JsonPrimitive(it)) }
            r.username?.let { put("username", JsonPrimitive(it)) }
        }.toString()
        ScannedCode.Result.Invalid -> null
    }
}
