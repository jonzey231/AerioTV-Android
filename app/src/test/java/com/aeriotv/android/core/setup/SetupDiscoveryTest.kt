package com.aeriotv.android.core.setup

import com.aeriotv.android.core.setup.SetupDiscoveryRules.RefreshStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Set up card discovery (Apple f108c4a parity, 2026-11-11): the refresh and
 * resolve timings, and the engine driven by a fake NSD on a virtual clock.
 */
class SetupDiscoveryTest {

    // ---- Rules (same checks as Apple Scripts/setup-tests) ----

    @Test fun refreshPace() {
        val r = SetupDiscoveryRules
        assertEquals("first refresh after 5 s", 5_000L, r.refreshDelayMs(0))
        assertEquals("second after 10 s", 10_000L, r.refreshDelayMs(1))
        assertEquals("third after 20 s", 20_000L, r.refreshDelayMs(2))
        assertEquals("then every 30 s", 30_000L, r.refreshDelayMs(3))
        assertEquals("still every 30 s much later", 30_000L, r.refreshDelayMs(500))
        assertTrue("never faster than every 5 s", (0 until 1000).all { r.refreshDelayMs(it) >= 5_000L })
        var t = 0L
        var n = 0
        while (true) { t += r.refreshDelayMs(n); if (t > 600_000L) break; n++ }
        assertTrue("at most 22 refreshes in 10 minutes: $n", n <= 22)
    }

    @Test fun refreshSteps() {
        val r = SetupDiscoveryRules
        assertEquals(RefreshStep.RECREATE, r.refreshStep(currentLists = false, probeRunning = false, silentProbes = 0))
        assertEquals(RefreshStep.RECREATE, r.refreshStep(currentLists = false, probeRunning = true, silentProbes = 1))
        assertEquals(RefreshStep.START_PROBE, r.refreshStep(currentLists = true, probeRunning = false, silentProbes = 0))
        assertEquals(RefreshStep.REPLACE_PROBE, r.refreshStep(currentLists = true, probeRunning = true, silentProbes = 0))
        assertEquals(RefreshStep.PROBE_TAKES_OVER, r.refreshStep(currentLists = true, probeRunning = true, silentProbes = 1))
        // A stale listing (missed goodbye) clears on the third refresh.
        val steps = mutableListOf<RefreshStep>()
        var probe = false
        var silent = 0
        repeat(5) {
            val s = r.refreshStep(true, probe, silent)
            steps += s
            if (s == RefreshStep.PROBE_TAKES_OVER) return@repeat
            if (s == RefreshStep.REPLACE_PROBE) silent++
            probe = true
        }
        assertEquals(listOf(RefreshStep.START_PROBE, RefreshStep.REPLACE_PROBE, RefreshStep.PROBE_TAKES_OVER), steps.take(3))
    }

    @Test fun resolveBudget() {
        val r = SetupDiscoveryRules
        assertTrue(r.RESOLVE_TIMEOUT_MS in 2_000L..5_000L)
        assertTrue(r.RESOLVE_ATTEMPTS in 2..4)
        assertEquals(1_000L, r.resolveRetryDelayMs(1))
        assertEquals(2_000L, r.resolveRetryDelayMs(2))
        val worst = r.RESOLVE_ATTEMPTS * r.RESOLVE_TIMEOUT_MS + (1 until r.RESOLVE_ATTEMPTS).sumOf { r.resolveRetryDelayMs(it) }
        assertTrue("a hung advert gives up in under 20 s: $worst", worst < 20_000L)
        assertTrue("and before the next steady refresh", worst < r.refreshDelayMs(3))
        assertTrue(r.TAKEOVER_REMOVAL_HOLD_MS in 1 until r.refreshDelayMs(0))
    }

    @Test fun receiverTargetValidation() {
        val pk = ByteArray(32) { it.toByte() }
        val own = ByteArray(32) { 7 }
        fun txt(vararg extra: Pair<String, String?>) = mapOf(
            SetupProtocol.TXT_VERSION to "1",
            SetupProtocol.TXT_PK to SetupCrypto.b64(pk),
        ) + extra
        val isOwn: (ByteArray) -> Boolean = { it.contentEquals(own) }
        val ok = SetupDiscoveryRules.receiverTarget("Den TV", "10.0.0.5", 4000, txt(SetupProtocol.TXT_NAME to "Den", SetupProtocol.TXT_KIND to "Phone"), isOwn)
        assertNotNull(ok)
        assertEquals("Den", ok!!.name)
        assertEquals(SetupProtocol.KIND_PHONE, ok.kind)
        assertEquals("Den TV", ok.service)
        assertEquals("instance name when name is blank", "Den TV",
            SetupDiscoveryRules.receiverTarget("Den TV", "10.0.0.5", 4000, txt(SetupProtocol.TXT_NAME to " "), isOwn)!!.name)
        assertEquals("absent kind is a TV", SetupProtocol.KIND_TV,
            SetupDiscoveryRules.receiverTarget("Den TV", "10.0.0.5", 4000, txt(), isOwn)!!.kind)
        assertNull("role=source", SetupDiscoveryRules.receiverTarget("x", "h", 1, txt(SetupProtocol.TXT_ROLE to SetupProtocol.ROLE_SOURCE), isOwn))
        assertNull("other version", SetupDiscoveryRules.receiverTarget("x", "h", 1, txt(SetupProtocol.TXT_VERSION to "2"), isOwn))
        assertNull("short key", SetupDiscoveryRules.receiverTarget("x", "h", 1, txt(SetupProtocol.TXT_PK to SetupCrypto.b64(ByteArray(16))), isOwn))
        assertNull("no host", SetupDiscoveryRules.receiverTarget("x", null, 1, txt(), isOwn))
        assertNull("own receiver", SetupDiscoveryRules.receiverTarget("x", "h", 1, txt(SetupProtocol.TXT_PK to SetupCrypto.b64(own)), isOwn))
    }

    // ---- Engine ----

    private class Clock : SetupDiscoveryEngine.Timers {
        var now = 0L
        private var seq = 0
        private class Task(val at: Long, val seq: Int, val block: () -> Unit) { var done = false }
        private val tasks = mutableListOf<Task>()
        override fun after(ms: Long, block: () -> Unit): () -> Unit {
            val t = Task(now + ms, seq++, block)
            tasks += t
            return { t.done = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { !it.done && it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                next.done = true
                now = next.at
                next.block()
            }
            now = end
        }
    }

    private class Nsd : SetupDiscoveryEngine.Platform {
        val live = mutableListOf<Int>()
        val started = mutableListOf<Int>()
        data class Resolve(val serial: Int, val browse: Int, val service: String, val at: Long)
        val resolves = mutableListOf<Resolve>()
        val cancelled = mutableListOf<Int>()
        lateinit var clock: Clock
        override fun startBrowse(id: Int) { live += id; started += id }
        override fun stopBrowse(id: Int) { live -= id }
        override fun resolve(serial: Int, browse: Int, service: String) { resolves += Resolve(serial, browse, service, clock.now) }
        override fun cancelResolve(serial: Int) { cancelled += serial }
    }

    private lateinit var clock: Clock
    private lateinit var nsd: Nsd
    private lateinit var engine: SetupDiscoveryEngine
    /** Every list the engine published, so a blink (a list without the device) would show. */
    private val published = mutableListOf<List<SetupTarget>>()

    @Before fun setUp() {
        clock = Clock()
        nsd = Nsd().also { it.clock = clock }
        engine = SetupDiscoveryEngine(nsd, clock)
        published.clear()
    }

    private fun record() { if (published.lastOrNull() !== engine.tvs.value) published += engine.tvs.value }

    /** Each fake device has its own address (one row per address). */
    private fun portOf(service: String) = 4000 + Math.floorMod(service.hashCode(), 1000)

    private fun target(service: String, port: Int = portOf(service), pkByte: Int = 1) = SetupTarget(
        name = service, host = "10.0.2.15", port = port, pk = ByteArray(32) { pkByte.toByte() }, service = service,
    )

    /** Answers the outstanding resolve for [service] with its target. */
    private fun answer(service: String, port: Int = portOf(service), pkByte: Int = 1) {
        val r = nsd.resolves.last { it.service == service }
        engine.resolved(r.serial, target(service, port, pkByte))
        record()
    }

    private fun names() = engine.tvs.value.map { it.service }
    private fun current() = nsd.live.first()

    @Test fun advertAfterStartShowsAndGoodbyeClears() {
        engine.start()
        assertEquals(listOf(1), nsd.live)
        clock.advance(3_000)
        engine.found(1, "Den TV")
        assertEquals(1, nsd.resolves.size)
        answer("Den TV")
        assertEquals(listOf("Den TV"), names())
        engine.lost(1, "Den TV")
        assertEquals(emptyList<String>(), names())
    }

    @Test fun nothingListedRecreatesOnTheRefreshPace() {
        engine.start()
        clock.advance(4_999)
        assertEquals(listOf(1), nsd.started)
        clock.advance(1)
        assertEquals("recreated at 5 s", listOf(1, 2), nsd.started)
        assertEquals(listOf(2), nsd.live)
        clock.advance(10_000)
        assertEquals("at 15 s", 3, nsd.started.size)
        clock.advance(20_000)
        assertEquals("at 35 s", 4, nsd.started.size)
        clock.advance(30_000)
        assertEquals("at 65 s", 5, nsd.started.size)
        clock.advance(600_000)
        assertTrue("at most one browse at a time", nsd.live.size == 1)
    }

    @Test fun lateAdvertFoundByARecreatedBrowse() {
        engine.start()
        // Browse 1 is deaf: the advert starts at 2 s and browse 1 never reports it.
        clock.advance(5_000)
        assertEquals(2, current())
        engine.found(2, "Den TV")
        answer("Den TV")
        assertEquals("found by the 5 s refresh", listOf("Den TV"), names())
    }

    @Test fun probeTakesOverWithoutBlinking() {
        engine.start()
        engine.found(1, "Den TV"); answer("Den TV"); record()
        val shown = engine.tvs.value.single()
        clock.advance(5_000)
        assertEquals("devices listed: a second browse beside the first", listOf(1, 2), nsd.live)
        engine.found(2, "Den TV")
        assertEquals("the second browse took over", listOf(2), nsd.live)
        assertEquals("re-resolved under the new browse", 2, nsd.resolves.last().browse)
        answer("Den TV")
        assertSame("same row instance, nothing republished", shown, engine.tvs.value.single())
        clock.advance(3_000)
        assertTrue("never an empty list", published.all { it.isNotEmpty() })
    }

    @Test fun takeoverHoldsRemovalsThenDropsWhatTheNewBrowseMissed() {
        engine.start()
        engine.found(1, "A"); answer("A")
        engine.found(1, "B"); answer("B")
        assertEquals(listOf("A", "B"), names())
        clock.advance(5_000)
        engine.found(2, "A")
        clock.advance(1_500)
        assertEquals("B held during the takeover hold", listOf("A", "B"), names())
        engine.found(2, "B")
        clock.advance(1_000)
        assertEquals("B re-found in time stays", listOf("A", "B"), names())

        clock.advance(8_500) // 16 s: the 15 s refresh started browse 3
        assertEquals(listOf(2, 3), nsd.live)
        engine.found(3, "A")
        clock.advance(2_000)
        assertEquals("B not re-found: dropped after the hold", listOf("A"), names())
    }

    @Test fun missedGoodbyeClearsAfterTwoSilentRefreshes() {
        engine.start()
        engine.found(1, "Den TV"); answer("Den TV")
        clock.advance(5_000)
        assertEquals("probe started", listOf(1, 2), nsd.live)
        clock.advance(10_000)
        assertEquals("silent probe replaced", listOf(1, 3), nsd.live)
        assertEquals(listOf("Den TV"), names())
        clock.advance(20_000)
        assertEquals("silent again: it takes over", listOf(3), nsd.live)
        assertEquals("the card clears", emptyList<String>(), names())
    }

    @Test fun resolveTimesOutAndRetriesThenWaitsForTheNextBrowse() {
        engine.start()
        clock.advance(1_000)
        engine.found(1, "Den TV")
        assertEquals(1, nsd.resolves.size)
        clock.advance(4_000) // timeout at 5 s; the 5 s refresh starts a probe (an advert is listed)
        assertEquals(listOf(nsd.resolves[0].serial), nsd.cancelled)
        clock.advance(1_000)
        assertEquals("attempt 2 after 1 s", 2, nsd.resolves.size)
        assertEquals(6_000L, nsd.resolves[1].at)
        clock.advance(4_000)
        clock.advance(2_000)
        assertEquals("attempt 3 after 2 s", 3, nsd.resolves.size)
        assertEquals(12_000L, nsd.resolves[2].at)
        clock.advance(4_000)
        assertEquals("gave up", 3, nsd.resolves.size)
        // A late answer to a cancelled attempt is ignored.
        engine.resolved(nsd.resolves[0].serial, target("Den TV"))
        assertEquals(emptyList<String>(), names())
        // The probe running since 5 s hears it: a fresh budget under the new browse.
        engine.found(nsd.live.last(), "Den TV")
        assertEquals(4, nsd.resolves.size)
        answer("Den TV")
        assertEquals(listOf("Den TV"), names())
    }

    @Test fun resolvesRunOneAtATime() {
        engine.start()
        engine.found(1, "A")
        engine.found(1, "B")
        assertEquals("one outstanding", listOf("A"), nsd.resolves.map { it.service })
        answer("A")
        assertEquals(listOf("A", "B"), nsd.resolves.map { it.service })
        answer("B")
        assertEquals(listOf("A", "B"), names())
    }

    @Test fun failedAttemptRetriesAndLostAdvertCancelsWork() {
        engine.start()
        engine.found(1, "A")
        engine.resolveFailed(nsd.resolves[0].serial, "error 3")
        clock.advance(1_000)
        assertEquals(2, nsd.resolves.size)
        engine.lost(1, "A")
        assertTrue("the outstanding attempt is cancelled", nsd.resolves[1].serial in nsd.cancelled)
        clock.advance(10_000)
        assertEquals("no more attempts for a lost advert", 2, nsd.resolves.size)
    }

    @Test fun notAReceiverIsNotListed() {
        engine.start()
        engine.found(1, "Source")
        engine.resolved(nsd.resolves[0].serial, null)
        assertEquals(emptyList<String>(), names())
    }

    @Test fun newKeyUnderTheSameNameReplacesTheRow() {
        engine.start()
        engine.found(1, "Den TV"); answer("Den TV", pkByte = 1)
        clock.advance(5_000)
        engine.found(2, "Den TV"); answer("Den TV", port = 3999, pkByte = 2)
        val t = engine.tvs.value.single()
        assertEquals(3999, t.port)
        assertEquals(2.toByte(), t.pk[0])
    }

    @Test fun stopClearsAndIgnoresLateCallbacks() {
        engine.start()
        engine.found(1, "A"); answer("A")
        engine.found(1, "B")
        engine.stop()
        assertEquals(emptyList<Int>(), nsd.live)
        assertTrue(nsd.resolves.last().serial in nsd.cancelled)
        assertEquals(emptyList<String>(), names())
        engine.found(1, "C")
        engine.resolved(nsd.resolves.last().serial, target("B"))
        clock.advance(60_000)
        assertEquals(emptyList<String>(), names())
        assertEquals("no refresh after stop", 1, nsd.started.size)
        engine.start()
        assertEquals("a fresh browse on restart", listOf(2), nsd.live)
    }

    @Test fun failedProbeIsDroppedAndTheNextRefreshTriesAgain() {
        engine.start()
        engine.found(1, "A"); answer("A")
        clock.advance(5_000)
        engine.browseFailed(2, 0)
        clock.advance(10_000)
        assertEquals("a new probe, not a takeover", listOf(1, 3), nsd.live)
        assertEquals(listOf("A"), names())
    }
}
