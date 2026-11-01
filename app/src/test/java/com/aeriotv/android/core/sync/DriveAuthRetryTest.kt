package com.aeriotv.android.core.sync

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Field run 2026-11-01: every Drive call answered 401 with the same token
 * (43 by the end), and the setup probe read the failed listing as an empty
 * Drive, which decided UseDispatcharr and replaced the backup.
 */
class DriveAuthRetryTest {

    private class FakeAuth(private val next: String?) : DriveAuthHandler {
        var refreshes = 0
        var marked: String? = null
        var swapped: Pair<String, String>? = null
        override fun needsSignIn() = marked != null
        override fun current(token: String) = swapped?.takeIf { it.first == token }?.second ?: token
        override suspend fun refresh(stale: String): String? {
            refreshes++
            return next?.also { swapped = stale to it }
        }
        override fun markNeedsSignIn(reason: String) { marked = reason }
    }

    /** Answers by the bearer token: [good] gets [okBody], anything else 401; [code] overrides for every call. */
    private fun client(auth: DriveAuthHandler?, good: String, okBody: String, seen: MutableList<String>, code: Int? = null): DriveAppDataClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val t = chain.request().header("Authorization").orEmpty().removePrefix("Bearer ")
            seen += t
            val c = code ?: if (t == good) 200 else 401
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(c).message("x")
                .body((if (c == 200) okBody else "{}").toResponseBody("application/json".toMediaType())).build()
        }).build()
        return DriveAppDataClient(http, auth)
    }

    private val listing = """{"files":[{"id":"f1","name":"favorites.v1.json"}]}"""

    @Test fun a401RefreshesOnceAndRetries() = runBlocking {
        val auth = FakeAuth(next = "fresh")
        val seen = mutableListOf<String>()
        val files = client(auth, "fresh", listing, seen).listAll("stale")
        assertEquals(listOf("f1" to "favorites.v1.json"), files)
        assertEquals(1, auth.refreshes)
        assertEquals(listOf("stale", "fresh"), seen)
        assertNull(auth.marked)
        // A later call holding the stale token goes straight to the new one.
        seen.clear()
        client(auth, "fresh", listing, seen).listAll("stale")
        assertEquals(listOf("fresh"), seen)
        assertEquals(1, auth.refreshes)
    }

    @Test fun failedRefreshMarksNeedsSignInAndStopsCalls() = runBlocking {
        val auth = FakeAuth(next = null)
        val seen = mutableListOf<String>()
        val c = client(auth, "never", listing, seen)
        try { c.listAll("stale"); fail("expected needs sign-in") } catch (e: DriveNeedsSignInException) {}
        assertEquals(1, auth.refreshes)
        assertTrue(auth.needsSignIn())
        // No further network call until the user signs in.
        seen.clear()
        try { c.findFileId("stale", "favorites.v1.json"); fail("expected needs sign-in") } catch (e: DriveNeedsSignInException) {}
        assertTrue(seen.isEmpty())
        assertTrue(c.upload("stale", "favorites.v1.json", "{}").isFailure)
        assertTrue(seen.isEmpty())
    }

    @Test fun a401AfterTheRefreshMarksNeedsSignIn() = runBlocking {
        val auth = FakeAuth(next = "fresh")
        val seen = mutableListOf<String>()
        try { client(auth, "neither", listing, seen).listAll("stale"); fail() } catch (e: DriveNeedsSignInException) {}
        assertEquals(listOf("stale", "fresh"), seen)
        assertEquals("401 after refresh", auth.marked)
    }

    @Test fun probeErrorIsNotEmpty() = runBlocking {
        // A failed listing throws: it can never read as an empty Drive.
        for (code in listOf(401, 403, 500, 503)) {
            try {
                client(null, "t", listing, mutableListOf(), code = code).listAll("t")
                fail("HTTP $code read as a listing")
            } catch (e: java.io.IOException) {
                assertFalse(SetupSourceChoice.probeReason(e).isEmpty())
            }
        }
        // A 401 is not a missing file either (it used to trigger a create).
        try { client(null, "t", listing, mutableListOf(), code = 401).findFileId("t", "x"); fail() } catch (e: DriveAuthException) {}
        assertEquals("[DRIVE] setup probe failed: Unauthorized (treated as unknown, not empty)",
            SetupSourceChoice.driveProbeFailedLine(DriveAuthException("listAll")))
        // Only an answered, empty listing is empty.
        assertTrue(client(null, "t", """{"files":[]}""", mutableListOf()).listAll("t").isEmpty())
    }

    @Test fun unknownDriveDecidesNothing() {
        val dsync = SetupSourceChoice.Summary(mapOf("settings" to SetupSourceChoice.Category(7, 1L, "f"), "progress" to SetupSourceChoice.Category(1, 1L, "p")))
        // Unknown Drive: no decision, so no UseDispatcharr with replace and no forced replace.
        assertNull(SetupSourceChoice.decideKnown(null, dsync))
        // The old reading of a failed probe (empty) decided UseDispatcharr, which replaced the backup.
        assertEquals(SetupSourceChoice.Decision.UseDispatcharr, SetupSourceChoice.decideKnown(SetupSourceChoice.Summary.EMPTY, dsync))
    }
}
