package com.aeriotv.android.feature.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What's New top fade (2026-11-12, Apple parity): about one line tall, as a
 * fraction of the list's height, never past the whole list.
 */
class WhatsNewFadeTest {
    @Test fun theFadeIsAboutOneLineOfTheList() {
        assertEquals(24, WhatsNewFade.HEIGHT_DP)
        assertEquals(0.1f, WhatsNewFade.stop(fadePx = 60f, heightPx = 600f), 0.0001f)
        // A list shorter than the fade fades over all of it; no height, no fade.
        assertEquals(1f, WhatsNewFade.stop(fadePx = 60f, heightPx = 30f), 0.0001f)
        assertEquals(0f, WhatsNewFade.stop(fadePx = 60f, heightPx = 0f), 0.0001f)
    }
}
