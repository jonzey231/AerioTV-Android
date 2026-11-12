package com.aeriotv.android.core.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 11 (security, 2026-11-11): the rejected-key sign-in form and the TV's
 * Fix Login receive reconnect only the playlist's current account, for every
 * account. With no known user a Child is refused.
 */
class SameAccountTest {

    @Test
    fun anotherAccountIsRefused() {
        assertFalse(FixLogin.sameAccount("parent", 0, "other", 7, childInvolved = false))
        assertFalse(FixLogin.sameAccount("kid", 0, "parent", 1, childInvolved = true))
    }

    @Test
    fun theSameAccountSucceedsWithoutCase() {
        assertTrue(FixLogin.sameAccount("Parent", 0, "parent", 3, childInvolved = false))
        assertTrue(FixLogin.sameAccount(" kid ", 0, "KID", 9, childInvolved = true))
    }

    @Test
    fun aRecordedUserIdMustMatchToo() {
        assertTrue(FixLogin.sameAccount("parent", 3, "parent", 3, childInvolved = false))
        // Same name, different account (deleted and recreated): refused.
        assertFalse(FixLogin.sameAccount("parent", 3, "parent", 4, childInvolved = false))
        // Only an id is known: it decides.
        assertTrue(FixLogin.sameAccount(null, 3, "renamed", 3, childInvolved = true))
        assertFalse(FixLogin.sameAccount("", 3, "other", 5, childInvolved = false))
    }

    @Test
    fun withNoKnownUserAChildIsRefusedAndAnAdultAllowed() {
        assertFalse(FixLogin.sameAccount(null, 0, "parent", 1, childInvolved = true))
        assertFalse(FixLogin.sameAccount("  ", 0, "parent", 1, childInvolved = true))
        assertTrue(FixLogin.sameAccount(null, 0, "parent", 1, childInvolved = false))
    }

    @Test
    fun fixLoginWithAnotherAccountIsRefused() {
        // The TV's playlist is the kid's account; the phone sent the parent's sign-in.
        val known = FixLogin.knownUser("kid", "kid", null)
        assertFalse(FixLogin.sameAccount(known, 12, "parent", 1, childInvolved = true))
        // The same account sent again (a new key) is accepted.
        assertTrue(FixLogin.sameAccount(known, 12, "kid", 12, childInvolved = true))
    }

    @Test
    fun knownAccountIsTheRecordedIdentityThenTheRowThenTheDefaultProfile() {
        val recorded = com.aeriotv.android.core.profiles.ProfileHostHold.Identity(7, "Parent")
        assertEquals(FixLogin.KnownAccount("Parent", 7), FixLogin.knownAccount(recorded, "row", "profile"))
        assertEquals(FixLogin.KnownAccount("row", 0), FixLogin.knownAccount(null, " row ", "profile"))
        assertEquals(FixLogin.KnownAccount("profile", 0), FixLogin.knownAccount(null, "  ", "profile"))
        assertEquals(FixLogin.KnownAccount(null, 0), FixLogin.knownAccount(null, "", " "))
        // A recorded id with a blank name still decides by the id.
        assertEquals(FixLogin.KnownAccount("row", 7), FixLogin.knownAccount(com.aeriotv.android.core.profiles.ProfileHostHold.Identity(7, ""), "row", null))
    }

    @Test
    fun anotherProfilesNameNeverStandsInForTheDefault() {
        // No recorded identity and an API-key row (no username): the default
        // profile's name decides, never a name a non-default profile wrote.
        val known = FixLogin.knownAccount(null, null, "kid")
        assertFalse(FixLogin.sameAccount(known.username, known.userId, "parent", 1, childInvolved = true))
        assertTrue(FixLogin.sameAccount(known.username, known.userId, "KID", 12, childInvolved = true))
    }

    @Test
    fun theRecordedIdIsComparedWheneverThereIsOne() {
        val known = FixLogin.knownAccount(com.aeriotv.android.core.profiles.ProfileHostHold.Identity(12, "kid"), "kid", "kid")
        // Same name, another account (deleted and recreated on the server): refused.
        assertFalse(FixLogin.sameAccount(known.username, known.userId, "kid", 13, childInvolved = false))
        assertTrue(FixLogin.sameAccount(known.username, known.userId, "kid", 12, childInvolved = false))
    }
}
