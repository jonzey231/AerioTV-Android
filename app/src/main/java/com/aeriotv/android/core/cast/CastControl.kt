package com.aeriotv.android.core.cast

import org.json.JSONArray
import org.json.JSONObject

/**
 * Custom Cast message protocol (GH #33) that lets the phone SENDER drive the
 * receiver-only playback controls the standard Cast MediaStatus can't carry:
 * audio track, subtitle/text track, playback speed, and aspect/resize mode.
 *
 * Cast Connect already relays transport (play / pause / stop) through the
 * receiver's MediaSession, so this channel is ONLY for those four "same as the
 * local player" controls that live inside the receiver's own ExoPlayer/PlayerView.
 *
 * Wire format is JSON over a custom namespace, both directions:
 *  - sender -> receiver: a COMMAND `{cmd, ...args}` (getState / setAudio / setText
 *    / setSpeed / setAspect).
 *  - receiver -> sender: a full STATE snapshot `{cmd:"state", audio:[...], text:[...],
 *    textOff, speed, aspect}` pushed on connect (answering getState), after every
 *    applied command, and whenever the receiver's own tracks change.
 *
 * The sender renders its normal audio/subtitle/speed/aspect pickers off [RemoteState]
 * instead of the local ExoPlayer, and each pick sends the matching command.
 */
object CastControl {
    /** Custom Cast message namespace (also declared as receiver additionalSenderNamespaces). */
    const val NAMESPACE = "urn:x-cast:com.aeriotv.control"

    // Command discriminator + values.
    const val KEY_CMD = "cmd"
    const val CMD_GET_STATE = "getState"
    const val CMD_SET_AUDIO = "setAudio"
    const val CMD_SET_TEXT = "setText"
    const val CMD_SET_SPEED = "setSpeed"
    const val CMD_SET_ASPECT = "setAspect"
    // Channel change. Cast Connect's deep-link load path does NOT re-deliver a
    // second load() to an already-running receiver (device-verified: the Z Fold
    // shows the flip but the TV never re-tunes), so channel changes ride THIS
    // reliable custom channel instead of RemoteMediaClient.load().
    const val CMD_SET_CHANNEL = "setChannel"
    const val KEY_CHANNEL_ID = "channelId"
    // Audio-only: drop the receiver's video track (keeps decoding audio). Bool arg.
    const val CMD_SET_AUDIO_ONLY = "setAudioOnly"
    const val KEY_AUDIO_ONLY = "audioOnly"
    // GH #33 live-rewind cast controls: drive the receiver's timeshift buffer.
    const val CMD_SEEK_BY = "seekBy" // signed delta ms (e.g. -30000 / +30000)
    const val CMD_SEEK_WALL = "seekWall" // absolute wall-clock target ms (scrubber)
    const val CMD_GO_LIVE = "goLive" // jump the receiver back to the live edge
    const val CMD_STATE = "state" // receiver -> sender snapshot
    // Lightweight ~1Hz position tick (receiver -> sender). Separate from CMD_STATE
    // so the crawling scrubber never triggers the heavy full-track state rebuild.
    const val CMD_POSITION = "position"
    // Transport play/pause. Over Cast Connect these ride the receiver's MediaSession
    // bridge, so this protocol never needed them. The LAN companion remote (GH #33
    // second-screen) has NO MediaSession bridge, so it drives transport explicitly.
    const val CMD_PLAY = "play"
    const val CMD_PAUSE = "pause"
    const val CMD_TOGGLE = "toggle"
    // Companion VOD/DVR (GH #33): play a movie/episode (by uuid) or a DVR
    // recording (by resolved playback URL) on the TV's own VOD player.
    const val CMD_PLAY_VOD = "playVod"
    const val CMD_PLAY_RECORDING = "playRecording"
    const val KEY_VIDEO_ID = "videoId"
    const val KEY_IS_EPISODE = "isEpisode"
    const val KEY_URL = "url"
    const val KEY_TITLE = "title"

    // Command args.
    const val KEY_TRACK_ID = "id" // audio/text track id; setText with "" (or absent) = Off
    const val KEY_SPEED = "speed" // Double
    const val KEY_ASPECT = "aspect" // AspectMode.key
    const val KEY_DELTA_MS = "deltaMs" // Long, CMD_SEEK_BY
    const val KEY_TARGET_WALL_MS = "targetWallMs" // Long, CMD_SEEK_WALL

    // State snapshot fields.
    const val KEY_AUDIO = "audio" // JSONArray<track>
    const val KEY_TEXT = "text" // JSONArray<track> (does NOT include the implicit Off row)
    const val KEY_TEXT_OFF = "textOff" // Boolean: true when no subtitle is selected
    const val KEY_STREAM_INFO = "streamInfo" // String: receiver-composed decode summary
    // Live-rewind snapshot fields (GH #33 cast scrubber / FF-RW).
    const val KEY_CAN_SEEK = "canSeek" // Boolean: a rewind session is rolling on the TV
    const val KEY_IS_LIVE = "isLive" // Boolean: playing the live edge (vs rewound)
    const val KEY_POSITION_WALL_MS = "positionWallMs" // Long
    const val KEY_WINDOW_START_MS = "windowStartMs" // Long (= tailWallMs)
    const val KEY_WINDOW_END_MS = "windowEndMs" // Long (= headWallMs)
    // Track object fields.
    const val KEY_ID = "id"
    const val KEY_LABEL = "label"
    const val KEY_SELECTED = "selected"

    /** A selectable audio or subtitle track. [id] is opaque and receiver-defined
     *  (an ExoPlayer group/track key); the sender only echoes it back on select. */
    data class Track(val id: String, val label: String, val selected: Boolean)

    /** The remote player state the sender's pickers render. */
    data class RemoteState(
        val audio: List<Track> = emptyList(),
        /** Subtitle tracks, EXCLUDING the implicit "Off" option the UI always adds. */
        val text: List<Track> = emptyList(),
        /** True when no subtitle track is currently selected (the "Off" row is checked). */
        val textOff: Boolean = true,
        val speed: Float = 1f,
        val aspect: AspectMode = AspectMode.FIT,
        /** True when the receiver's video track is disabled (audio-only). */
        val audioOnly: Boolean = false,
        /** Receiver-composed one-line decode summary for the Stream Info sheet. */
        val streamInfo: String = "",
        /** True when a rewind session is rolling on the TV (seek controls shown). */
        val canSeek: Boolean = false,
        /** True when playing the live edge; false when rewound into the buffer. */
        val isLive: Boolean = true,
        /** Current wall-clock playhead (epoch ms) while rewound. */
        val positionWallMs: Long = 0L,
        /** Rewind window start (oldest available) wall-clock ms (= tailWallMs). */
        val windowStartMs: Long = 0L,
        /** Rewind window end (live edge) wall-clock ms (= headWallMs). */
        val windowEndMs: Long = 0L,
        /**
         * The channel this receiver is playing, in the shared "disp:<uuid>"
         * format. Anchors a freshly connected companion phone (channel
         * up/down + phone-side Switch Stream) when the PHONE didn't start
         * the channel -- the tvOS host already sends this (parity 2026-07-17).
         */
        val channelId: String? = null,
    )

    /**
     * Aspect / resize modes. The sender shows [label]; the receiver maps [key] to
     * the actual PlayerView RESIZE_MODE. Kept in this shared file so the sender's
     * cycle order and labels always match what the receiver applies.
     */
    enum class AspectMode(val key: String, val label: String) {
        FIT("fit", "Fit"),
        FILL("fill", "Fill"),
        ZOOM("zoom", "Zoom");

        fun next(): AspectMode = entries[(ordinal + 1) % entries.size]

        companion object {
            fun fromKey(k: String?): AspectMode = entries.firstOrNull { it.key == k } ?: FIT
        }
    }

    // ---- encode ----

    /** Build a sender->receiver command string. */
    fun command(cmd: String, build: JSONObject.() -> Unit = {}): String =
        JSONObject().apply { put(KEY_CMD, cmd); build() }.toString()

    /** Build the receiver->sender state snapshot string. */
    fun encodeState(state: RemoteState): String = JSONObject().apply {
        put(KEY_CMD, CMD_STATE)
        put(KEY_AUDIO, JSONArray().apply { state.audio.forEach { put(trackJson(it)) } })
        put(KEY_TEXT, JSONArray().apply { state.text.forEach { put(trackJson(it)) } })
        put(KEY_TEXT_OFF, state.textOff)
        put(KEY_SPEED, state.speed.toDouble())
        put(KEY_ASPECT, state.aspect.key)
        put(KEY_AUDIO_ONLY, state.audioOnly)
        put(KEY_STREAM_INFO, state.streamInfo)
        put(KEY_CAN_SEEK, state.canSeek)
        put(KEY_IS_LIVE, state.isLive)
        put(KEY_POSITION_WALL_MS, state.positionWallMs)
        put(KEY_WINDOW_START_MS, state.windowStartMs)
        put(KEY_WINDOW_END_MS, state.windowEndMs)
        state.channelId?.takeIf { it.isNotBlank() }?.let { put(KEY_CHANNEL_ID, it) }
    }.toString()

    private fun trackJson(t: Track) = JSONObject().apply {
        put(KEY_ID, t.id)
        put(KEY_LABEL, t.label)
        put(KEY_SELECTED, t.selected)
    }

    // ---- decode ----

    /** Parse a receiver->sender [CMD_STATE] snapshot. */
    fun decodeState(json: JSONObject): RemoteState {
        fun tracks(name: String): List<Track> =
            json.optJSONArray(name)?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Track(o.optString(KEY_ID), o.optString(KEY_LABEL), o.optBoolean(KEY_SELECTED))
                }
            } ?: emptyList()
        return RemoteState(
            audio = tracks(KEY_AUDIO),
            text = tracks(KEY_TEXT),
            textOff = json.optBoolean(KEY_TEXT_OFF, true),
            speed = json.optDouble(KEY_SPEED, 1.0).toFloat(),
            aspect = AspectMode.fromKey(json.optString(KEY_ASPECT)),
            audioOnly = json.optBoolean(KEY_AUDIO_ONLY, false),
            streamInfo = json.optString(KEY_STREAM_INFO),
            canSeek = json.optBoolean(KEY_CAN_SEEK, false),
            isLive = json.optBoolean(KEY_IS_LIVE, true),
            positionWallMs = json.optLong(KEY_POSITION_WALL_MS, 0L),
            windowStartMs = json.optLong(KEY_WINDOW_START_MS, 0L),
            windowEndMs = json.optLong(KEY_WINDOW_END_MS, 0L),
            channelId = json.optString(KEY_CHANNEL_ID).takeIf { it.isNotBlank() },
        )
    }

    // ---- lightweight position tick (GH #33 cast scrubber) ----

    const val KEY_IS_PLAYING = "isPlaying" // Boolean: receiver transport state (companion remote)

    /** The crawling scrubber's live playhead + rewind window, pushed ~1Hz. */
    data class PositionSnapshot(
        val canSeek: Boolean = false,
        val isLive: Boolean = true,
        val positionWallMs: Long = 0L,
        val windowStartMs: Long = 0L,
        val windowEndMs: Long = 0L,
        /** Receiver transport state, carried on the tick for the LAN companion remote
         *  (which has no Cast MediaSession bridge). Cast Connect reports true. */
        val isPlaying: Boolean = true,
        /** Live anchor ("disp:<uuid>") riding the tick; null when idle/VOD. */
        val channelId: String? = null,
    )

    /** Build a receiver->sender [CMD_POSITION] tick. [isPlaying] defaults true so the
     *  existing Cast-Connect ticker (transport rides its MediaSession) is unaffected. */
    fun positionMessage(
        canSeek: Boolean,
        isLive: Boolean,
        positionWallMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        isPlaying: Boolean = true,
        channelId: String? = null,
    ): String = JSONObject().apply {
        put(KEY_CMD, CMD_POSITION)
        put(KEY_CAN_SEEK, canSeek)
        put(KEY_IS_LIVE, isLive)
        put(KEY_POSITION_WALL_MS, positionWallMs)
        put(KEY_WINDOW_START_MS, windowStartMs)
        put(KEY_WINDOW_END_MS, windowEndMs)
        put(KEY_IS_PLAYING, isPlaying)
        // GH #33: the full-state push after CMD_SET_CHANNEL races the async
        // deep-link re-prime (holder.currentChannelId still holds the OLD
        // channel), and nothing re-sent the anchor afterwards -- the phone's
        // flip/Switch-Stream anchor stayed stale (2026-07-17: sheet offered
        // "Streams for ESPN HD" while the TV played ESPN2). Riding the anchor
        // on the ~1Hz tick keeps clients current through companion flips AND
        // native TV-side flips.
        channelId?.takeIf { it.isNotBlank() }?.let { put(KEY_CHANNEL_ID, it) }
    }.toString()

    /** Parse a [CMD_POSITION] tick. */
    fun decodePosition(json: JSONObject): PositionSnapshot = PositionSnapshot(
        canSeek = json.optBoolean(KEY_CAN_SEEK, false),
        isLive = json.optBoolean(KEY_IS_LIVE, true),
        positionWallMs = json.optLong(KEY_POSITION_WALL_MS, 0L),
        windowStartMs = json.optLong(KEY_WINDOW_START_MS, 0L),
        windowEndMs = json.optLong(KEY_WINDOW_END_MS, 0L),
        isPlaying = json.optBoolean(KEY_IS_PLAYING, true),
        channelId = json.optString(KEY_CHANNEL_ID).takeIf { it.isNotBlank() },
    )
}
