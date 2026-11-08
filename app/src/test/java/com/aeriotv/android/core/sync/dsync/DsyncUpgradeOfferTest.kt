package com.aeriotv.android.core.sync.dsync

import com.aeriotv.android.core.sync.dsync.DsyncUpgradeOffer.Decision
import com.aeriotv.android.core.sync.dsync.DsyncUpgradeOffer.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsyncUpgradeOfferTest {
    private fun d(vc: Int = 70, last: Int = 69, isD: Boolean = true, on: Boolean = false, declined: Boolean = false, r: Record) =
        DsyncUpgradeOffer.decide(vc, last, isD, on, declined, r)

    @Test fun foundShowsOptIn() = assertEquals(Decision.ShowOptIn, d(r = Record.Found))
    @Test fun missingShowsMigrate() = assertEquals(Decision.ShowMigrate, d(r = Record.Missing))
    @Test fun errorRetriesWithoutStamp() {
        assertEquals(Decision.Retry, d(r = Record.Error))
        assertFalse(DsyncUpgradeOffer.stamps(Decision.Retry))
    }
    @Test fun neverHadOfferFires() = assertEquals(Decision.ShowMigrate, d(last = 0, r = Record.Missing))
    @Test fun sameVersionSkips() {
        assertEquals(Decision.AlreadyEvaluated, d(last = 70, r = Record.Found))
        assertNull(DsyncUpgradeOffer.logLine(Record.Found, Decision.AlreadyEvaluated))
    }
    @Test fun notDispatcharr() = assertEquals(Decision.NotDispatcharr, d(isD = false, r = Record.Found))
    @Test fun alreadyOn() = assertEquals(Decision.AlreadyOn, d(on = true, r = Record.Found))
    @Test fun declinedBefore() = assertEquals(Decision.DeclinedBefore, d(declined = true, r = Record.Found))
    @Test fun gateProbesOnlyWhenEligible() {
        assertNull(DsyncUpgradeOffer.gate(70, 69, true, false, false))
        assertEquals(Decision.AlreadyOn, DsyncUpgradeOffer.gate(70, 69, true, true, false))
    }
    @Test fun stampsShownAndSkips() {
        assertTrue(DsyncUpgradeOffer.stamps(Decision.ShowOptIn))
        assertTrue(DsyncUpgradeOffer.stamps(Decision.NotDispatcharr))
    }
    @Test fun logAndCopy() {
        assertEquals("[DSYNC] upgrade offer: record=found decision=shown-optin", DsyncUpgradeOffer.logLine(Record.Found, Decision.ShowOptIn))
        assertTrue(DsyncUpgradeOffer.optInBody(true).endsWith("Turn it on here too, alongside Google Drive?"))
        assertTrue(DsyncUpgradeOffer.optInBody(false).endsWith("Turn it on here too?"))
    }
}
