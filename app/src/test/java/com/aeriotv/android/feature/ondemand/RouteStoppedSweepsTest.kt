package com.aeriotv.android.feature.ondemand

import com.aeriotv.android.feature.ondemand.RouteStoppedSweeps.Sweeps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** On Demand sweeps across a server address change (stuck save 2026-11-12). */
class RouteStoppedSweepsTest {
    private val none = Sweeps(false, false)
    private val both = Sweeps(true, true)

    @Test
    fun aChangeOnTheActivePlaylistStopsItsRunningSweeps() {
        val r = RouteStoppedSweeps()
        assertEquals(both, r.onStart("p", "p", both))
        assertEquals(Sweeps(true, false), RouteStoppedSweeps().onStart("p", "p", Sweeps(true, false)))
    }

    @Test
    fun aChangeOnAnotherPlaylistStopsNothing() {
        val r = RouteStoppedSweeps()
        assertEquals(none, r.onStart("q", "p", both))
        assertEquals(none, r.onLanded("q", "p", none))
    }

    @Test
    fun theLandingStartsTheStoppedSweepsAgainOnce() {
        val r = RouteStoppedSweeps()
        r.onStart("p", "p", Sweeps(true, false))
        assertEquals(Sweeps(true, false), r.onLanded("p", "p", none))
        assertEquals(none, r.onLanded("p", "p", none))
    }

    @Test
    fun aSweepAlreadyRunningAtTheLandingIsNotStartedTwice() {
        val r = RouteStoppedSweeps()
        r.onStart("p", "p", both)
        assertEquals(Sweeps(false, true), r.onLanded("p", "p", Sweeps(true, false)))
    }

    @Test
    fun nothingRunningMeansNothingToStartAgain() {
        val r = RouteStoppedSweeps()
        assertEquals(none, r.onStart("p", "p", none))
        assertEquals(none, r.onLanded("p", "p", none))
    }

    @Test
    fun twoStartsBeforeTheLandingKeepBothStoppedSweeps() {
        val r = RouteStoppedSweeps()
        r.onStart("p", "p", Sweeps(true, false))
        r.onStart("p", "p", Sweeps(false, true))
        assertEquals(both, r.onLanded("p", "p", none))
    }

    @Test
    fun anotherActivePlaylistAtTheLandingStartsNothing() {
        val r = RouteStoppedSweeps()
        r.onStart("p", "p", both)
        assertEquals(none, r.onLanded("p", "q", none))
    }

    @Test
    fun aSweepAskedForDuringTheChangeWaitsForTheLanding() {
        // The held active profile left for the default one restarts On
        // Demand while the save is still running: that sweep would read the
        // row before the new address is written.
        val r = RouteStoppedSweeps()
        assertTrue(r.holdStart("p", changing = true, movies = true))
        assertTrue(r.holdStart("p", changing = true, movies = false))
        assertEquals(both, r.onLanded("p", "p", none))
    }

    @Test
    fun noChangeInProgressLetsTheSweepStart() {
        val r = RouteStoppedSweeps()
        assertFalse(r.holdStart("p", changing = false, movies = true))
        assertFalse(r.holdStart(null, changing = true, movies = true))
        assertEquals(none, r.onLanded("p", "p", none))
    }

    @Test
    fun aStoppedAndAHeldSweepBothStartAtTheLanding() {
        val r = RouteStoppedSweeps()
        r.onStart("p", "p", Sweeps(true, false))
        r.holdStart("p", changing = true, movies = false)
        assertEquals(both, r.onLanded("p", "p", none))
    }
}
