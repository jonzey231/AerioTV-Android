package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.M3UChannel
import java.security.MessageDigest

/**
 * Stable fingerprint of a loaded channel list's identity: the sorted set of
 * canonical ids plus each channel's declared guide key. The EPG cache is
 * stamped with it on a successful fetch; a different fingerprint at paint
 * time means the cache was built for a different channel list and must be
 * refetched (docs/guide-semantics.md, section 4). Order-independent, so
 * re-sorting or re-grouping the same channels never invalidates the cache.
 */
object GuideIdentityHash {
    fun of(channels: List<M3UChannel>): String {
        if (channels.isEmpty()) return ""
        val md = MessageDigest.getInstance("SHA-1")
        channels
            .map { it.guideChannelId().value + "|" + GuideMatchMaps.normalize(it.tvgID) }
            .sorted()
            .forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        val sb = StringBuilder(40)
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
