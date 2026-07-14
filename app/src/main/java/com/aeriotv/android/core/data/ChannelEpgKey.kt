package com.aeriotv.android.core.data

/**
 * Canonical lookup key for joining a [M3UChannel] to its EPG programmes.
 *
 * iOS GuideStore builds three matching maps (`tvgIDToChannelIDs`,
 * `intIDToChannelID`, `uuidToChannelID`) at fetch time so a programme keyed
 * by EITHER tvg_id, channel number, or Dispatcharr channel UUID (the Dummy
 * EPG case) all match the right channel; see
 * `Aerio/Features/LiveTV/EPGGuideView.swift` lines 768-815 (Dispatcharr
 * grid match) and 1395-1414 (XMLTV match). Android historically did
 * `epgByChannel[channel.tvgID]` which only worked for tvg-id-keyed feeds.
 *
 * The mirror approach here:
 *   1. Every channel gets ONE canonical [guideMatchKey] -- tvgID first,
 *      falling back to channel number, then dispatcharrChannelId, then any
 *      uuid-shaped rawAttribute, then the entity id. Every Live-TV /
 *      Favorites / Guide / Player site looks up programmes with this key
 *      instead of `tvgID` directly, so channels with blank tvg-id render
 *      now-playing as soon as ANY fallback matches.
 *   2. [PlaylistRepository.bridgeChannelIds] runs once per fetch to
 *      rewrite each programme's `channelId` to the matching channel's
 *      [guideMatchKey], so the downstream `groupBy { it.channelId }` +
 *      `epgByChannel[channel.guideMatchKey]` lookup ends up in the same
 *      bucket regardless of which key the source feed used.
 *
 * Issue #20 (shared tvg-id across multiple channels) still works because
 * the lookup is by key, not by channel identity -- two channels with the
 * same tvgID resolve to the same bucket and both render the same
 * programme list. iOS handles this with `[String: [String]]` value-as-list;
 * the Android equivalent falls out of the data shape naturally.
 */
val M3UChannel.guideMatchKey: String
    get() {
        val tvg = tvgID.takeIf { it.isNotBlank() }
        if (tvg != null) return tvg
        val num = channelNumber?.takeIf { it.isNotBlank() }
        if (num != null) return num
        val dispatcharrInt = dispatcharrChannelId?.toString()
        if (dispatcharrInt != null) return dispatcharrInt
        val uuidAttr = rawAttributes["channel-id"]?.takeIf { it.isNotBlank() }
            ?: rawAttributes["channel-uuid"]?.takeIf { it.isNotBlank() }
            ?: rawAttributes["uuid"]?.takeIf { it.isNotBlank() }
        if (uuidAttr != null) return uuidAttr
        return id
    }

/**
 * Build a case-insensitive map from any candidate key (tvg-id, channel
 * number, dispatcharr int id, uuid-shaped rawAttribute) onto the
 * channel's canonical [guideMatchKey]. Used by
 * [com.aeriotv.android.core.data.repository.PlaylistRepository.bridgeChannelIds]
 * to rewrite each programme's `channelId` to the canonical key its target
 * channel will look up under.
 *
 * Putting tvg-id first so the most-common case is a no-op (programme's
 * channelId already IS the tvgID, the rewrite is identity). `putIfAbsent`
 * preserves the first-seen mapping when two channels happen to share a
 * candidate key (e.g. two channels with channelNumber="5"). The downstream
 * `epgByChannel[guideMatchKey]` lookup still finds both channels' shared
 * bucket because they both resolve to the same key.
 */
fun buildChannelEpgKeyBridge(channels: List<M3UChannel>): Map<String, String> {
    if (channels.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>(channels.size * 4)
    for (ch in channels) {
        val canonical = ch.guideMatchKey
        val candidates = listOfNotNull(
            ch.tvgID,
            ch.channelNumber,
            ch.dispatcharrChannelId?.toString(),
            ch.rawAttributes["channel-id"],
            ch.rawAttributes["channel-uuid"],
            ch.rawAttributes["uuid"],
            // GH #35: Dispatcharr Dummy / no-EPG grid programmes are keyed by
            // str(channel.uuid) (Dispatcharr apps/epg/api_views.py::EPGGridAPIView),
            // NOT the channel's real tvg-id. These are exactly the OTA subchannels
            // that carry major.minor numbers like "5.1", so the guide looked blank
            // for float-numbered channels. The server UUID lives only inside the
            // channel id ("disp:<uuid>") and rawAttributes is empty for Dispatcharr
            // channels, so expose the bare UUID here as a bridge candidate. Mirrors
            // the iOS uuidToChannelID third key (EPGGuideView.swift:947). Derived
            // from `id` (not rawAttributes) so cache-restored cold starts bridge too.
            ch.id.takeIf { it.startsWith("disp:") }?.removePrefix("disp:"),
        )
        for (raw in candidates) {
            val key = raw.trim().lowercase()
            if (key.isNotEmpty()) out.putIfAbsent(key, canonical)
        }
    }
    return out
}

/**
 * Rewrites each [EPGProgramme.channelId] to the canonical [guideMatchKey]
 * of the channel it belongs to, using a [buildChannelEpgKeyBridge] lookup.
 * Programmes whose raw `channel="..."` attribute doesn't match any candidate
 * key on any channel are left untouched (still in the bucket for whatever
 * key the source feed uses -- a later channel match may rescue it once the
 * channel list is updated).
 *
 * Idempotent: feeding through the bridge twice in a row produces the same
 * output because the first rewrite already lands on a canonical key, which
 * the second pass either no-ops on (canonical == programme.channelId) or
 * rewrites onto itself.
 *
 * Costs `O(programmes)` lookups plus `O(channels)` map build; safe to call
 * inline from a `Dispatchers.Default` block.
 */
fun bridgeChannelIds(
    programmes: List<EPGProgramme>,
    channels: List<M3UChannel>,
): List<EPGProgramme> {
    if (programmes.isEmpty() || channels.isEmpty()) return programmes
    val bridge = buildChannelEpgKeyBridge(channels)
    if (bridge.isEmpty()) return programmes
    return programmes.map { prog ->
        val raw = prog.channelId.trim().lowercase()
        if (raw.isEmpty()) return@map prog
        val canonical = bridge[raw] ?: return@map prog
        if (canonical == prog.channelId) prog else prog.copy(channelId = canonical)
    }
}
