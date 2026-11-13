package com.aeriotv.android.core.data.repository

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Nothing Phone sequence of 2026-11-13: a guide re-sweep is paging the
 * playlist's old server when Edit Playlist moves the playlist to another
 * address with another key. Every request reads the playlist's key when it
 * goes out (withApiKeyRetry), while the sweep read its address once, at its
 * start; the phone sent the new server's key to the old one.
 */
class RouteChangeJobsTest {
    private val old = "https://old.example.test"
    private val new = "http://127.0.0.1:8781"
    private val id = "10a8b936-0000-0000-0000-000000000000"

    private data class Request(val seq: Int, val address: String, val key: String)

    /** The playlist row, the change marker, and every request the "servers" saw. */
    private inner class World {
        val row = AtomicReference(old to "OLD-KEY")
        val changing: MutableSet<String> = Collections.synchronizedSet(HashSet())
        val requests: MutableList<Request> = Collections.synchronizedList(ArrayList())
        @Volatile var seq = 0
        val guard = RouteGuard(isChanging = { changing.contains(it) }, addresses = { row.get().first to null })

        /** One sweep: the address read once at its start, the key read at every request. */
        suspend fun sweep(days: Int, dayGapMs: Long, firstDayDone: CompletableDeferred<Unit>? = null) {
            val base = row.get().first
            try {
                repeat(days) { day ->
                    val key = row.get().second
                    guard.ensure(id, base, RouteChangeJobs.SWEEP)
                    requests += Request(++seq, base, key)
                    if (day == 2) firstDayDone?.complete(Unit)
                    delay(dayGapMs)
                }
            } catch (e: RouteMoved) {
                // The job ends, as PlaylistRepository's sweep does.
            }
        }

        /** Edit Playlist: the change starts, the save writes the new address and key, the change lands. */
        fun changeStarts(): Int { changing += id; return seq }
        fun saveLands() { row.set(new to "NEW-KEY"); changing -= id }
    }

    private fun World.assertNoLeak(changeStartSeq: Int) {
        val after = requests.filter { it.seq > changeStartSeq }
        assertTrue("a request went to the old address after the change started: $after", after.none { it.address == old })
        assertTrue("an address carried another server's key: $requests", requests.all {
            (it.address == old && it.key == "OLD-KEY") || (it.address == new && it.key == "NEW-KEY")
        })
    }

    @Test
    fun aSweepInFlightNeverReachesTheOldAddressOnceTheChangeStarts_guardAlone() = runBlocking {
        // Even with no stop signal at all (the route-change collector is
        // asynchronous), the guard ends the sweep at its next request.
        val w = World()
        val third = CompletableDeferred<Unit>()
        coroutineScope {
            val sweep = launch { w.sweep(days = 38, dayGapMs = 20, firstDayDone = third) }
            third.await()
            val startSeq = w.changeStarts()
            delay(50) // the verify runs; the sweep would page the old server here
            w.saveLands()
            sweep.join()
            w.assertNoLeak(startSeq)
            assertEquals("the sweep stopped at the change", startSeq, w.requests.size)
        }
    }

    @Test
    fun theSweepStopsWaitsAndStartsAgainAtTheNewAddress() = runBlocking {
        val w = World()
        val jobs = RouteChangeJobs()
        var sweepJob: Job? = null
        coroutineScope {
            fun startSweep() { sweepJob = launch { w.sweep(days = 5, dayGapMs = 20) } }
            val third = CompletableDeferred<Unit>()
            sweepJob = launch { w.sweep(days = 38, dayGapMs = 20, firstDayDone = third) }
            third.await()

            // The change starts: the running sweep stops.
            val startSeq = w.changeStarts()
            val stop = jobs.onStart(id, mapOf(RouteChangeJobs.SWEEP to { startSweep() }))
            assertEquals(setOf(RouteChangeJobs.SWEEP), stop)
            sweepJob?.cancel()
            sweepJob?.join()

            // A sweep asked for during the change (the save's own guide load) waits.
            assertTrue(jobs.hold(id, changing = w.changing.contains(id), kind = RouteChangeJobs.SWEEP) { startSweep() })
            assertEquals(setOf(RouteChangeJobs.SWEEP), jobs.waiting(id))
            delay(60)
            assertTrue("nothing went out during the change", w.requests.none { it.seq > startSeq })

            // The save lands: one sweep starts, at the new address with the new key.
            w.saveLands()
            val start = jobs.onLanded(id, running = emptySet())
            assertEquals(listOf(RouteChangeJobs.SWEEP), start.map { it.first })
            start.forEach { it.second() }
            sweepJob?.join()

            w.assertNoLeak(startSeq)
            val afterward = w.requests.filter { it.seq > startSeq }
            assertEquals(5, afterward.size)
            assertTrue(afterward.all { it.address == new && it.key == "NEW-KEY" })
            assertTrue(jobs.waiting(id).isEmpty())
        }
    }

    @Test
    fun aJobAlreadyRunningAtTheLandingIsNotStartedTwice() {
        val jobs = RouteChangeJobs()
        var started = 0
        jobs.onStart(id, mapOf(RouteChangeJobs.SWEEP to { started++ }, RouteChangeJobs.WALK to { started++ }))
        val start = jobs.onLanded(id, running = setOf(RouteChangeJobs.WALK))
        assertEquals(listOf(RouteChangeJobs.SWEEP), start.map { it.first })
        start.forEach { it.second() }
        assertEquals(1, started)
        assertTrue(jobs.onLanded(id, running = emptySet()).isEmpty())
    }

    @Test
    fun withNoChangeUnderWayNothingWaitsAndNothingStops() {
        val jobs = RouteChangeJobs()
        assertFalse(jobs.hold(id, changing = false, kind = RouteChangeJobs.SWEEP) {})
        assertTrue(jobs.onStart(id, emptyMap()).isEmpty())
        assertTrue(jobs.onLanded("another-playlist", emptySet()).isEmpty())
    }

    @Test
    fun theGuardAcceptsOnlyThePlaylistsOwnAddresses() = runBlocking {
        assertTrue(RouteGuard.sameRoute("http://127.0.0.1:8781/", "http://127.0.0.1:8781", null))
        assertTrue(RouteGuard.sameRoute("HTTP://192.168.1.5:9191", "https://tv.example.test", "http://192.168.1.5:9191/"))
        assertFalse(RouteGuard.sameRoute("https://old.example.test", "http://127.0.0.1:8781", "http://192.168.1.5:9191"))
        val guard = RouteGuard(isChanging = { false }, addresses = { null })
        val gone = runCatching { guard.ensure(id, old, "guide re-sweep") }.exceptionOrNull()
        assertTrue("a deleted playlist's job stops", gone is RouteMoved)
    }
}
