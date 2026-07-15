package com.aeriotv.android.core.cast.companion

import org.json.JSONObject

/**
 * Session / handshake protocol for the LAN companion remote (GH #33 second-screen):
 * the Android TV app advertises itself over mDNS/NSD and runs a WebSocket server;
 * a phone discovers it, pairs with a code, and then drives the TV's NATIVE player.
 *
 * This is the SESSION layer. It is multiplexed over the SAME WebSocket as the
 * player-control messages, which are reused verbatim from [com.aeriotv.android.core
 * .cast.CastControl] (channel change / seek / audio / subtitle / speed / aspect /
 * play-pause). The two are told apart by which discriminator key is present:
 *  - session messages carry `"t"` ([KEY_TYPE]) -> handled here,
 *  - control messages carry `"cmd"` (CastControl.KEY_CMD) -> handled by CastControl.
 *
 * All player-control commands are refused until the connection is authenticated, so
 * a neighbour on the LAN can discover the TV but cannot touch playback without the
 * pairing code (or a token previously issued to that phone).
 */
object CompanionProtocol {
    /** Bump when the wire format changes incompatibly. */
    const val VERSION = 1

    /** NSD service type the TV registers and the phone browses for. */
    const val SERVICE_TYPE = "_aeriotv._tcp."
    /** NSD TXT attribute keys. */
    const val TXT_VERSION = "v"
    const val TXT_DEVICE_ID = "id" // stable per-TV id, so the phone can remember its pairing token

    const val KEY_TYPE = "t"

    // ---- TV -> phone: sent immediately on connect ----
    const val T_HELLO = "hello"
    const val KEY_DEVICE_NAME = "device" // TV friendly name for the picker / remote header
    const val KEY_VERSION = "v"
    const val KEY_NEEDS_PAIRING = "needsPairing" // true until a valid token/code is accepted
    const val KEY_NOW_PLAYING = "nowPlaying" // current channel title (blank if nothing playing)

    // ---- phone -> TV: authenticate with a remembered token OR a freshly typed code ----
    const val T_AUTH = "auth"
    const val KEY_TOKEN = "token" // remembered per-TV token, or blank on first pairing
    const val KEY_CODE = "code" // the 6-digit code the user read off the TV, or blank

    // ---- TV -> phone: auth result ----
    const val T_AUTH_OK = "authOk" // carries KEY_TOKEN for the phone to remember
    const val T_AUTH_FAIL = "authFail" // carries KEY_REASON
    const val KEY_REASON = "reason"
    const val REASON_BAD_CODE = "badCode"
    const val REASON_BAD_TOKEN = "badToken"

    fun hello(deviceName: String?, needsPairing: Boolean, nowPlaying: String?): String =
        JSONObject().apply {
            put(KEY_TYPE, T_HELLO)
            put(KEY_VERSION, VERSION)
            put(KEY_DEVICE_NAME, deviceName ?: "")
            put(KEY_NEEDS_PAIRING, needsPairing)
            put(KEY_NOW_PLAYING, nowPlaying ?: "")
        }.toString()

    fun auth(token: String?, code: String?): String =
        JSONObject().apply {
            put(KEY_TYPE, T_AUTH)
            put(KEY_TOKEN, token ?: "")
            put(KEY_CODE, code ?: "")
        }.toString()

    fun authOk(token: String): String =
        JSONObject().apply {
            put(KEY_TYPE, T_AUTH_OK)
            put(KEY_TOKEN, token)
        }.toString()

    fun authFail(reason: String): String =
        JSONObject().apply {
            put(KEY_TYPE, T_AUTH_FAIL)
            put(KEY_REASON, reason)
        }.toString()

    /** The session message type, or null when this frame is a CastControl `cmd` message. */
    fun typeOf(json: JSONObject): String? =
        json.optString(KEY_TYPE).takeIf { it.isNotBlank() }
}
