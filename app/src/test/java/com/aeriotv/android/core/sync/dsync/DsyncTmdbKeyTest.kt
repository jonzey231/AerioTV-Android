package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.sync.SyncItemCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tmdbKey (docs/DispatcharrSyncSchema.md section 4.13, owner exception
 * 2026-11-13). The same cases as Apple's Scripts/dsync-tests tmdbKey block,
 * plus Android's change detection (a Drive restore is not an edit).
 */
class DsyncTmdbKeyTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // ---- Category and placement: its own sub-object, ungated by Sync Categories, never mirrored to Drive ----

    @Test fun categoryAndPlacement() {
        assertEquals("tmdbKey", DsyncKey.TmdbKey.suffix)
        assertNull("no Sync Categories toggle", DsyncKey.TmdbKey.category)
        assertNull("no Sync Categories row", SyncItemCatalog.itemForDsync("tmdbKey"))
        // Drive carries the key in the Credentials file on its own terms (DriveTmdbKeyTest), never through a follow.
        assertNull("no Drive follow file", DsyncCoexistence.driveFileFor(DsyncKey.TmdbKey))
        assertFalse("not a setup-choice key", DsyncKey.TmdbKey in DsyncCoexistence.FORCE_KEYS)
        assertFalse("never pushed or pulled by hand", DsyncKey.TmdbKey in DsyncManual.keys(emptyMap()))
        assertTrue("in the one App Data key", DsyncKey.TmdbKey in DsyncBucket.Main.keys)
        assertTrue("never trimmed", DsyncTrim.STEPS.none { it.key == DsyncKey.TmdbKey } && DsyncV2.TRIMS.none { it.key == DsyncKey.TmdbKey })
    }

    @Test fun theDriveCompareNeverCountsIt() {
        val cats = mapOf(DsyncKey.TmdbKey to DsyncV2.encodeCategory(null, "dev", 1_794_000_000L, obj("""{"apiKey":"abc123"}""")))
        val raw = DsyncV2.encodeBucket(null, "dev", "1", 1_794_000_000L, cats)
        val bucket = (DsyncV2.decode(raw) as DsyncV2.Decode.Ok).bucket
        val summary = DsyncCoexistence.summarize(bucket, emptyMap())
        assertFalse(summary.categories.containsKey("tmdbKey"))
        assertTrue(summary.isEmpty)
    }

    // ---- Codec: apiKey string, trimmed; missing = cleared; wrong type = malformed; unknown members round-trip ----

    @Test fun codec() {
        assertEquals("decode a key", "abc123", DsyncPayloads.decodeTMDBKey(obj("""{"apiKey":"abc123"}""")))
        assertEquals("decode trims", "abc123", DsyncPayloads.decodeTMDBKey(obj("""{"apiKey":"  abc123 "}""")))
        assertEquals("decode a clear", "", DsyncPayloads.decodeTMDBKey(obj("""{"apiKey":""}""")))
        assertEquals("missing member reads as cleared", "", DsyncPayloads.decodeTMDBKey(obj("{}")))
        assertNull("non-string is malformed", DsyncPayloads.decodeTMDBKey(obj("""{"apiKey":42}""")))
        assertEquals("encode trims", """{"apiKey":"k1"}""", DsyncPayloads.encodeTMDBKey(null, " k1 ").toString())
        assertEquals("encode a clear", """{"apiKey":""}""", DsyncPayloads.encodeTMDBKey(null, "").toString())
        assertEquals(
            "unknown members round-trip",
            obj("""{"apiKey":"new","x":1}"""),
            DsyncPayloads.encodeTMDBKey(obj("""{"apiKey":"old","x":1}"""), "new"),
        )
        // A clear is a real value, not an empty category (the first-pull seed rule must not skip it).
        assertFalse(DsyncCoexistence.isEmptyData(DsyncKey.TmdbKey, obj("""{"apiKey":""}""")))
    }

    // ---- Newest edit wins (checked when a newer remote value arrives) ----

    @Test fun newestEditWins() {
        assertFalse("same value: nothing to keep", DsyncPayloads.tmdbKeepsLocal("k", "k", 500, 100))
        assertTrue("local edit newer: keep and send", DsyncPayloads.tmdbKeepsLocal("k2", "k1", 500, 400))
        assertFalse("remote edit newer: take it", DsyncPayloads.tmdbKeepsLocal("k2", "k1", 300, 400))
        assertFalse("a newer clear clears", DsyncPayloads.tmdbKeepsLocal("k2", "", 300, 400))
        assertTrue("a newer local clear is kept and sent", DsyncPayloads.tmdbKeepsLocal("", "k1", 500, 400))
        assertFalse("no key, unknown time: take the server's key", DsyncPayloads.tmdbKeepsLocal("", "k1", 0, 400))
        assertFalse("unknown time, two keys: the server's wins", DsyncPayloads.tmdbKeepsLocal("k2", "k1", 0, 400))
        assertTrue("unknown time: a key beats a clear", DsyncPayloads.tmdbKeepsLocal("k2", "", 0, 400))
    }

    // ---- Change detection: a SHA-256 digest, and only a save or clear here is an edit ----

    @Test fun digestIsSha256HexOfTheTrimmedKey() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DsyncPayloads.tmdbDigest(""))
        assertEquals("6ca13d52ca70c883e0f0bb101e425a89e8624de51db2d2392593af6a84118090", DsyncPayloads.tmdbDigest("abc123"))
        assertEquals(DsyncPayloads.tmdbDigest("abc123"), DsyncPayloads.tmdbDigest(" abc123 "))
    }

    @Test fun onlyASaveOrClearHereIsAnEdit() {
        val a = DsyncPayloads.TmdbLocal(DsyncPayloads.tmdbDigest("A"), 100)
        assertFalse("the first value is the baseline", DsyncPayloads.tmdbIsEdit(null, a))
        // The TMDB field writes the key and its edit time together.
        val saved = DsyncPayloads.TmdbLocal(DsyncPayloads.tmdbDigest("B"), 200)
        assertTrue("a save", DsyncPayloads.tmdbIsEdit(a, saved))
        assertTrue("a clear", DsyncPayloads.tmdbIsEdit(a, DsyncPayloads.TmdbLocal(DsyncPayloads.tmdbDigest(""), 200)))
        // A Drive restore of an older snapshot writes the key alone.
        val restored = DsyncPayloads.TmdbLocal(DsyncPayloads.tmdbDigest("R"), 100)
        assertFalse("a Drive restore", DsyncPayloads.tmdbIsEdit(a, restored))
        // Saving the key it already holds has nothing to send.
        assertFalse("same key saved again", DsyncPayloads.tmdbIsEdit(a, DsyncPayloads.TmdbLocal(a.digest, 300)))
        // After a restore, saving the earlier key again is an edit (the key changed here).
        assertTrue("restore then save", DsyncPayloads.tmdbIsEdit(restored, DsyncPayloads.TmdbLocal(a.digest, 300)))
    }
}
