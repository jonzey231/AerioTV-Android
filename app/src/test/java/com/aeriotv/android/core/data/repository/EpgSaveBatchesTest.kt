package com.aeriotv.android.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSaveBatchesTest {
    private data class P(val channel: String, val n: Int)

    private fun guide(channels: Int, perChannel: Int): List<P> =
        (0 until perChannel).flatMap { n -> (0 until channels).map { c -> P("ch$c", n) } }

    @Test fun aSmallGuideIsOneBatch() {
        val items = guide(5, 10)
        val batches = EpgSaveBatches.byChannel(items, 10_000) { it.channel }
        assertEquals(1, batches.size)
        assertEquals(items, batches[0])
    }

    @Test fun nothingToSaveIsNoBatch() {
        assertTrue(EpgSaveBatches.byChannel(emptyList<P>(), 10) { it.channel }.isEmpty())
    }

    @Test fun aLargeGuideIsBatchesOfAboutTenThousandWholeChannels() {
        // 150k programmes across 650 channels, interleaved as a grid answer lists them.
        val items = guide(650, 231)
        val batches = EpgSaveBatches.byChannel(items) { it.channel }
        assertEquals(items.size, batches.sumOf { it.size })
        assertTrue(batches.all { it.size <= EpgSaveBatches.ROWS })
        assertTrue(batches.size in 15..17)
        // Every channel lives in exactly one batch, with its programmes in order.
        val owner = HashMap<String, Int>()
        batches.forEachIndexed { i, b ->
            b.groupBy { it.channel }.forEach { (ch, rows) ->
                assertEquals(null, owner.put(ch, i))
                assertEquals((0 until 231).toList(), rows.map { it.n })
            }
        }
        assertEquals(650, owner.size)
        // Channels keep their first-seen order.
        assertEquals((0 until 650).map { "ch$it" }, batches.flatten().map { it.channel }.distinct())
    }

    @Test fun aChannelLargerThanABatchIsNeverSplit() {
        val items = List(25) { P("big", it) } + List(3) { P("small", it) } + List(4) { P("other", it) }
        val batches = EpgSaveBatches.byChannel(items, 10) { it.channel }
        assertEquals(listOf(25, 7), batches.map { it.size })
        assertEquals(setOf("big"), batches[0].map { it.channel }.toSet())
        assertEquals(setOf("small", "other"), batches[1].map { it.channel }.toSet())
    }
}
