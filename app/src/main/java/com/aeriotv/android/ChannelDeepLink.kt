package com.aeriotv.android

import com.aeriotv.android.core.data.M3UChannel

/**
 * `aeriotv://channel/<id>`, resolved against the ACTIVE playlist's channels
 * (owner 2026-11-13, the same format on both apps):
 * 1. `m3u:<tvg-id>` (and `m3u:<url>` for a channel without a tvg-id), and any
 *    other id AerioTV itself writes (the TV home channels, the Cast Connect
 *    entity, the companion remote, reminder notifications): matched as is.
 * 2. `disp:<uuid>`: a Dispatcharr channel, as AerioTV names it.
 * 3. A bare uuid: the Dispatcharr channel with that uuid.
 * 4. `dispatcharr:<channel id>`: Dispatcharr's integer channel id.
 * 5. `number:<channel number>`: the channel number the guide shows; decimals
 *    such as 5.1 are allowed.
 * Nothing matching returns null; the caller drops the link once channels
 * have loaded, and logs it.
 */
object ChannelDeepLink {
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** The channel [link] names in [channels], or null. */
    fun resolve(link: String, channels: List<M3UChannel>): M3UChannel? {
        val id = link.trim()
        if (id.isEmpty()) return null
        channels.firstOrNull { it.id == id }?.let { return it }
        when {
            id.startsWith("disp:", ignoreCase = true) -> {
                val uuid = id.substring(5)
                return channels.firstOrNull { it.id.equals("disp:$uuid", ignoreCase = true) }
            }
            id.startsWith("dispatcharr:", ignoreCase = true) -> {
                val n = id.substring(12).trim().toIntOrNull() ?: return null
                return channels.firstOrNull { it.dispatcharrChannelId == n }
            }
            id.startsWith("number:", ignoreCase = true) -> {
                val wanted = id.substring(7).trim()
                if (wanted.isEmpty()) return null
                channels.firstOrNull { it.channelNumber?.trim() == wanted }?.let { return it }
                val n = wanted.toDoubleOrNull() ?: return null
                return channels.firstOrNull { it.channelNumber?.trim()?.toDoubleOrNull() == n }
            }
            UUID.matches(id) -> return channels.firstOrNull { it.id.equals("disp:$id", ignoreCase = true) }
        }
        return null
    }
}
