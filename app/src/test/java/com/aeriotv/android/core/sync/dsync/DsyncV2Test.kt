package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema v3: one key, every category a sub-object. */
class DsyncV2Test {
    private val now = 1_760_724_245L
    private val dev = "DEV-A"
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun sub(data: JsonObject, at: Long = now, d: String = dev) = DsyncV2.encodeCategory(null, d, at, data)
    private fun ok(v: JsonObject): DsyncV2.Bucket = (DsyncV2.decode(obj(v.toString())) as DsyncV2.Decode.Ok).bucket
    private fun cat(b: DsyncV2.Bucket, k: DsyncKey) = (DsyncV2.category(b, k) as DsyncDecode.Ok).envelope

    @Test fun oneKeyHoldsEveryCategory() {
        assertEquals(1, DsyncBucket.entries.size)
        assertEquals("logan.aeriotv", DsyncBucket.Main.wire("logan.aeriotv"))
        assertEquals(DsyncKey.entries.toSet(), DsyncBucket.Main.keys.toSet())
        DsyncKey.entries.forEach { assertEquals(DsyncBucket.Main, DsyncBucket.of(it)) }
    }

    @Test fun v3EncodeDecodeEveryCategory() {
        val p = ProgressRow(8812, "e", 1834, 2640, now, false, 301, 2, 5, "The Pilot")
        val value = DsyncV2.encodeBucket(
            null, dev, "0.5.13", now,
            mapOf(
                DsyncKey.Favorites to sub(DsyncPayloads.encodeFavorites(null, listOf(1042, 77))),
                DsyncKey.Recents to sub(DsyncPayloads.encodeRecents(null, listOf(RecentRow(1042, now)))),
                DsyncKey.Progress to sub(DsyncPayloads.encodeProgress(null, listOf(p), now)),
                DsyncKey.RadioDefault to sub(DsyncPayloads.encodeRadioDefault(null, true)),
                DsyncKey.Settings to sub(obj("""{"v":{"trueBlack":true}}"""), 200, "OTHER"),
            ),
        )
        assertEquals(JsonPrimitive(3), value["schemaVersion"])
        val b = ok(value)
        assertEquals(listOf(1042, 77), DsyncPayloads.decodeFavorites(cat(b, DsyncKey.Favorites).data))
        assertEquals(listOf(p), DsyncPayloads.decodeProgress(cat(b, DsyncKey.Progress).data))
        assertEquals(true, DsyncPayloads.decodeRadioDefault(cat(b, DsyncKey.RadioDefault).data))
        assertEquals(200L, cat(b, DsyncKey.Settings).updatedAt)
        assertEquals("OTHER", cat(b, DsyncKey.Settings).deviceId)
        assertNull(DsyncV2.category(b, DsyncKey.Watchlist))
        // Older readers skip v3; a v3 reader skips v4 and does not treat v2 as v3.
        assertTrue(DsyncEnvelopeCodec.decode(obj(value.toString()), 1) !is DsyncDecode.Ok)
        assertEquals(DsyncV2.Decode.TooNew(4), DsyncV2.decode(obj(value.toString().replace("\"schemaVersion\":3", "\"schemaVersion\":4"))))
        assertEquals(DsyncV2.Decode.Malformed, DsyncV2.decode(obj(value.toString().replace("\"schemaVersion\":3", "\"schemaVersion\":2"))))
        // Rewriting one category round-trips the others, unknown categories, and extra.
        val withUnknown = obj(value.toString().replace("\"categories\":{", "\"categories\":{\"future\":{\"updatedAt\":\"2026-01-01T00:00:00Z\",\"data\":{},\"extra\":{\"e\":1}},"))
        val again = ok(DsyncV2.encodeBucket(withUnknown, "PHONE", "0.5.13", now + 5, mapOf(DsyncKey.Watchlist to sub(obj("""{"rows":[]}"""), now + 5, "PHONE"))))
        assertEquals(200L, cat(again, DsyncKey.Settings).updatedAt)
        assertEquals(obj("""{"e":1}"""), again.categories["future"]!!["extra"])
    }

    private fun v1(key: DsyncKey, data: String, at: Long, d: String = "OLD"): DsyncEnvelope =
        (DsyncEnvelopeCodec.decode(DsyncEnvelopeCodec.encode(key, null, d, "0.5.12", at, obj(data)), 1) as DsyncDecode.Ok).envelope

    /** A v2 three-key value (schemaVersion 2). */
    private fun v2(vararg cats: Pair<DsyncKey, JsonObject>): DsyncV2.Bucket {
        val v = DsyncV2.encodeBucket(null, "V2DEV", "0.5.13", now, cats.toMap())
        val asV2 = obj(v.toString().replace("\"schemaVersion\":3", "\"schemaVersion\":2"))
        return (DsyncV2.decode(asV2, min = DsyncV2.MIN_SUBOBJECT_SCHEMA) as DsyncV2.Decode.Ok).bucket
    }

    @Test fun migrationFromV1AndV2MergesPerCategoryByUpdatedAt() {
        val oldV1 = mapOf(
            DsyncKey.Favorites to v1(DsyncKey.Favorites, """{"ids":[1,2]}""", 100),
            DsyncKey.Settings to v1(DsyncKey.Settings, """{"v":{"trueBlack":true}}""", 300),
            DsyncKey.Reminders to v1(DsyncKey.Reminders, """{"rows":[]}""", 50),
        )
        val fromV2 = listOf(
            v2(DsyncKey.Favorites to sub(obj("""{"ids":[9]}"""), 200, "V2DEV"), DsyncKey.Recents to sub(obj("""{"rows":[[9,1]]}"""), 150, "V2DEV")),
            v2(DsyncKey.Settings to sub(obj("""{"v":{"trueBlack":false}}"""), 250, "V2DEV")),
        )
        val out = ok(DsyncV2.migrate(oldV1, fromV2, null, dev, "0.5.13", now)!!)
        assertEquals(listOf(9), DsyncPayloads.decodeFavorites(cat(out, DsyncKey.Favorites).data)) // v2 at 200 beats v1 at 100
        assertEquals(true, DsyncPayloads.decodeSettings(cat(out, DsyncKey.Settings).data)!!["trueBlack"]!!.content.toBoolean()) // v1 at 300 beats v2 at 250
        assertEquals(150L, cat(out, DsyncKey.Recents).updatedAt)
        assertEquals(50L, cat(out, DsyncKey.Reminders).updatedAt)
        assertEquals("OLD", cat(out, DsyncKey.Reminders).deviceId)
        // An existing v3 category newer than both wins.
        val v3 = DsyncV2.encodeBucket(null, "V3DEV", "0.5.13", now, mapOf(DsyncKey.Favorites to sub(obj("""{"ids":[5]}"""), 900, "V3DEV")))
        val out3 = ok(DsyncV2.migrate(oldV1, fromV2, v3, dev, "0.5.13", now)!!)
        assertEquals(listOf(5), DsyncPayloads.decodeFavorites(cat(out3, DsyncKey.Favorites).data))
        assertNull(DsyncV2.migrate(emptyMap(), emptyList(), v3, dev, "0.5.13", now))
    }

    @Test fun trimSequenceOn413IsOneStepAtATimeInOrder() {
        assertEquals(
            listOf(
                "progress: oldest down to 200", "progress: oldest down to 100", "recents: down to 25",
                "watchlist: oldest down to 300", "hiddenTitles: oldest down to 300",
                "watchlist: oldest down to 150", "hiddenTitles: oldest down to 150", "favorites: down to 500",
            ),
            DsyncV2.TRIMS.map { it.label },
        )
        val progress = (1..300).map { ProgressRow(it, "m", 1, 1, now - it, false, null, 0, 0, "t") }
        val marks = (1..500).map { VodMarkRow(it, "m", now - it) }
        var value = DsyncV2.encodeBucket(
            null, dev, "0.5.13", now,
            mapOf(
                DsyncKey.Progress to sub(DsyncPayloads.encodeProgress(null, progress, now)),
                DsyncKey.Recents to sub(DsyncPayloads.encodeRecents(null, (1..50).map { RecentRow(it, now - it) })),
                DsyncKey.Watchlist to sub(DsyncPayloads.encodeVodMarks(null, marks, 500)),
                DsyncKey.HiddenTitles to sub(DsyncPayloads.encodeVodMarks(null, marks, 500)),
                DsyncKey.Favorites to sub(DsyncPayloads.encodeFavorites(null, (1..1000).toList())),
            ),
        )
        val before = DsyncEnvelopeCodec.byteSize(value)
        // Step 0: only progress changes, oldest go first.
        value = DsyncV2.applyTrim(value, 0)!!
        val p = DsyncPayloads.decodeProgress(cat(ok(value), DsyncKey.Progress).data)!!
        assertEquals(200, p.size)
        assertEquals(1, p.first().vodId)
        assertEquals(50, DsyncPayloads.decodeRecents(cat(ok(value), DsyncKey.Recents).data)!!.size)
        for (i in 1 until DsyncV2.TRIMS.size) value = DsyncV2.applyTrim(value, i)!!
        val b = ok(value)
        assertEquals(100, DsyncPayloads.decodeProgress(cat(b, DsyncKey.Progress).data)!!.size)
        assertEquals(25, DsyncPayloads.decodeRecents(cat(b, DsyncKey.Recents).data)!!.size)
        assertEquals(150, DsyncPayloads.decodeVodMarks(cat(b, DsyncKey.Watchlist).data)!!.size)
        assertEquals(1, DsyncPayloads.decodeVodMarks(cat(b, DsyncKey.Watchlist).data)!!.first().vodId)
        assertEquals(150, DsyncPayloads.decodeVodMarks(cat(b, DsyncKey.HiddenTitles).data)!!.size)
        assertEquals((1..500).toList(), DsyncPayloads.decodeFavorites(cat(b, DsyncKey.Favorites).data))
        assertTrue(DsyncEnvelopeCodec.byteSize(value) < before)
        // Past the last step, or a category that is absent: nothing to apply.
        assertNull(DsyncV2.applyTrim(value, DsyncV2.TRIMS.size))
        val empty = DsyncV2.encodeBucket(null, dev, "0.5.13", now, emptyMap())
        assertNull(DsyncV2.applyTrim(empty, 0))
        assertTrue(JsonArray(emptyList()).isEmpty())
    }

    /** Key derivation vectors (identical in the Apple Scripts/dsync-tests). */
    @Test fun userKeyDerivation() {
        val vectors = listOf(
            "Logan" to "logan.aeriotv",
            "john.doe-1_x" to "john.doe-1_x.aeriotv",
            "a@b.com" to "a-40b.com.aeriotv",
            "first+tag" to "first-2btag.aeriotv",
            "_admin" to "u_admin.aeriotv",
            "Jos\u00E9" to "jos-c3-a9.aeriotv",
            "ADMIN" to "admin.aeriotv",
            "" to null,
            "a".repeat(60) to "a".repeat(46) + ".aeriotv",
        )
        for ((u, want) in vectors) {
            assertEquals(u, want, DsyncKeyName.main(u))
            if (want != null) assertTrue(u, DsyncKey.isValidKeyName("$want.favorites"))
        }
        assertEquals(setOf("logan.aeriotv", "logan.aeriotv.favorites", "logan.aeriotv.progress"), DsyncKeyName.v2Wires("logan.aeriotv"))
        assertTrue(DsyncKeyName.isLegacy("app.molinete.aerio") && DsyncKeyName.isLegacy("app.molinete.aerio.favorites"))
        assertTrue(!DsyncKeyName.isLegacy("app.molinete.aeriotv") && !DsyncKeyName.isLegacy("logan.aeriotv"))
        assertTrue(DsyncKeyName.isMain("logan.aeriotv") && !DsyncKeyName.isMain(".aeriotv"))
    }

    @Test fun legacyKeyRenamesOnceToTheUserKey() {
        assertEquals(
            listOf("app.molinete.aerio", "app.molinete.aerio.progress"),
            DsyncKeyName.renamePlan(listOf("other", "app.molinete.aerio.progress", "app.molinete.aerio"), "logan.aeriotv"),
        )
        assertEquals(emptyList<String>(), DsyncKeyName.renamePlan(listOf("logan.aeriotv", "app.molinete.aerio"), "logan.aeriotv"))
        assertEquals(emptyList<String>(), DsyncKeyName.renamePlan(listOf("other"), "logan.aeriotv"))
        val legacyV3 = DsyncV2.encodeBucket(null, "old", "1", now, mapOf(DsyncKey.Favorites to sub(obj("""{"ids":[4,5]}"""), now, "old")))
        assertEquals(legacyV3, DsyncKeyName.renameValue(listOf("app.molinete.aerio" to legacyV3), dev, "2", now))
        val v1Newer = DsyncEnvelopeCodec.encode(DsyncKey.Favorites, null, "z", "1", now + 100, obj("""{"ids":[9]}"""))
        val merged = ok(DsyncKeyName.renameValue(listOf("app.molinete.aerio" to legacyV3, "app.molinete.aerio.favorites" to v1Newer), dev, "2", now)!!)
        assertEquals(listOf(9), DsyncPayloads.decodeFavorites(cat(merged, DsyncKey.Favorites).data))
        assertNull(DsyncKeyName.renameValue(listOf("app.molinete.aerio" to obj("""{"x":1}""")), dev, "2", now))
    }
}

class VodIntIdTest {
    /** The catalog's movie payload starts with the integer id, which movieUuidsByIds reads. */
    @Test fun moviePayloadStartsWithIntegerId() {
        val m = com.aeriotv.android.core.network.DispatcharrVODMovie(id = 4242, uuid = "0b7e2c1a-uuid")
        val payload = Json { ignoreUnknownKeys = true }.encodeToString(com.aeriotv.android.core.network.DispatcharrVODMovie.serializer(), m)
        assertTrue(payload, payload.startsWith("{\"id\":4242,"))
        assertEquals("4242", Regex("^\\{\"id\":(\\d+)").find(payload.take(24))!!.groupValues[1])
    }

    /** Rows carry integers for vodId and seriesId; movie and episode ids never collide in the merge. */
    @Test fun integerIdsRoundTripAndTypeSeparatesMovieAndEpisode() {
        val e = ProgressRow(77, "e", 10, 20, 300, false, 12, 1, 2, "Ep")
        val m = ProgressRow(77, "m", 5, 20, 200, false, null, 0, 0, "Movie")
        val d = DsyncPayloads.encodeProgress(null, listOf(e, m), 400)
        assertEquals("""{"rows":[[77,"e",10,20,300,0,12,1,2,"Ep"],[77,"m",5,20,200,0,null,0,0,"Movie"]]}""", d.toString())
        assertEquals(2, DsyncMerge.progress(listOf(e), listOf(m)).size)
        assertEquals(listOf(VodMarkRow(5531, "m", 9)), DsyncPayloads.decodeVodMarks(Json.parseToJsonElement("""{"rows":[[5531,"m",9]]}""").jsonObject))
        // A uuid string where an int belongs is malformed.
        assertNull(DsyncPayloads.decodeVodMarks(Json.parseToJsonElement("""{"rows":[["5531","m",9]]}""").jsonObject))
    }
}
