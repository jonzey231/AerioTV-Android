package com.aeriotv.android

import com.aeriotv.android.core.data.M3UChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelDeepLinkTest {
    private val uuid = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"
    private val disp = M3UChannel(id = "disp:$uuid", name = "News", url = "http://x/1", channelNumber = "5.1", dispatcharrChannelId = 241)
    private val disp2 = M3UChannel(id = "disp:0f0e0d0c-0b0a-4908-8706-050403020100", name = "Sports", url = "http://x/2", channelNumber = "12", dispatcharrChannelId = 7)
    private val m3u = M3UChannel(id = "m3u:241", name = "Movies", url = "http://x/3", tvgID = "241", channelNumber = "241")
    private val m3uUrl = M3UChannel(id = "m3u:http://example.invalid/stream.ts", name = "No tvg", url = "http://x/4")
    private val all = listOf(disp, disp2, m3u, m3uUrl)

    @Test fun m3uTvgIdAsToday() = assertEquals(m3u, ChannelDeepLink.resolve("m3u:241", all))

    @Test fun m3uUrlAsToday() = assertEquals(m3uUrl, ChannelDeepLink.resolve("m3u:http://example.invalid/stream.ts", all))

    @Test fun dispUuid() {
        assertEquals(disp, ChannelDeepLink.resolve("disp:$uuid", all))
        assertEquals(disp, ChannelDeepLink.resolve("disp:${uuid.uppercase()}", all))
    }

    @Test fun bareUuidMatchesTheDispatcharrChannel() {
        assertEquals(disp, ChannelDeepLink.resolve(uuid, all))
        assertEquals(disp, ChannelDeepLink.resolve(uuid.uppercase(), all))
    }

    @Test fun dispatcharrIntegerChannelId() {
        assertEquals(disp, ChannelDeepLink.resolve("dispatcharr:241", all))
        assertEquals(disp2, ChannelDeepLink.resolve("dispatcharr:7", all))
        assertNull(ChannelDeepLink.resolve("dispatcharr:abc", all))
    }

    @Test fun channelNumberAsTheGuideShowsItDecimalsAllowed() {
        assertEquals(disp, ChannelDeepLink.resolve("number:5.1", all))
        assertEquals(disp2, ChannelDeepLink.resolve("number:12", all))
        assertEquals(disp2, ChannelDeepLink.resolve("number:12.0", all))
        assertEquals(m3u, ChannelDeepLink.resolve("number:241", all))
    }

    @Test fun nothingMatchingIsNull() {
        assertNull(ChannelDeepLink.resolve("number:999", all))
        assertNull(ChannelDeepLink.resolve("dispatcharr:999", all))
        assertNull(ChannelDeepLink.resolve("disp:00000000-0000-0000-0000-000000000000", all))
        assertNull(ChannelDeepLink.resolve("00000000-0000-0000-0000-000000000000", all))
        assertNull(ChannelDeepLink.resolve("241", all))
        assertNull(ChannelDeepLink.resolve("", all))
    }

    @Test fun internalIdsResolveExactlyAsBefore() {
        // The TV home channels, Cast Connect, the companion remote, and
        // reminders all pass the channel's own id.
        all.forEach { assertEquals(it, ChannelDeepLink.resolve(it.id, all)) }
    }
}
