package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsyncSettingsValidatorTest {
    private fun ok(k: String, v: JsonPrimitive) = DsyncSettingsValidator.accepts(k, v)

    @Test fun enums() {
        listOf("dark", "light", "system").forEach { assertTrue(ok("appearanceMode", JsonPrimitive(it))) }
        assertFalse(ok("appearanceMode", JsonPrimitive("Dark")))
        listOf("", "LiveTV", "DVR", "Movies", "TVShows", "Settings").forEach { assertTrue(ok("defaultTab", JsonPrimitive(it))) }
        assertFalse(ok("defaultTab", JsonPrimitive("Home")))
        listOf("system", "12", "24").forEach { assertTrue(ok("timeFormat", JsonPrimitive(it))) }
        assertFalse(ok("timeFormat", JsonPrimitive(24)))
        assertTrue(ok("guideGroupSelector", JsonPrimitive("pills")))
        assertTrue(ok("phoneGroupSelector", JsonPrimitive("sidebar")))
        assertFalse(ok("phoneGroupSelector", JsonPrimitive("drawer")))
        assertTrue(ok("guideSidebarLayout", JsonPrimitive("shift")))
        assertFalse(ok("guideSidebarLayout", JsonPrimitive("push")))
        listOf("off", "current", "all").forEach { assertTrue(ok("guideProgramInfoButton", JsonPrimitive(it))) }
        assertFalse(ok("guideProgramInfoButton", JsonPrimitive("on")))
        assertTrue(ok("selectedTheme", JsonPrimitive("Midnight")))
        assertFalse(ok("selectedTheme", JsonPrimitive("midnight")))
    }

    @Test fun ranges() {
        assertTrue(ok("textScale", JsonPrimitive(0.85)))
        assertTrue(ok("subtextScale", JsonPrimitive(1.5)))
        assertFalse(ok("textScale", JsonPrimitive(1.6)))
        assertFalse(ok("subtextScale", JsonPrimitive(0.8)))
        assertFalse(ok("textScale", JsonPrimitive("1.1")))
        assertTrue(ok("textContrast", JsonPrimitive(0)))
        assertTrue(ok("textContrast", JsonPrimitive(1.0)))
        assertFalse(ok("textContrast", JsonPrimitive(1.2)))
        assertFalse(ok("textContrast", JsonPrimitive(-0.1)))
    }

    @Test fun booleansMustBeReal() {
        assertTrue(ok("trueBlack", JsonPrimitive(true)))
        assertFalse(ok("trueBlack", JsonPrimitive("true")))
        assertFalse(ok("trueBlack", JsonPrimitive(1)))
        DsyncSettingsValidator.BOOLEANS.forEach { assertTrue(it, ok(it, JsonPrimitive(false))) }
    }

    @Test fun filterSplitsAndDescribes() {
        val r = DsyncSettingsValidator.filter(
            mapOf(
                "appearanceMode" to JsonPrimitive("light"),
                "trueBlack" to JsonPrimitive("yes"),
                "textScale" to JsonPrimitive(3),
            ),
        )
        assertEquals(setOf("appearanceMode"), r.accepted.keys)
        assertEquals(listOf("trueBlack=\"yes\"", "textScale=3"), r.ignored)
    }

    @Test fun expandedAllowlist2026_10_27() {
        assertTrue(ok("guideLayout", JsonPrimitive("preview")))
        assertFalse(ok("guideLayout", JsonPrimitive("grid")))
        assertTrue(ok("brightnessEdge", JsonPrimitive("right")))
        assertTrue(ok("dvrDefaultDestination", JsonPrimitive("local")))
        assertFalse(ok("dvrDefaultDestination", JsonPrimitive("cloud")))
        // Ints: allowed set or range, never strings or fractions.
        assertTrue(ok("skipBack", JsonPrimitive(15)))
        assertFalse(ok("skipBack", JsonPrimitive(20)))
        assertFalse(ok("skipForward", JsonPrimitive("30")))
        assertTrue(ok("channelLogoSize", JsonPrimitive(250)))
        assertFalse(ok("channelLogoSize", JsonPrimitive(251)))
        assertFalse(ok("dvrPreRollMins", JsonPrimitive(1.5)))
        assertTrue(ok("liveRewindKeepCount", JsonPrimitive(5)))
        assertFalse(ok("liveRewindKeepCount", JsonPrimitive(6)))
        assertTrue(ok("multiview.logoSize", JsonPrimitive(5)))
        // Doubles.
        assertTrue(ok("displayScaleGuide", JsonPrimitive(2.0)))
        assertFalse(ok("displayScaleLiveTv", JsonPrimitive(2.0)))
        // Strings and hex.
        assertTrue(ok("customAccentHex", JsonPrimitive("")))
        assertTrue(ok("customAccentHex", JsonPrimitive("#1A2B3C")))
        assertFalse(ok("customAccentHex", JsonPrimitive("blue")))
        assertTrue(ok("categoryColor.sports", JsonPrimitive("FF8800")))
        assertFalse(ok("categoryColor.sports", JsonPrimitive("orange")))
        assertTrue(ok("categoryBucketEnabled.news", JsonPrimitive(false)))
        assertFalse(ok("categoryBucketEnabled.news", JsonPrimitive("false")))
        assertTrue(ok("hiddenEpgBadges", JsonPrimitive("NEW,LIVE")))
        assertFalse(ok("customCategories", JsonPrimitive("x".repeat(4097))))
        assertTrue(ok("lastGroup", JsonPrimitive("Sports")))
        // Apple-only names are not owned here and round-trip untouched.
        assertFalse(DsyncSettingsValidator.isOwned("liquidGlassStyle"))
        assertFalse(DsyncSettingsValidator.isOwned("tintChannelCards"))
        assertTrue(DsyncSettingsValidator.isOwned("categoryColor.kids"))
        // Every name a Sync Categories item carries is owned (except prefix families).
        com.aeriotv.android.core.sync.SyncItemCatalog.allItems.flatMap { it.names }
            .forEach { assertTrue(it, DsyncSettingsValidator.isOwned(it)) }
    }

    @Test fun settingsLanesRoundTrip() {
        val base = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"v":{"liquidGlassStyle":"clear","textScale":1.0},"tv":{"textScale":1.2},"x":1}""",
        ) as kotlinx.serialization.json.JsonObject
        val out = DsyncPayloads.encodeSettingsLanes(base, mapOf("mobile" to mapOf("textScale" to JsonPrimitive(1.3))))
        // Lanes this device does not write, foreign names, and unknown fields round-trip.
        assertEquals("""{"v":{"liquidGlassStyle":"clear","textScale":1.0},"tv":{"textScale":1.2},"x":1,"mobile":{"textScale":1.3}}""", out.toString())
        val lanes = DsyncPayloads.decodeSettingsLanes(out)!!
        assertEquals(JsonPrimitive(1.2), lanes["tv"]!!["textScale"])
        assertEquals(null, DsyncPayloads.decodeSettingsLanes(kotlinx.serialization.json.Json.parseToJsonElement("""{"tv":[1]}""") as kotlinx.serialization.json.JsonObject))
    }
}
