package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TMDB key at rest in the dsync state file (section 4.13, hardening
 * 2026-11-13): sealed on write, opened on read, never kept in plain text,
 * and a copy that cannot be opened loses only its tmdbKey category.
 */
class DsyncAtRestTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // A stand-in for CredentialCipher: tagged values are "sealed:" + the reversed key.
    private val seal: (String) -> String? = { v -> if (v.startsWith("sealed:")) v else "sealed:" + v.reversed() }
    private val open: (String) -> String? = { v ->
        when {
            v == "sealed:BAD" -> null
            v.startsWith("sealed:") -> v.removePrefix("sealed:").reversed()
            else -> v // a plain value an earlier build saved
        }
    }
    private val failingSeal: (String) -> String? = { null }

    private fun bucket(tmdb: String?) = obj(
        """{"schemaVersion":3,"deviceId":"d","platform":"apple","appVersion":"1","updatedAt":"2026-11-13T18:00:00Z","categories":{""" +
            """"favorites":{"updatedAt":"2026-11-13T18:00:00Z","deviceId":"d","data":{"ids":[1,2]}}""" +
            (tmdb?.let { ""","tmdbKey":{"updatedAt":"2026-11-13T18:00:00Z","deviceId":"d","data":{"apiKey":"$it","x":1}}""" } ?: "") +
            """},"syncProviders":{"keep":true}}""",
    )

    private fun apiKeyIn(b: JsonObject): String? =
        (((b[DsyncV2.CATEGORIES] as JsonObject)["tmdbKey"] as? JsonObject)?.get("data") as? JsonObject)?.get("apiKey")?.let { (it as JsonPrimitive).content }

    @Test fun aBucketIsSavedSealedAndReadBackExactly() {
        val raw = bucket("k-123")
        val sealed = DsyncAtRest.sealBucket(raw, seal)
        assertFalse("never the plain key at rest", sealed.toString().contains("k-123"))
        assertEquals("sealed:321-k", apiKeyIn(sealed))
        // Everything else is untouched: other categories, unknown members, envelope.
        assertEquals((raw[DsyncV2.CATEGORIES] as JsonObject)["favorites"], (sealed[DsyncV2.CATEGORIES] as JsonObject)["favorites"])
        assertEquals(raw["syncProviders"], sealed["syncProviders"])
        assertEquals(raw, DsyncAtRest.openBucket(sealed, open))
        assertEquals("sealing twice changes nothing", sealed, DsyncAtRest.sealBucket(sealed, seal))
    }

    @Test fun aBucketWithoutAKeyIsUnchanged() {
        val none = bucket(null)
        assertEquals(none, DsyncAtRest.sealBucket(none, seal))
        assertEquals(none, DsyncAtRest.openBucket(none, open))
        val cleared = bucket("")
        assertEquals("a clear is nothing secret", cleared, DsyncAtRest.sealBucket(cleared, seal))
        assertEquals(cleared, DsyncAtRest.openBucket(cleared, open))
    }

    @Test fun aPlainKeyFromAnEarlierBuildReadsAndReseals() {
        val legacy = bucket("k-123")
        assertEquals(legacy, DsyncAtRest.openBucket(legacy, open))
        assertEquals("sealed:321-k", apiKeyIn(DsyncAtRest.sealBucket(DsyncAtRest.openBucket(legacy, open), seal)))
    }

    @Test fun aCopyThatCannotBeSealedOrOpenedLosesOnlyItsKeyCategory() {
        val failed = DsyncAtRest.sealBucket(bucket("k-123"), failingSeal)
        assertFalse(failed.toString().contains("k-123"))
        assertFalse((failed[DsyncV2.CATEGORIES] as JsonObject).containsKey("tmdbKey"))
        assertTrue((failed[DsyncV2.CATEGORIES] as JsonObject).containsKey("favorites"))
        val bad = DsyncAtRest.openBucket(bucket("sealed:BAD"), open)
        assertFalse("never a sealed value or an empty key toward the server", (bad[DsyncV2.CATEGORIES] as JsonObject).containsKey("tmdbKey"))
    }

    @Test fun theCategorysOwnCopiesSealAndOpen() {
        val sub = obj("""{"updatedAt":"2026-11-13T18:00:00Z","deviceId":"d","data":{"apiKey":"k-123"}}""")
        val sealedSub = DsyncAtRest.sealCategory(sub, seal)!!
        assertFalse(sealedSub.toString().contains("k-123"))
        assertEquals(sub, DsyncAtRest.openCategory(sealedSub, open))
        assertNull(DsyncAtRest.sealCategory(sub, failingSeal))
        val data = obj("""{"apiKey":"k-123"}""")
        val sealedData = DsyncAtRest.sealData(data, seal)!!
        assertEquals("""{"apiKey":"sealed:321-k"}""", sealedData.toString())
        // The change check compares strings: an opened copy prints exactly as the original.
        assertEquals(data.toString(), DsyncAtRest.openData(sealedData, open).toString())
        assertNull(DsyncAtRest.openData(obj("""{"apiKey":"sealed:BAD"}"""), open))
        assertEquals("a non-string key is left to the codec", obj("""{"apiKey":42}"""), DsyncAtRest.sealData(obj("""{"apiKey":42}"""), seal))
    }
}
