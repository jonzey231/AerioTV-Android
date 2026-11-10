package com.aeriotv.android.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Only one caller probes a given key at a time: the others await its result,
 * so N simultaneous 401s cost the server's failed-key budget one probe.
 */
class KeyProbeSingleFlightTest {
    @Test fun concurrentCallersShareOneRun() = runBlocking(Dispatchers.Default) {
        val flight = SingleFlight<String, String>()
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val callers = (1..6).map {
            async {
                flight.run("server|key") {
                    runs.incrementAndGet()
                    release.await()
                    "rejected"
                }
            }
        }
        while (runs.get() == 0) yield()
        // Give the other callers time to join the run in flight.
        kotlinx.coroutines.delay(100)
        release.complete(Unit)
        assertEquals(List(6) { "rejected" }, callers.awaitAll())
        assertEquals(1, runs.get())
        assertEquals(0, flight.size)
    }

    @Test fun differentKeysRunSeparately() = runBlocking(Dispatchers.Default) {
        val flight = SingleFlight<String, String>()
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val a = async { flight.run("server|a") { runs.incrementAndGet(); release.await(); "a" } }
        val b = async { flight.run("server|b") { runs.incrementAndGet(); release.await(); "b" } }
        while (runs.get() < 2) yield()
        release.complete(Unit)
        assertEquals("a", a.await())
        assertEquals("b", b.await())
        assertEquals(2, runs.get())
    }

    @Test fun aFinishedRunIsNotReused() = runBlocking {
        val flight = SingleFlight<String, Int>()
        val runs = AtomicInteger()
        assertEquals(1, flight.run("k") { runs.incrementAndGet() })
        assertEquals(2, flight.run("k") { runs.incrementAndGet() })
    }

    @Test fun theLeadersFailureReachesEveryWaiter() = runBlocking(Dispatchers.Default) {
        val flight = SingleFlight<String, String>()
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val callers = (1..3).map {
            async {
                runCatching {
                    flight.run("k") {
                        runs.incrementAndGet()
                        release.await()
                        throw DispatcharrError.RateLimited(30)
                    }
                }
            }
        }
        while (runs.get() == 0) yield()
        kotlinx.coroutines.delay(100)
        release.complete(Unit)
        callers.awaitAll().forEach { r ->
            assertTrue("${r.exceptionOrNull()}", r.exceptionOrNull() is DispatcharrError.RateLimited)
        }
        assertEquals(1, runs.get())
    }

    @Test fun aCancelledLeaderHandsTheRunToAWaiter() = runBlocking(Dispatchers.Default) {
        val flight = SingleFlight<String, String>()
        val runs = AtomicInteger()
        val leaderStarted = CompletableDeferred<Unit>()
        val leader = async {
            flight.run("k") {
                runs.incrementAndGet()
                leaderStarted.complete(Unit)
                CompletableDeferred<Unit>().await() // never finishes on its own
                "leader"
            }
        }
        leaderStarted.await()
        val waiter = async { flight.run("k") { runs.incrementAndGet(); "waiter" } }
        kotlinx.coroutines.delay(100)
        leader.cancel()
        assertEquals("waiter", waiter.await())
        assertEquals(2, runs.get())
        try {
            leader.await()
            fail("the cancelled leader must not return a value")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }
}
