package com.aeriotv.android.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** GH #152: installer x signer x flavor decision table for the GitHub updater. */
class UpdaterGateTest {
    private val github = setOf(UpdaterGate.GITHUB_RELEASE_SIGNER_SHA256)
    private val play = setOf("1".repeat(64))
    private val debug = setOf("2".repeat(64))

    @Test
    fun decisionTable() {
        val installers = listOf(null, "com.android.vending", "com.android.packageinstaller", "com.amazon.venezia", "adb")
        data class Row(val flavor: Boolean, val signers: Set<String>, val expected: Boolean, val name: String)
        val rows = listOf(
            Row(true, github, true, "github flavor, GitHub signer"),
            Row(true, play, false, "github flavor, Play signer"),
            Row(true, debug, false, "github flavor, debug signer"),
            Row(true, emptySet(), false, "github flavor, unreadable signer"),
            Row(false, github, false, "play flavor, GitHub signer"),
            Row(false, play, false, "play flavor, Play signer"),
        )
        for (r in rows) for (i in installers) {
            assertEquals("${r.name}, installer=$i", r.expected, UpdaterGate.isEligible(r.flavor, i, r.signers))
        }
    }

    @Test
    fun upperCaseDigestStillMatches() =
        assertEquals(true, UpdaterGate.isEligible(true, "com.android.vending", setOf(UpdaterGate.GITHUB_RELEASE_SIGNER_SHA256.uppercase())))

    @Test
    fun managerConstantMatchesGate() =
        assertEquals(UpdaterGate.GITHUB_RELEASE_SIGNER_SHA256, GithubUpdateManager.UPLOAD_KEY_SHA256)
}
