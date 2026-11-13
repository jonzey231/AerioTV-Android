package com.aeriotv.android.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability probe's one-pass-per-playlist guard (stuck save
 * 2026-11-12: the save's probe ran an identity repair, the repair reloaded
 * the channels inline, and the reload's probe joined the probe running the
 * repair, so the save never finished).
 */
class SingleFlightTest {

    @Test
    fun aProbeAskedFromInsideItsOwnPassAnswersAtOnce() = runBlocking {
        val flight = SingleFlight<String>()
        var reentries = 0
        val result = withTimeout(2_000) {
            flight.single("p", reentered = { reentries++; false }) {
                // The repair's inline reload probing the same playlist.
                val inner = flight.single("p", reentered = { reentries++; false }) { error("never runs") }
                assertFalse(inner)
                true
            }
        }
        assertTrue(result)
        assertEquals(1, reentries)
    }

    @Test
    fun aChildCoroutineOfThePassIsInsideToo() = runBlocking {
        val flight = SingleFlight<String>()
        val result = withTimeout(2_000) {
            flight.single("p", reentered = { false }) {
                val child = kotlinx.coroutines.coroutineScope {
                    async { flight.single("p", reentered = { false }) { error("never runs") } }.await()
                }
                assertFalse(child)
                assertTrue(flight.isInside("p"))
                true
            }
        }
        assertTrue(result)
        assertFalse(flight.isInside("p"))
    }

    @Test
    fun anotherPlaylistFromInsideThePassRunsItsOwnPass() = runBlocking {
        val flight = SingleFlight<String>()
        val ran = withTimeout(2_000) {
            flight.single("p", reentered = { false }) {
                flight.single("q", reentered = { false }) { true }
            }
        }
        assertTrue(ran)
    }

    @Test
    fun aReloadLaunchedOutsideThePassJoinsItAndGetsItsAnswer() = runBlocking {
        val flight = SingleFlight<String>()
        val outside = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val passes = AtomicInteger()
        val joined = CompletableDeferred<Boolean>()
        withTimeout(2_000) {
            flight.single("p", reentered = { false }) {
                passes.incrementAndGet()
                // The post-repair reload, launched into another scope: not
                // inside, so it joins and waits for this pass to end.
                outside.launch {
                    joined.complete(flight.single("p", reentered = { false }) { passes.incrementAndGet(); false })
                }
                kotlinx.coroutines.delay(50)
                assertFalse(joined.isCompleted)
                true
            }
        }
        // Joined the pass (its answer, no second pass) or ran after it ended.
        val answer = withTimeout(2_000) { joined.await() }
        assertTrue(answer || passes.get() == 2)
        assertTrue(passes.get() in 1..2)
    }

    @Test
    fun concurrentCallersShareOnePass() = runBlocking {
        val flight = SingleFlight<String>()
        val passes = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            flight.single("p", reentered = { false }) { passes.incrementAndGet(); gate.await(); true }
        }
        while (!flight.isRunning("p")) kotlinx.coroutines.yield()
        val second = async(Dispatchers.Default) {
            flight.single("p", reentered = { false }) { passes.incrementAndGet(); false }
        }
        kotlinx.coroutines.delay(50)
        gate.complete(Unit)
        assertTrue(withTimeout(2_000) { first.await() })
        assertTrue(withTimeout(2_000) { second.await() })
        assertEquals(1, passes.get())
    }

    @Test
    fun aCancelledPassNeverLeavesAJoinerWaiting() = runBlocking {
        val flight = SingleFlight<String>()
        val first = launch(Dispatchers.Default) {
            flight.single("p", reentered = { false }) { kotlinx.coroutines.awaitCancellation() }
        }
        while (!flight.isRunning("p")) kotlinx.coroutines.yield()
        val joiner = async(Dispatchers.Default) { flight.single("p", reentered = { false }) { true } }
        kotlinx.coroutines.delay(50)
        first.cancelAndJoin()
        assertFalse(withTimeout(2_000) { joiner.await() })
        assertFalse(flight.isRunning("p"))
    }
}
