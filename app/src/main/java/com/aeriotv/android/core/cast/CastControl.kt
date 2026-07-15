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
    const val CMD_STATE = "state" // receiver -> sender snapshot

    // Command args.
    const val KEY_TRACK_ID = "id" // audio/text track id; setText with "" (or absent) = Off
    const val KEY_SPEED = "speed" // Double
    const val KEY_ASPECT = "aspect" // AspectMode.key

    // State snapshot fields.
    const val KEY_AUDIO = "audio" // JSONArray<track>
    const val KEY_TEXT = "text" // JSONArray<track> (does NOT include the implicit Off row)
    const val KEY_TEXT_OFF = "textOff" // Boolean: true when no subtitle is selected
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
        )
    }
}
