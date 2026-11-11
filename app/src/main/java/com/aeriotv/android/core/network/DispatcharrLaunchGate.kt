package com.aeriotv.android.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Launch order (Logan 2026-11-10, Apple parity): with Drive Sync and
 * Credentials on, the credentials pull runs before the first Dispatcharr
 * request, so a device holding a stale key takes the synced one instead of
 * spending the server's failed-key attempts on its own first.
 *
 * Armed at the very start of the app process; the first requests (API calls
 * and stream opens) wait until the pull is done ([open]) or [MAX_WAIT_MS]
 * after arming, whichever comes first, then never wait again. When the pull
 * does not apply (sync off, Credentials off, not signed in) it opens at once.
 */
object DispatcharrLaunchGate {
    /** Apple parity (2026-11-10): its iCloud merge landed 3.2 to 3.3 s after launch on the Apple TV, so 2 s missed it. */
    const val MAX_WAIT_MS = 4_000L

    private var opened = CompletableDeferred<Unit>()
    /** Clock time the wait ends (0 = not armed). */
    @Volatile private var deadline = 0L

    /** Clock in ms (tests replace it). */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    fun arm() {
        if (deadline == 0L && !opened.isCompleted) deadline = clock() + MAX_WAIT_MS
    }

    fun open() {
        opened.complete(Unit)
    }

    /** True while requests should still wait. */
    fun waiting(): Boolean = deadline != 0L && !opened.isCompleted && clock() < deadline

    /** Waits (suspending) until the pull is done or the deadline passes. */
    suspend fun await() {
        if (!waiting()) return
        withTimeoutOrNull((deadline - clock()).coerceAtLeast(0L)) { opened.await() }
    }

    /** Same, blocking (stream threads). */
    fun awaitBlocking() {
        if (!waiting()) return
        runBlocking { await() }
    }

    fun resetForTests() {
        opened = CompletableDeferred()
        deadline = 0L
        clock = { System.nanoTime() / 1_000_000 }
    }
}
