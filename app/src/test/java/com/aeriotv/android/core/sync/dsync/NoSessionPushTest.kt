package com.aeriotv.android.core.sync.dsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-session `profiles` push (launch picker add or remove) makes the same
 * list call a session starts with, and writes only when no legacy key is
 * listed (review finding A4, 2026-11-11). Otherwise the change stays pending
 * for the next session, which moves the legacy keys first.
 */
class NoSessionPushTest {

    private val main = DsyncKeyName.main("Kids Room")!!

    @Test fun legacyKeysListedMeansNoPutAndTheChangeWaitsForTheSession() {
        val listed = listOf(DsyncKeyName.LEGACY_MAIN, "${DsyncKeyName.LEGACY_MAIN}.favorites")
        assertFalse(DsyncKeyName.noSessionPushAllowed(listed))
        // The session that picks the change up moves them first.
        assertEquals(listed.sorted(), DsyncKeyName.renamePlan(listed, main))
    }

    @Test fun aLegacyV1OrV2KeyAloneAlsoWaits() {
        assertFalse(DsyncKeyName.noSessionPushAllowed(listOf("${DsyncKeyName.LEGACY_MAIN}.progress")))
        assertFalse(DsyncKeyName.noSessionPushAllowed(listOf(main, DsyncKeyName.LEGACY_MAIN)))
    }

    @Test fun noLegacyKeysMeansItPushesAsBefore() {
        assertTrue(DsyncKeyName.noSessionPushAllowed(listOf(main)))
        assertTrue(DsyncKeyName.noSessionPushAllowed(listOf(main, "other.app")))
        // A user with no App Data yet: nothing to move, the push creates the key.
        assertTrue(DsyncKeyName.noSessionPushAllowed(emptyList()))
    }

    @Test fun aKeyListThatCouldNotBeReadWaitsToo() {
        assertFalse(DsyncKeyName.noSessionPushAllowed(null))
    }
}
