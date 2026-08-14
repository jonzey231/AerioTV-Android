package com.aeriotv.android.core.cast.hlsproxy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for the cast HLS proxy server's segment store and
 * playlist across a live splice (channel change or ingest reconnect).
 * Exercises the store API directly, no sockets: the device-observed
 * failure was the receiver 404ing on segments its cached playlist still
 * promised because the old splice path wiped the ring and left a
 * sequence numbering gap (Shaka error 1001, CAF CLIP_ENDED reload).
 */
class CastHlsProxyServerTest {

    private val server = CastHlsProxyServer(log = {})

    /** ~3 s of 90 kHz ticks, matching real segment cadence. */
    private val ticks = 3L * TsToFmp4Remuxer.TICKS_PER_SECOND

    private fun publish(gen: Int, count: Int, tag: Byte = 0) {
        repeat(count) { i -> server.addSegment(gen, byteArrayOf(tag, gen.toByte(), i.toByte()), ticks) }
    }

    // ---- sequence continuity ----

    @Test
    fun `splice never leaves a sequence gap`() {
        val gen1 = server.beginGeneration()
        server.setInitSegment(gen1, byteArrayOf(1))
        publish(gen1, 3) // seq 0..2
        val gen2 = server.beginGeneration()
        server.setInitSegment(gen2, byteArrayOf(2))
        publish(gen2, 1)
        // First post-splice segment is exactly lastPublishedSequence+1.
        assertNotNull("seq 3 must follow seq 2 across the splice", server.awaitSegment(3, 0))
        assertNull("seq 4 not yet published", server.awaitSegment(4, 0))
    }

    @Test
    fun `stale ingest after splice claims no sequence number`() {
        val gen1 = server.beginGeneration()
        publish(gen1, 2) // seq 0..1
        val gen2 = server.beginGeneration()
        // Old remuxer racing the channel change: dropped, number unclaimed.
        server.addSegment(gen1, byteArrayOf(9), ticks)
        publish(gen2, 1)
        assertNotNull("new generation starts at seq 2", server.awaitSegment(2, 0))
        assertNull(server.awaitSegment(3, 0))
    }

    // ---- playlist shape ----

    @Test
    fun `playlist across splice lists old entries then discontinuity then new map`() {
        val gen1 = server.beginGeneration()
        server.setInitSegment(gen1, byteArrayOf(1))
        publish(gen1, 6) // seq 0..5; window will hold 3,4,5
        val gen2 = server.beginGeneration()
        server.setInitSegment(gen2, byteArrayOf(2))
        publish(gen2, 2) // seq 6,7
        val playlist = server.playlistText()
        val lines = playlist.lines()
        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:3"))
        val oldMap = lines.indexOf("#EXT-X-MAP:URI=\"init$gen1.mp4\"")
        val lastOldSeg = lines.indexOf("seg5.m4s")
        val disc = lines.indexOf("#EXT-X-DISCONTINUITY")
        val newMap = lines.indexOf("#EXT-X-MAP:URI=\"init$gen2.mp4\"")
        val firstNewSeg = lines.indexOf("seg6.m4s")
        assertTrue("old-generation MAP present", oldMap >= 0)
        assertTrue("old-generation segments still listed", lastOldSeg > oldMap)
        assertTrue("DISCONTINUITY after the old entries", disc > lastOldSeg)
        assertTrue("new MAP after the DISCONTINUITY", newMap > disc)
        assertTrue("new-generation segments after the new MAP", firstNewSeg > newMap)
        assertTrue("new-generation live edge listed", playlist.contains("seg7.m4s"))
    }

    @Test
    fun `fresh session playlist has no discontinuity`() {
        val gen1 = server.beginGeneration()
        server.setInitSegment(gen1, byteArrayOf(1))
        publish(gen1, 2)
        assertFalse(server.playlistText().contains("#EXT-X-DISCONTINUITY"))
    }

    // ---- old-generation availability across the splice ----

    @Test
    fun `old segments and init stay servable after splice until eviction`() {
        val gen1 = server.beginGeneration()
        server.setInitSegment(gen1, byteArrayOf(1))
        publish(gen1, 5) // seq 0..4
        val gen2 = server.beginGeneration()
        server.setInitSegment(gen2, byteArrayOf(2))
        publish(gen2, 2) // seq 5,6; ring holds 0..6
        for (seq in 0..4) {
            assertNotNull("old-gen seg$seq must survive the splice", server.awaitSegment(seq, 0))
        }
        assertNotNull("old-gen init must survive while listed", server.initSegment(gen1))
        assertNotNull(server.initSegment(gen2))
        // Ring capacity is 8: publish enough new-gen segments to evict
        // every old-gen entry, then the old init goes too.
        publish(gen2, 8) // seq 7..14; ring now 7..14, all gen2
        assertNull("evicted old segment 404s", server.awaitSegment(0, 0))
        assertNull("unreferenced old init dropped", server.initSegment(gen1))
        assertNotNull(server.initSegment(gen2))
    }

    // ---- live-edge hold ----

    @Test
    fun `held request for newest plus one completes when published`() {
        val gen1 = server.beginGeneration()
        publish(gen1, 3) // seq 0..2; newest+1 is 3
        val result = AtomicReference<ByteArray?>()
        val done = CountDownLatch(1)
        Thread {
            result.set(server.awaitSegment(3, 5_000))
            done.countDown()
        }.start()
        // Let the fetch park on the monitor before publishing.
        Thread.sleep(150)
        server.addSegment(gen1, byteArrayOf(42), ticks)
        assertTrue("held fetch must complete on publish", done.await(2, TimeUnit.SECONDS))
        assertNotNull(result.get())
        assertEquals(42, result.get()!![0].toInt())
    }

    @Test
    fun `held request spanning a splice completes with the new generation segment`() {
        val gen1 = server.beginGeneration()
        publish(gen1, 2) // seq 0..1; newest+1 is 2
        val result = AtomicReference<ByteArray?>()
        val done = CountDownLatch(1)
        Thread {
            result.set(server.awaitSegment(2, 5_000))
            done.countDown()
        }.start()
        Thread.sleep(150)
        // The device failure scenario: the receiver asks for the next
        // number while the channel change splices underneath it.
        val gen2 = server.beginGeneration()
        server.addSegment(gen2, byteArrayOf(7), ticks)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNotNull("seq 2 arrives from the new generation, never a 404", result.get())
        assertEquals(7, result.get()!![0].toInt())
    }

    @Test
    fun `requests beyond newest plus one or behind the ring fail immediately`() {
        val gen1 = server.beginGeneration()
        publish(gen1, 3) // seq 0..2
        val start = System.currentTimeMillis()
        assertNull("two past the edge is a bad URL", server.awaitSegment(5, 5_000))
        assertTrue("no hold for far-future sequences", System.currentTimeMillis() - start < 1_000)
        publish(gen1, 8) // evict seq 0..2
        assertNull("behind the ring is gone", server.awaitSegment(0, 5_000))
        assertTrue(System.currentTimeMillis() - start < 2_000)
    }
}
