package com.aeriotv.android.core.data

import java.util.UUID

/**
 * Mirrors iOS Aerio/Networking/PlaylistParsers.swift M3UChannel (lines 4-14).
 * Pure data class with no Room annotations yet — Phase 2b will add @Entity.
 */
data class M3UChannel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val groupTitle: String = "",
    val tvgID: String = "",
    val tvgName: String = "",
    val tvgLogo: String = "",
    /**
     * `tvg-chno` from the playlist, preserved verbatim (just trimmed). Mirrors
     * iOS commit d1ac87a: prior versions parsed this as Int? and dropped any
     * decimal-valued entry (Dispatcharr sub-channels like 2.1 / 11444.0).
     * Keeping the raw String lets us render the user's exact identifier and
     * still sort numerically via toDoubleOrNull() at sort sites.
     */
    val channelNumber: String? = null,
    val rawAttributes: Map<String, String> = emptyMap(),
    /**
     * GH #27: DRM signalling from `#KODIPROP:inputstream.adaptive.*` lines
     * preceding this channel's URL (the Kodi/TiviMate convention for
     * encrypted DASH). `drmLicenseType` is "clearkey" / "com.widevine.alpha"
     * etc.; `drmLicenseKey` is either a license-server URL or a ClearKey
     * "kid:key" hex pair. Null for the (vast) majority of channels.
     */
    val drmLicenseType: String? = null,
    val drmLicenseKey: String? = null,
    /**
     * Dispatcharr's primary-key integer for this channel, used when scheduling
     * server-side recordings (`/api/channels/recordings/` requires the int id).
     * Null for M3U / Xtream sources that don't carry it.
     */
    val dispatcharrChannelId: Int? = null,
    /**
     * Catch-up (timeshift) retention window in DAYS, 0 when the channel has no
     * archive. From Dispatcharr's `catchup_days` (native API) or an XC provider's
     * `tv_archive_duration`. A programme that ended within this window is
     * replayable; [catchupStreamId] must also be set for playback.
     */
    val catchupDays: Int = 0,
    /**
     * The id the panel timeshifts by, when [catchupDays] > 0: Dispatcharr's
     * Channel.id (its XC layer exposes it as stream_id) or a raw XC provider's
     * `stream_id`. Null disables catch-up even if [catchupDays] > 0.
     */
    val catchupStreamId: String? = null,
) {
    /** True when this channel exposes a replayable archive. */
    val hasCatchup: Boolean get() = catchupDays > 0 && catchupStreamId != null
}

/**
 * True when [programme] is replayable from this channel's catch-up archive:
 * the programme has ENDED (panels report has_archive=0 for the airing show;
 * its archive is still being written) and its end falls inside the retention
 * window. The 30-day cap mirrors Dispatcharr's server-side clamp.
 */
fun M3UChannel.canReplay(programme: EPGProgramme, nowMillis: Long): Boolean {
    if (!hasCatchup || programme.isPlaceholder) return false
    val windowMs = minOf(catchupDays, 30) * 86_400_000L
    return programme.endMillis <= nowMillis &&
        programme.endMillis > nowMillis - windowMs
}
