package com.aeriotv.android.core.playback

import androidx.media3.common.util.UnstableApi
import com.aeriotv.android.core.network.DispatcharrError
import com.aeriotv.android.feature.settings.API_KEY_REJECTED_TITLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live player's stop notice for a held API key (Apple
 * LiveStopNotice.keyRejected, 2026-11-11): the same title as the prompt and
 * the same text as the held-key errors, on the limit card without the
 * "Close another stream" hint.
 */
@UnstableApi
class KeyRejectedNoticeTest {
    private val appleTitle = "API key no longer valid"
    private val appleMessage = "Dispatcharr no longer accepts this playlist's API key. Enter the current key in Edit Playlist, " +
        "or ask your Dispatcharr administrator for a new one."

    @Test fun copyMatchesApple() {
        val n = DispatcharrConnectionLimit.KEY_REJECTED
        assertEquals(DispatcharrConnectionLimit.Kind.KEY_REJECTED, n.kind)
        assertEquals(appleTitle, n.title)
        assertEquals(appleMessage, n.message)
        assertEquals("the prompt title", appleTitle, API_KEY_REJECTED_TITLE)
        assertEquals("the stream error", appleMessage, StreamKeyRejectedException().message)
        assertEquals("the API error", appleMessage, DispatcharrError.KeyRejected().message)
    }

    @Test fun hintOnlyUnderLimitRefusals() {
        fun hint(k: DispatcharrConnectionLimit.Kind) = DispatcharrConnectionLimit.Notice(k, "t", "m").closeAnotherStreamHint
        assertTrue(hint(DispatcharrConnectionLimit.Kind.USER_STREAM_LIMIT))
        assertTrue(hint(DispatcharrConnectionLimit.Kind.PROVIDER_LIMIT))
        assertFalse(hint(DispatcharrConnectionLimit.Kind.STREAM_ENDED))
        assertFalse(hint(DispatcharrConnectionLimit.Kind.RATE_LIMITED))
        assertFalse(hint(DispatcharrConnectionLimit.Kind.KEY_REJECTED))
    }
}
