package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsyncSchemaTest {

    private val now = 1_760_724_245L
    private val dev = "6F1C2A9E-4B7D-4C1E-9E1A-0D2B8C3F5A11"

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    /** Encode [data] for [key], decode it back, and return the decoded data. */
    private fun roundTrip(key: DsyncKey, data: JsonObject): JsonObject {
        val env = DsyncEnvelopeCodec.encode(key, null, dev, "0.5.13", now, data)
        val wire = env.toString()
        assertFalse("compact JSON", wire.contains(": ") || wire.contains(", "))
        val d = DsyncEnvelopeCodec.decode(obj(wire), key.schemaVersion)
        assertTrue(d is DsyncDecode.Ok)
        val e = (d as DsyncDecode.Ok).envelope
        assertEquals(1, e.schemaVersion)
        assertEquals(dev, e.deviceId)
        assertEquals("android", e.platform)
        assertEquals(now, e.updatedAt)
        assertEquals("2025-10-17T18:04:05Z", env["updatedAt"]!!.let { (it as JsonPrimitive).content })
        return e.data
    }

    @Test fun keysMatchSchema() {
        assertEquals(13, DsyncKey.entries.size)
        DsyncKey.entries.forEach { assertTrue(it.wire, DsyncKey.isValidKeyName(it.wire)) }
        assertEquals("app.molinete.aerio.hiddenTitles", DsyncKey.HiddenTitles.wire)
        assertEquals(DsyncKey.Favorites, DsyncKey.fromWire("app.molinete.aerio.favorites"))
    }

    @Test fun favoritesRoundTrip() {
        val d = roundTrip(DsyncKey.Favorites, DsyncPayloads.encodeFavorites(null, listOf(1042, 77, 903, 77)))
        assertEquals(listOf(1042, 77, 903), DsyncPayloads.decodeFavorites(d))
        assertEquals("""{"ids":[1042,77,903]}""", d.toString())
    }

    @Test fun recentsRoundTrip() {
        val rows = listOf(RecentRow(1042, 1760724245), RecentRow(77, 1760720000))
        val d = roundTrip(DsyncKey.Recents, DsyncPayloads.encodeRecents(null, rows))
        assertEquals("""{"rows":[[1042,1760724245],[77,1760720000]]}""", d.toString())
        assertEquals(rows, DsyncPayloads.decodeRecents(d))
    }

    @Test fun progressRoundTrip() {
        val p = ProgressRow(8812, "e", 1834, 2640, 1760724245, false, 301, 2, 5, "The Pilot")
        val m = ProgressRow(5531, "m", 10, 0, 1760720000, true, null, 0, 0, "Movie")
        val d = roundTrip(DsyncKey.Progress, DsyncPayloads.encodeProgress(null, listOf(m, p), now))
        assertEquals(
            """{"rows":[[8812,"e",1834,2640,1760724245,0,301,2,5,"The Pilot"],[5531,"m",10,0,1760720000,1,null,0,0,"Movie"]]}""",
            d.toString(),
        )
        assertEquals(listOf(p, m), DsyncPayloads.decodeProgress(d))
    }

    @Test fun remindersRoundTrip() {
        val r = ReminderRow(1042, now + 3600, now + 7200, "Monday Night Football", "ESPN")
        val d = roundTrip(DsyncKey.Reminders, DsyncPayloads.encodeReminders(null, listOf(r), now))
        assertEquals(listOf(r), DsyncPayloads.decodeReminders(d))
    }

    @Test fun watchlistAndHiddenTitlesRoundTrip() {
        val rows = listOf(VodMarkRow(5531, "m", 1760700000), VodMarkRow(301, "s", 1760600000))
        val w = roundTrip(DsyncKey.Watchlist, DsyncPayloads.encodeVodMarks(null, rows, DsyncLimits.WATCHLIST))
        assertEquals("""{"rows":[[5531,"m",1760700000],[301,"s",1760600000]]}""", w.toString())
        assertEquals(rows, DsyncPayloads.decodeVodMarks(w))
        val h = roundTrip(DsyncKey.HiddenTitles, DsyncPayloads.encodeVodMarks(null, rows.take(1), DsyncLimits.HIDDEN_TITLES))
        assertEquals(rows.take(1), DsyncPayloads.decodeVodMarks(h))
    }

    @Test fun hiddenGroupsAndGroupOrderRoundTrip() {
        val g = DsyncPayloads.HiddenGroups(listOf("Adult", "Spanish"), emptyList(), listOf("Kids"))
        val d = roundTrip(DsyncKey.HiddenGroups, DsyncPayloads.encodeHiddenGroups(null, g))
        assertEquals("""{"live":["Adult","Spanish"],"movies":[],"series":["Kids"]}""", d.toString())
        assertEquals(g, DsyncPayloads.decodeHiddenGroups(d))
        val o = DsyncPayloads.GroupOrder("manual", listOf("Sports", "News", "Movies"))
        val d2 = roundTrip(DsyncKey.GroupOrder, DsyncPayloads.encodeGroupOrder(null, o))
        assertEquals("""{"mode":"manual","order":["Sports","News","Movies"]}""", d2.toString())
        assertEquals(o, DsyncPayloads.decodeGroupOrder(d2))
    }

    @Test fun settingsRoundTripKeepsUnknownNames() {
        val remote = obj("""{"v":{"selectedTheme":"ocean","categoryColor.sports":"#FF0000","customCategories":"[]"},"future":1}""")
        val owned = setOf("selectedTheme", "trueBlack", "textScale")
        val built = DsyncPayloads.encodeSettings(
            remote, owned,
            mapOf("selectedTheme" to JsonPrimitive("Aerio"), "trueBlack" to JsonPrimitive(true), "textScale" to JsonPrimitive(1.1)),
        )
        val d = roundTrip(DsyncKey.Settings, built)
        val v = DsyncPayloads.decodeSettings(d)!!
        assertEquals("Aerio", v["selectedTheme"]!!.content)
        assertEquals("true", v["trueBlack"]!!.content)
        assertEquals("1.1", v["textScale"]!!.content)
        assertEquals("#FF0000", v["categoryColor.sports"]!!.content)
        assertEquals("[]", v["customCategories"]!!.content)
        assertEquals(JsonPrimitive(1), d["future"])
    }

    @Test fun remoteMapRadioCollectionsProfilesRoundTrip() {
        val tv = obj("""{"menu":"guide","playPause":"togglePlay","longSelect":"options"}""")
        assertEquals(tv, DsyncPayloads.decodeRemoteMap(roundTrip(DsyncKey.RemoteMap, DsyncPayloads.encodeRemoteMap(null, tv))))
        assertEquals(false, DsyncPayloads.decodeRadioDefault(roundTrip(DsyncKey.RadioDefault, DsyncPayloads.encodeRadioDefault(null, false))))
        assertEquals(true, DsyncPayloads.decodeRadioDefault(roundTrip(DsyncKey.RadioDefault, DsyncPayloads.encodeRadioDefault(null, true))))
        val c = CollectionRow("3F2A9C1E-7B44-4D0A-9A61-2C5E8F1B7D30", "Sunday Football", "b", listOf(1042, 77, 903))
        val dc = roundTrip(DsyncKey.Collections, DsyncPayloads.encodeCollections(null, listOf(c)))
        assertEquals("""{"rows":[["3F2A9C1E-7B44-4D0A-9A61-2C5E8F1B7D30","Sunday Football","b",[1042,77,903]]]}""", dc.toString())
        assertEquals(listOf(c), DsyncPayloads.decodeCollections(dc))
        val salt = "b81e" + "0".repeat(28)
        val hash = DsyncPinHash.hash(salt, "UULR")
        val rows = listOf(
            ProfileRow(1, "admin", "Logan", "none", "", "", role = "admin", avatar = com.aeriotv.android.core.profiles.AvatarSpec()),
            ProfileRow(7, "test1", "Kids", "directions", hash, salt, role = "child", avatar = com.aeriotv.android.core.profiles.AvatarSpec("paw", "orange")),
        )
        val dp = roundTrip(DsyncKey.Profiles, DsyncPayloads.encodeProfiles(null, rows))
        assertEquals(rows, DsyncPayloads.decodeProfiles(dp))
        // Position 8: {} for the default, {"icon","color"} otherwise.
        assertTrue(dp.toString().contains(""""admin",0,{}]"""))
        assertTrue(dp.toString().contains("""{"icon":"paw","color":"orange"}"""))
        // An older writer without the role position: reader keeps its own ("").
        val old = obj("""{"rows":[[3,"x","X","none","",""]]}""")
        assertEquals("", DsyncPayloads.decodeProfiles(old)!![0].role)
        assertEquals("", DsyncPayloads.decodeProfiles(obj("""{"rows":[[3,"x","X","none","","","boss"]]}"""))!![0].role)
    }

    @Test fun trailingPositionsAndUnknownMembersRoundTrip() {
        val remote = obj("""{"rows":[[8812,"e",1,2,100,0,301,2,5,"T","newField",9]],"extraMember":{"a":1}}""")
        val pulled = DsyncPayloads.decodeProgress(remote)!!
        assertEquals(listOf(JsonPrimitive("newField"), JsonPrimitive(9)), pulled[0].tail.extra)
        val local = listOf(ProgressRow(8812, "e", 50, 2, 200, false, 301, 2, 5, "T"))
        val built = DsyncPayloads.encodeProgress(remote, DsyncMerge.progress(local, pulled), 200)
        val row = built["rows"]!!.jsonArray[0].jsonArray
        assertEquals(12, row.size)
        assertEquals(JsonPrimitive(50), row[2])
        assertEquals(JsonPrimitive("newField"), row[10])
        assertEquals(obj("""{"a":1}"""), built["extraMember"])
    }

    @Test fun envelopeExtraAndUnknownTopLevelRoundTrip() {
        val prev = obj("""{"schemaVersion":1,"deviceId":"A","platform":"ios","appVersion":"1.8.44","updatedAt":"2026-10-17T18:04:05Z","data":{},"extra":{"x":1},"note":"keep"}""")
        val env = DsyncEnvelopeCodec.encode(DsyncKey.Favorites, prev, dev, "0.5.13", now, obj("""{"ids":[1]}"""))
        assertEquals(obj("""{"x":1}"""), env["extra"])
        assertEquals(JsonPrimitive("keep"), env["note"])
        assertEquals(JsonPrimitive("android"), env["platform"])
    }

    @Test fun readerRules() {
        assertEquals(DsyncDecode.Malformed, DsyncEnvelopeCodec.decode(obj("""{"schemaVersion":1}"""), 1))
        assertEquals(
            DsyncDecode.Malformed,
            DsyncEnvelopeCodec.decode(obj("""{"schemaVersion":"1","deviceId":"a","platform":"ios","appVersion":"1","updatedAt":"2026-10-17T18:04:05Z","data":{}}"""), 1),
        )
        val v2 = DsyncEnvelopeCodec.decode(obj("""{"schemaVersion":2,"deviceId":"a","platform":"ios","appVersion":"1","updatedAt":"2026-10-17T18:04:05Z","data":{}}"""), 1)
        assertEquals(DsyncDecode.TooNew(2), v2)
        assertNull(DsyncPayloads.decodeFavorites(obj("""{"ids":["x"]}""")))
    }

    @Test fun lastWriterWinsWithDeviceIdTie() {
        assertTrue(DsyncEnvelopeCodec.remoteWins(100, "A", 101, "A"))
        assertFalse(DsyncEnvelopeCodec.remoteWins(101, "A", 100, "Z"))
        assertTrue(DsyncEnvelopeCodec.remoteWins(100, "A", 100, "B"))
        assertFalse(DsyncEnvelopeCodec.remoteWins(100, "B", 100, "A"))
        assertFalse(DsyncEnvelopeCodec.remoteWins(100, "A", 100, "A"))
    }

    @Test fun progressRowMergeKeepsNewerPerId() {
        val local = listOf(ProgressRow(1, "m", 10, 0, 200, false, null, 0, 0, "A"), ProgressRow(2, "m", 5, 0, 100, false, null, 0, 0, "B"))
        val remote = listOf(ProgressRow(1, "m", 99, 0, 150, false, null, 0, 0, "A"), ProgressRow(2, "m", 77, 0, 300, false, null, 0, 0, "B"), ProgressRow(3, "m", 1, 0, 50, false, null, 0, 0, "C"))
        val m = DsyncMerge.progress(local, remote)
        assertEquals(listOf(2, 1, 3), m.map { it.vodId })
        assertEquals(10L, m.first { it.vodId == 1 }.position)
        assertEquals(77L, m.first { it.vodId == 2 }.position)
    }

    @Test fun recentsRowMergeKeepsNewerPerId() {
        val m = DsyncMerge.recents(listOf(RecentRow(1, 300), RecentRow(2, 100)), listOf(RecentRow(2, 200), RecentRow(3, 250)))
        assertEquals(listOf(1 to 300L, 3 to 250L, 2 to 200L), m.map { it.channelId to it.watchedAt })
    }

    @Test fun capsApply() {
        val many = (1..60).map { RecentRow(it, it.toLong()) }
        assertEquals(50, DsyncPayloads.capRecents(many).size)
        assertEquals(60, DsyncPayloads.capRecents(many).first().channelId)
        val old = ProgressRow(1, "m", 1, 1, now - 31L * 24 * 3600, true, null, 0, 0, "x")
        val keep = ProgressRow(2, "m", 1, 1, now - 1, true, null, 0, 0, "x")
        assertEquals(listOf(2), DsyncPayloads.capProgress(listOf(old, keep), now).map { it.vodId })
        val past = ReminderRow(1, now - 7200, now - 3600, "t", "c")
        assertTrue(DsyncPayloads.capReminders(listOf(past), now).isEmpty())
        assertEquals(1000, DsyncPayloads.decodeFavorites(DsyncPayloads.encodeFavorites(null, (1..1200).toList()))!!.size)
        assertEquals("ab", truncateUtf8("abc", 2))
        assertEquals("a", truncateUtf8("aé", 2)) // e-acute is 2 bytes
        val p = ProgressRow(1, "m", 1, 1, 1, false, null, 0, 0, "x".repeat(80))
        assertEquals(40, (DsyncPayloads.progressRowJson(p)[9] as JsonPrimitive).content.length)
    }

    private fun bigProgress(n: Int, finishedEvery: Int = 3): JsonObject =
        DsyncPayloads.encodeProgress(
            null,
            (1..n).map { i ->
                ProgressRow(1_000_000 + i, "e", 100L + i, 2640, now - i, i % finishedEvery == 0, 2_000_000 + i, 1, i % 20, "T".repeat(40), Tail(listOf(JsonPrimitive("x".repeat(40)))))
            },
            now,
        )

    private fun wrap(k: DsyncKey, d: JsonObject) = DsyncEnvelopeCodec.encode(k, null, dev, "0.5.13", now, d)

    @Test fun trimOrderForOneKey() {
        val labels = DsyncTrim.STEPS.map { it.label }
        assertEquals(
            listOf(
                "progress: drop finished", "progress: oldest down to 200", "progress: titles to 20 B",
                "reminders: drop rows starting more than 14 days out", "recents: down to 25",
                "watchlist: oldest down to 300", "hiddenTitles: oldest down to 300", "settings: drop customCategories",
            ),
            labels,
        )
        // Step 1 alone is enough: finished rows go, unfinished stay.
        val data = bigProgress(300)
        assertTrue(DsyncEnvelopeCodec.byteSize(wrap(DsyncKey.Progress, data)) > DsyncLimits.KEY_BYTES)
        val fitted = DsyncTrim.fitKey(DsyncKey.Progress, data, now) { wrap(DsyncKey.Progress, it) }!!
        val rows = DsyncPayloads.decodeProgress(fitted)!!
        assertEquals(200, rows.size)
        assertTrue(rows.none { it.finished })
        assertTrue(DsyncEnvelopeCodec.byteSize(wrap(DsyncKey.Progress, fitted)) <= DsyncLimits.KEY_BYTES)
    }

    @Test fun keyGuard32KiBRunsStepsInOrderThenGivesUp() {
        // No finished rows: step 2 (down to 200) must run.
        val data = bigProgress(300, finishedEvery = Int.MAX_VALUE)
        val fitted = DsyncTrim.fitKey(DsyncKey.Progress, data, now) { wrap(DsyncKey.Progress, it) }!!
        assertTrue(DsyncPayloads.decodeProgress(fitted)!!.size <= 200)
        // Favorites are never trimmed: a value that cannot fit returns null.
        val huge = JsonObject(mapOf("ids" to JsonArray((1..9000).map { JsonPrimitive(1_000_000 + it) })))
        assertNull(DsyncTrim.fitKey(DsyncKey.Favorites, huge, now) { wrap(DsyncKey.Favorites, it) })
        // Under the limit: untouched.
        val small = DsyncPayloads.encodeFavorites(null, listOf(1, 2))
        assertEquals(small, DsyncTrim.fitKey(DsyncKey.Favorites, small, now) { wrap(DsyncKey.Favorites, it) })
    }

    @Test fun totalGuard128KiBTrimsAcrossKeysInOrder() {
        val progress = DsyncTrim.fitKey(DsyncKey.Progress, bigProgress(300, Int.MAX_VALUE), now) { wrap(DsyncKey.Progress, it) }!!
        val recents = DsyncPayloads.encodeRecents(null, (1..50).map { RecentRow(100000 + it, now - it) })
        val datas = mapOf(DsyncKey.Progress to progress, DsyncKey.Recents to recents)
        val ours = datas.entries.sumOf { (k, d) -> DsyncEnvelopeCodec.byteSize(wrap(k, d)).toLong() }
        // Other apps leave just under what our keys need.
        val others = DsyncLimits.TOTAL_BYTES - ours + 10
        val out = DsyncTrim.fitTotal(datas, others, now) { k, d -> wrap(k, d) }!!
        // Progress steps run first; recents stay whole because progress freed enough.
        assertEquals(50, DsyncPayloads.decodeRecents(out[DsyncKey.Recents]!!)!!.size)
        val total = others + out.entries.sumOf { (k, d) -> DsyncEnvelopeCodec.byteSize(wrap(k, d)).toLong() }
        assertTrue(total <= DsyncLimits.TOTAL_BYTES)
        // Nothing fits when the other apps already use the whole budget.
        assertNull(DsyncTrim.fitTotal(datas, DsyncLimits.TOTAL_BYTES.toLong(), now) { k, d -> wrap(k, d) })
        // Already under: unchanged.
        assertEquals(datas, DsyncTrim.fitTotal(datas, 0, now) { k, d -> wrap(k, d) })
    }

    @Test fun pinHashMatchesSchemaDefinition() {
        // SHA-256 of salt bytes || UTF-8 PIN. Salt 16 zero bytes, PIN "1234".
        val salt = "00".repeat(16)
        val h = DsyncPinHash.hash(salt, "1234")
        assertEquals(64, h.length)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(ByteArray(16)); md.update("1234".toByteArray())
        assertEquals(md.digest().joinToString("") { "%02x".format(it) }, h)
        assertNotNull(DsyncPinHash.hexToBytes("b81e"))
    }

    @Test fun profileAvatarWire() {
        // Absent (older writer) = null, the reader keeps its own avatar.
        assertNull(DsyncPayloads.decodeProfiles(obj("""{"rows":[[3,"x","X","none","","","user",5]]}"""))!![0].avatar)
        // Unknown ids fall back per field; a trailing position after the avatar is kept.
        val r = DsyncPayloads.decodeProfiles(obj("""{"rows":[[3,"x","X","none","","","user",5,{"icon":"dragon","color":"blue"},"next"]]}"""))!![0]
        assertEquals(com.aeriotv.android.core.profiles.AvatarSpec("initial", "blue"), r.avatar)
        assertEquals(1, r.tail.extra.size)
        assertEquals("""{"color":"blue"}""", DsyncPayloads.encodeAvatar(r.avatar).toString())
        assertEquals("{}", DsyncPayloads.encodeAvatar(null).toString())
    }

    @Test fun avatarDefaultsAreStable() {
        val cat = com.aeriotv.android.core.profiles.AvatarCatalog
        assertEquals(12, cat.ICONS.size)
        assertEquals(8, cat.COLORS.size)
        // Sum of "test1" chars (116+101+115+116+49 = 497) mod 8 = 1, blue.
        assertEquals(1, cat.defaultColorIndex("Test1"))
        assertEquals("blue", cat.colorFor(null, "test1"))
        assertEquals("ember", cat.colorFor(com.aeriotv.android.core.profiles.AvatarSpec("star", "ember"), "test1"))
        assertNull(cat.normalize(com.aeriotv.android.core.profiles.AvatarSpec("nope", "nope")))
        assertEquals("initial", cat.iconFor(null))
    }
}
