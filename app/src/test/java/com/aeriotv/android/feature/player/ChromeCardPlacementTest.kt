package com.aeriotv.android.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The standalone channel card never lands in the top bar's row on a touch
 * device (2026-11-10: it jumped there while the chrome faded out and was
 * drawn across Fullscreen and More). Its position is decided by width alone.
 */
class ChromeCardPlacementTest {
    @Test fun compactTouchWidthsAlwaysUnderTheBar() {
        // Nothing Phone portrait (411 dp), Fold cover screen, small tablets in split view.
        for (w in listOf(320, 360, 411, 499)) assertTrue("$w dp", chromeCardUnderBar(isTv = false, screenWidthDp = w))
    }

    @Test fun wideTouchWidthsUseTheInlineCardInstead() {
        // Phone landscape, Fold inner screen, tablets: the card is inline in the bar
        // (weighted between the button groups), and the standalone copy never shows.
        for (w in listOf(500, 673, 882, 1280)) assertFalse("$w dp", chromeCardUnderBar(isTv = false, screenWidthDp = w))
    }

    @Test fun tvKeepsItsOwnPosition() {
        assertFalse(chromeCardUnderBar(isTv = true, screenWidthDp = 411))
        assertFalse(chromeCardUnderBar(isTv = true, screenWidthDp = 960))
    }
}
