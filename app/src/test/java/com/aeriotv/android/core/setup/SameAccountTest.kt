package com.aeriotv.android.core.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun knownUserIsTheRowThenTheFactsThenTheDefaultProfile() {
        assertEquals("row", FixLogin.knownUser(" row ", "facts", "profile"))
        assertEquals("facts", FixLogin.knownUser("", "facts", "profile"))
        assertEquals("profile", FixLogin.knownUser(null, "  ", "profile"))
        assertNull(FixLogin.knownUser(null, "", " "))
    }
}
