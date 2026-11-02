package com.aeriotv.android.core.playback

import com.aeriotv.android.core.network.DispatcharrHttpFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stop conditions for the change_stream failover walk (Streamer 2026-11-01:
 *  status + change_stream kept firing every 56 s after the player went IDLE). */
class LiveStreamFailoverStopTest {

    @Test fun activeTuneMayStep() {
        assertNull(LiveStreamFailover.walkBlockReason(tuneActive = true, channelMatches = true, sessionGone = false))
    }

    @Test fun idlePlayerStops() {
        assertEquals(
            "player stopped",
            LiveStreamFailover.walkBlockReason(tuneActive = false, channelMatches = true, sessionGone = false),
        )
    }

    @Test fun otherChannelStops() {
        assertEquals(
            "tuned elsewhere",
            LiveStreamFailover.walkBlockReason(tuneActive = true, channelMatches = false, sessionGone = false),
        )
    }

    @Test fun goneSessionStops() {
        assertEquals(
            "channel session gone",
            LiveStreamFailover.walkBlockReason(tuneActive = true, channelMatches = true, sessionGone = true),
        )
    }

    @Test fun channelNotFoundIsGone() {
        assertTrue(LiveStreamFailover.isChannelGone(DispatcharrHttpFailure(404, "Channel not found")))
        assertTrue(LiveStreamFailover.isChannelGone(Exception("HTTP 404: Channel not found")))
        assertFalse(LiveStreamFailover.isChannelGone(DispatcharrHttpFailure(504, "not confirmed")))
        assertFalse(LiveStreamFailover.isChannelGone(DispatcharrHttpFailure(403, null)))
    }
}
