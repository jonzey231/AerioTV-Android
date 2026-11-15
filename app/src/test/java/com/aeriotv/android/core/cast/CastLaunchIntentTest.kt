package com.aeriotv.android.core.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner rule 2026-11-14: a basic cast must not need the app. A "Google Cast"
 * pick is a plain cast to the web receiver; only "AerioTV on TV" launches the
 * AerioTV Android TV app.
 */
class CastLaunchIntentTest {

    @Test fun theTvAppDeclinesOnlyAPlainCast() {
        assertFalse(CastLaunchIntent.tvAppAccepts(CastLaunchIntent.WebReceiver.wire))
        assertTrue(CastLaunchIntent.tvAppAccepts(CastLaunchIntent.TvApp.wire))
        // A sender that names no intent (older builds, other platforms) launches as before.
        assertTrue(CastLaunchIntent.tvAppAccepts(null))
        assertTrue(CastLaunchIntent.tvAppAccepts(""))
        assertTrue(CastLaunchIntent.tvAppAccepts("something else"))
        // Whitespace around the wire value still reads.
        assertFalse(CastLaunchIntent.tvAppAccepts(" aeriotv-launch:web "))
    }

    @Test fun aSessionEndReturnsToAPlainCastUnlessASwitchFromAerioTvOnTvWaits() {
        assertEquals(CastLaunchIntent.WebReceiver, CastLaunchIntent.afterSessionEnd(null))
        assertEquals(CastLaunchIntent.WebReceiver, CastLaunchIntent.afterSessionEnd(CastLaunchIntent.WebReceiver))
        assertEquals(CastLaunchIntent.TvApp, CastLaunchIntent.afterSessionEnd(CastLaunchIntent.TvApp))
    }

    @Test fun theWireValuesRoundTripAndCarryNoSecret() {
        CastLaunchIntent.entries.forEach { assertEquals(it, CastLaunchIntent.of(it.wire)) }
        assertEquals(null, CastLaunchIntent.of(null))
        CastLaunchIntent.entries.forEach { assertTrue(it.wire.startsWith("aeriotv-launch:")) }
    }
}
