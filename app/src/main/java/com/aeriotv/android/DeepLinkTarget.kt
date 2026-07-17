package com.aeriotv.android

/**
 * Resolved deep-link destination parsed from an `aeriotv://` Intent
 * (audit task #47). MainActivity captures the URI on launch / new-intent
 * and surfaces a [DeepLinkTarget] to [AerioTVNavHost]; the NavHost
 * navigates after the active playlist is ready and invokes the
 * consumed-callback to clear it.
 */
sealed interface DeepLinkTarget {
    /** Launch fullscreen player for [channelId] (matches M3UChannel.id). */
    data class Channel(val channelId: String) : DeepLinkTarget

    /** Launch movie detail screen for [movieUuid]. */
    data class Vod(val movieUuid: String) : DeepLinkTarget

    /**
     * Jump to a programme in the Live TV guide (parity: iOS
     * aerioJumpToGuideProgram, commit e3ccf439d). [channelId] is the
     * channel's guideMatchKey (the same key epgByChannel is keyed on, which
     * is also what an EPG search result carries), NOT M3UChannel.id.
     * [startMillis] is the programme's start time, used as the guide's
     * horizontal anchor column. Consumed idempotently in Navigation.kt's
     * MAIN route once a matching loaded channel exists; the consumer routes
     * to the Live TV tab, forces guide mode, resets the group filter to All,
     * and scrolls/focuses the program cell.
     */
    data class GuideProgram(val channelId: String, val startMillis: Long) : DeepLinkTarget

    /** GH #33 companion remote: AUTOPLAY a movie/episode on this TV -- straight
     *  into the VOD player (unlike [Vod], which lands on the detail screen). */
    data class VodPlay(val videoId: String, val isEpisode: Boolean) : DeepLinkTarget

    /** GH #33 companion remote: play a DVR recording / catch-up by its resolved
     *  playback URL on this TV. */
    data class RecordingPlay(val playbackUrl: String, val title: String) : DeepLinkTarget
}
