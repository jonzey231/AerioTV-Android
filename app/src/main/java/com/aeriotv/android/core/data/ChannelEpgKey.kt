package com.aeriotv.android.core.data

import com.aeriotv.android.core.guide.guideChannelId

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
    get() = this.guideChannelId().value

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
    // Rebuilt on the guide identity module: keys are the raw feed keys a
    // programme may carry (declared tvg-ids, dummy-EPG uuids), values the
    // canonical id of the first channel that claims them. Callers that need
    // the full one-to-many attachment use bridgeChannelIds; callers that need
    // the raw-key SET (the parser's known-channel filter) use .keys.
    if (channels.isEmpty()) return emptyMap()
    val maps = com.aeriotv.android.core.guide.GuideMatchMaps.build(channels)
    val out = LinkedHashMap<String, String>(maps.tvgIdToChannels.size + maps.uuidToChannel.size)
    for ((key, ids) in maps.tvgIdToChannels) out[key] = ids.first().value
    for ((key, id) in maps.uuidToChannel) out.putIfAbsent(key, id.value)
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
    // Rebuilt: attach through explicit match maps, ONE-TO-MANY (a tvg-id
    // shared by several channels lands its programmes on every one of them,
    // as the Apple guide does), and never through channel numbers or integer
    // ids. Unmatched programmes are dropped; they can never render anyway.
    if (programmes.isEmpty() || channels.isEmpty()) return programmes
    val maps = com.aeriotv.android.core.guide.GuideMatchMaps.build(channels)
    if (maps.isEmpty) return emptyList()
    val result = com.aeriotv.android.core.guide.GuideIngest.attach(
        programmes, maps, com.aeriotv.android.core.guide.GuideSource.GRID,
    )
    if (result.unresolvedCount > 0) {
        // TEMP DIAGNOSTIC (rebuild phase 2): for each sampled unresolved key,
        // say which channel FIELD would have matched it under the old bridge,
        // so the identity maps can be corrected from evidence, not guesses.
        val byNumber = channels.associateBy { it.channelNumber?.trim()?.lowercase().orEmpty() }
        val byIntId = channels.associateBy { it.dispatcharrChannelId?.toString().orEmpty() }
        val byRawTvg = channels.associateBy { it.rawAttributes["tvg-id"]?.trim()?.lowercase().orEmpty() }
        val byTvg = channels.associateBy { it.tvgID.trim().lowercase() }
        val classified = result.unresolvedKeys.map { k ->
            val hits = buildList {
                byTvg[k]?.let { add("tvgID=" + it.name) }
                byRawTvg[k]?.let { add("rawTvg=" + it.name) }
                byNumber[k]?.let { add("number=" + it.name) }
                byIntId[k]?.let { add("intId=" + it.name) }
            }
            "$k -> ${if (hits.isEmpty()) "no channel field" else hits.joinToString("|")}"
        }
        android.util.Log.i(
            "GuideIdentity",
            "attach: ${result.attachedCount} attached, ${result.unresolvedCount} unresolved; " +
                "channels=${channels.size} withTvg=${channels.count { it.tvgID.isNotBlank() }} " +
                "sample: $classified",
        )
    }
    return result.resolved.map { it.programme }
}
