package com.aeriotv.android.core.profiles

import com.aeriotv.android.core.network.DispatcharrRequestGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * APP8-1/APP8-2 (Apple parity, 2026-11-12): only a USER-chosen switch (the
 * switcher, the launch picker) opens the held-key sign-in form. An app-made
 * switch (a removal, a synced tombstone, a switch back) never opens a form
 * and never fails because the target's key is held: it lands on the target
 * with no sign-in and nothing sent with that key.
 */
class AppMadeSwitchTest {
    private val default = DispatcharrProfile(id = "def", playlistId = "P1", displayName = "Parent", username = "parent", isDefault = true)
    private val adult = DispatcharrProfile(id = "sam", playlistId = "P1", displayName = "Sam", username = "sam", apiKey = "sam-key", userLevel = 1, role = ProfileRole.User)
    private val kid = DispatcharrProfile(id = "kid", playlistId = "P1", displayName = "Kid", username = "kid", userLevel = 1, role = ProfileRole.Child)
    private val isRejected: (String) -> Boolean = DispatcharrRequestGate::isKeyRejected

    @Before fun setUp() = DispatcharrRequestGate.resetForTests()
    @After fun tearDown() = DispatcharrRequestGate.resetForTests()

    @Test fun removingTheActiveProfileWhileTheDefaultsKeyIsHeldLandsOnTheDefault() {
        DispatcharrRequestGate.markKeyRejected("P1", "own-key", "Home")
        // The removal switches to the default on its own: no form, no failure.
        assertEquals("def", RejectedKeySwitch.appMadeTarget(default, "def", signInUnusable = false))
        assertEquals(RejectedKeySwitch.Step.LandHeld, RejectedKeySwitch.step(default, "own-key", isRejected, chosenByUser = false))
        // The default stays held: nothing is sent with its key, and its prompt handles it.
        assertEquals(true, DispatcharrRequestGate.isKeyRejected("own-key"))
        // Chosen by the user, the same target opens the form.
        assertEquals(RejectedKeySwitch.Step.Form, RejectedKeySwitch.step(default, "own-key", isRejected, chosenByUser = true))
    }

    @Test fun aKeyNotHeldSignsInAsUsual() {
        assertEquals(RejectedKeySwitch.Step.SignIn, RejectedKeySwitch.step(default, "own-key", isRejected, chosenByUser = false))
        assertEquals(RejectedKeySwitch.Step.SignIn, RejectedKeySwitch.step(adult, "own-key", isRejected, chosenByUser = true))
    }

    @Test fun aSwitchBackToAProfileThatCannotSignInGivesWayToTheDefaultButAChildNeverEscapes() {
        assertEquals("def", RejectedKeySwitch.appMadeTarget(adult, "def", signInUnusable = true))
        assertEquals("sam", RejectedKeySwitch.appMadeTarget(adult, "def", signInUnusable = false))
        // A Child is kept (the switch then does not run): never landed on another profile.
        assertEquals("kid", RejectedKeySwitch.appMadeTarget(kid, "def", signInUnusable = true))
    }
}
