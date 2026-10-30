package com.aeriotv.android.core.sync

import com.aeriotv.android.core.sync.SetupSourceChoice.Category
import com.aeriotv.android.core.sync.SetupSourceChoice.Decision
import com.aeriotv.android.core.sync.SetupSourceChoice.Source
import com.aeriotv.android.core.sync.SetupSourceChoice.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupSourceChoiceTest {
    private fun sum(vararg c: Pair<String, Category>) = Summary(mapOf(*c))

    @Test fun bothEmptyUsesDispatcharr() {
        assertEquals(Decision.UseDispatcharr, SetupSourceChoice.decide(Summary.EMPTY, Summary.EMPTY))
    }

    @Test fun zeroRowCategoriesCountAsEmpty() {
        val d = sum("favorites" to Category(0, 5_000))
        val x = sum("favorites" to Category(3, 1_000))
        assertEquals(Decision.UseDispatcharr, SetupSourceChoice.decide(d, x))
    }

    @Test fun dispatcharrEmptyUsesDrive() {
        assertEquals(Decision.UseDrive, SetupSourceChoice.decide(sum("favorites" to Category(2, 1)), Summary.EMPTY))
    }

    @Test fun driveEmptyUsesDispatcharr() {
        assertEquals(Decision.UseDispatcharr, SetupSourceChoice.decide(Summary.EMPTY, sum("progress" to Category(4, 1))))
    }

    @Test fun equalFingerprintsPreferDispatcharr() {
        val fp = SetupSourceChoice.fingerprint(listOf("ESPN|100", "CNN|200"))
        val d = sum("reminders" to Category(2, 10, SetupSourceChoice.fingerprint(listOf("cnn|200", "espn|100"))), "favorites" to Category(0, 0))
        val x = sum("reminders" to Category(2, 20, fp))
        assertEquals(Decision.UseDispatcharr, SetupSourceChoice.decide(d, x))
    }

    @Test fun differingFingerprintsAsk() {
        val d = sum("reminders" to Category(2, 10, SetupSourceChoice.fingerprint(listOf("a|1", "b|2"))))
        val x = sum("reminders" to Category(2, 20, SetupSourceChoice.fingerprint(listOf("a|1", "c|3"))))
        assertTrue(SetupSourceChoice.decide(d, x) is Decision.Ask)
    }

    @Test fun sameCountsWithoutFingerprintAsk() {
        // No fingerprint (a row did not resolve to its canonical id): equality cannot be proven.
        val d = sum("favorites" to Category(5, 10))
        val x = sum("favorites" to Category(5, 10))
        val r = SetupSourceChoice.decide(d, x)
        assertTrue(r is Decision.Ask)
        assertEquals(d, (r as Decision.Ask).drive)
        assertEquals(x, r.dsync)
    }

    @Test fun differentCountsAsk() {
        val fp = "x"
        assertTrue(SetupSourceChoice.decide(sum("progress" to Category(3, 1, fp)), sum("progress" to Category(4, 1, fp))) is Decision.Ask)
    }

    @Test fun categoryOnOneSideOnlyAsks() {
        val d = sum("favorites" to Category(2, 1))
        val x = sum("progress" to Category(2, 1, "p"))
        assertTrue(SetupSourceChoice.decide(d, x) is Decision.Ask)
    }

    @Test fun defaultSelectionIsMoreRecent() {
        val older = sum("favorites" to Category(1, 1_000))
        val newer = sum("favorites" to Category(1, 9_000))
        assertEquals(Source.Drive, SetupSourceChoice.defaultSelection(drive = newer, dsync = older))
        assertEquals(Source.Dispatcharr, SetupSourceChoice.defaultSelection(drive = older, dsync = newer))
        assertEquals(Source.Dispatcharr, SetupSourceChoice.defaultSelection(drive = older, dsync = older))
    }

    @Test fun recencyIgnoresEmptyCategories() {
        val d = sum("favorites" to Category(1, 1_000), "settings" to Category(0, 99_000))
        val x = sum("favorites" to Category(1, 5_000))
        assertEquals(1_000L, d.updatedAt)
        assertEquals(Source.Dispatcharr, SetupSourceChoice.defaultSelection(d, x))
    }

    @Test fun countLineShowsNonZeroInOrder() {
        val s = sum(
            "reminders" to Category(3, 1),
            "favorites" to Category(12, 1),
            "progress" to Category(8, 1),
            "watchlist" to Category(0, 1),
        )
        assertEquals("12 favorites, 8 in progress, 3 reminders", SetupSourceChoice.countLine(s))
    }

    @Test fun countLineCapsAtFourThenAndMore() {
        val s = sum(
            "favorites" to Category(12, 1), "progress" to Category(8, 1), "reminders" to Category(3, 1),
            "watchlist" to Category(2, 1), "settings" to Category(40, 1),
        )
        assertEquals("12 favorites, 8 in progress, 3 reminders, 2 in Watchlist, and more", SetupSourceChoice.countLine(s))
    }

    @Test fun countLineExactlyFourHasNoAndMore() {
        val s = sum(
            "favorites" to Category(1, 1), "progress" to Category(1, 1), "reminders" to Category(1, 1), "collections" to Category(1, 1),
        )
        assertEquals("1 favorite, 1 in progress, 1 reminder, 1 collection", SetupSourceChoice.countLine(s))
    }

    @Test fun countLineEmpty() {
        assertEquals("Nothing saved yet", SetupSourceChoice.countLine(Summary.EMPTY))
    }

    @Test fun updatedLine() {
        val now = 10_000_000_000L
        assertEquals("Updated just now", SetupSourceChoice.updatedLine(now - 5_000, now))
        assertEquals("Updated 1 minute ago", SetupSourceChoice.updatedLine(now - 60_000, now))
        assertEquals("Updated 5 minutes ago", SetupSourceChoice.updatedLine(now - 300_000, now))
        assertEquals("Updated 2 hours ago", SetupSourceChoice.updatedLine(now - 7_200_000, now))
        assertEquals("Updated 3 days ago", SetupSourceChoice.updatedLine(now - 3L * 86_400_000, now))
        assertEquals("Not updated yet", SetupSourceChoice.updatedLine(0, now))
    }

    @Test fun allSharedCategoriesProvablyEqualPreferDispatcharr() {
        // Every shared category now carries a canonical fingerprint on both sides.
        val cats = SetupSourceChoice.SHARED.mapIndexed { i, n -> n to Category(i + 1, 10, "fp$i") }
        val d = Summary(cats.toMap())
        val x = Summary(cats.associate { (n, c) -> n to c.copy(updatedAt = 99) })
        assertEquals(Decision.UseDispatcharr, SetupSourceChoice.decide(d, x))
    }

    @Test fun oneUnresolvedCategoryStillAsks() {
        val cats = SetupSourceChoice.SHARED.mapIndexed { i, n -> n to Category(i + 1, 10, "fp$i") }.toMap()
        val d = Summary(cats + ("favorites" to Category(1, 10, null)))
        assertTrue(SetupSourceChoice.decide(d, Summary(cats)) is Decision.Ask)
    }

    @Test fun backupUpdateCopy() {
        assertEquals("Your backup needs an update", SetupSourceChoice.UPDATE_TITLE)
        assertEquals("Update backup", SetupSourceChoice.UPDATE_ACCEPT)
        assertEquals("Not now", SetupSourceChoice.UPDATE_NOT_NOW)
        assertTrue(SetupSourceChoice.UPDATE_BODY.startsWith("AerioTV backups changed format."))
        assertTrue(SetupSourceChoice.UPDATE_BODY.endsWith("does not change your favorites, progress, or settings."))
    }

    @Test fun backupUpdateOfferDeclineAndSettingsOncePerSession() {
        BackupUpdateOffer.resetForTest()
        assertTrue(BackupUpdateOffer.shouldOffer(true, true, accepted = false, playlistId = "p"))
        assertTrue(!BackupUpdateOffer.shouldOffer(false, true, false, "p"))
        assertTrue(!BackupUpdateOffer.shouldOffer(true, false, false, "p"))
        assertTrue(!BackupUpdateOffer.shouldOffer(true, true, accepted = true, playlistId = "p"))
        assertTrue(!BackupUpdateOffer.shouldOfferInSettings(driveOn = false, true, true, false, "p"))
        assertTrue(BackupUpdateOffer.shouldOfferInSettings(true, true, true, false, "p"))
        BackupUpdateOffer.markSettingsShown("p")
        assertTrue(!BackupUpdateOffer.shouldOfferInSettings(true, true, true, false, "p"))
        BackupUpdateOffer.decline("q")
        assertTrue(!BackupUpdateOffer.shouldOffer(true, true, false, "q"))
        BackupUpdateOffer.resetForTest()
    }

    @Test fun copyHasNoEmDash() {
        listOf(SetupSourceChoice.TITLE, SetupSourceChoice.FOOTER, SetupSourceChoice.UPDATE_TITLE, SetupSourceChoice.UPDATE_BODY).forEach { assertTrue(!it.contains('\u2014')) }
    }
}
