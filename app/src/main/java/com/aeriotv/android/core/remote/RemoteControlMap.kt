package com.aeriotv.android.core.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * TV remote button mapping (task #190 initiative, plan:
 * ~/Desktop/AerioTV-Remote-Control-Plan.md). One map per CONTEXT
 * (player vs guide), shared JSON schema with the
 * Apple platforms:
 *
 * ```json
 * { "version": 1, "preset": "aerioDefault",
 *   "player": { "okLong": "optionsMenu", ... },
 *   "guide":  { "ffwd": "pageDown", ... } }
 * ```
 *
 * Design invariants (do not relax without a plan change):
 * - Back is NEVER a slot: the Back/mini-player/guide-ladder model is
 *   device-verified canon and stays hardcoded.
 * - Guide SHORT arrows are focus navigation, never mappable.
 * - Unknown slots/actions in a stored blob are IGNORED on read
 *   (forward compat with newer app versions); missing slots fall back
 *   to [RemoteControlMap.DEFAULT]'s assignment at RESOLVE time, so a
 *   partial blob never strands a button.
 */

/** Stable wire ids. Which slots a platform/context actually offers is
 *  decided by the settings UI + resolver, not by this enum. */
enum class RemoteSlot(val wire: String) {
    OK_SHORT("okShort"), OK_LONG("okLong"),
    UP_SHORT("upShort"), UP_LONG("upLong"),
    DOWN_SHORT("downShort"), DOWN_LONG("downLong"),
    LEFT_SHORT("leftShort"), LEFT_LONG("leftLong"),
    RIGHT_SHORT("rightShort"), RIGHT_LONG("rightLong"),
    PLAY_PAUSE("playPause"), FFWD("ffwd"), REWIND("rewind"),
    CHANNEL_UP("channelUp"), CHANNEL_DOWN("channelDown");

    companion object {
        fun fromWire(s: String): RemoteSlot? = entries.firstOrNull { it.wire == s }
    }
}

/** Actions available while the fullscreen live player is frontmost. */
enum class PlayerRemoteAction(val wire: String) {
    CHANNEL_UP("channelUp"), CHANNEL_DOWN("channelDown"),
    LAST_CHANNEL("lastChannel"),
    RECENT_CHANNELS("recentChannels"),
    TOGGLE_CONTROLS("toggleControls"),
    SHOW_PROGRAM_INFO("showProgramInfo"),
    OPTIONS_MENU("optionsMenu"),
    PLAY_PAUSE("playPause"),
    SEEK_FORWARD("seekForward"), SEEK_BACKWARD("seekBackward"),
    RESTART_PROGRAM("restartProgram"), JUMP_TO_LIVE("jumpToLive"),
    MINIMIZE_TO_GUIDE("minimizeToGuide"),
    CHANNEL_LIST("channelList"),
    SUBTITLES("subtitles"), AUDIO_TRACKS("audioTracks"),
    ASPECT_RATIO("aspectRatio"), RECORD("record"),
    SLEEP_TIMER("sleepTimer"), OPEN_SEARCH("openSearch"),
    /** Fixed hold-Back behavior (stop with NO mini promotion); dispatched
     *  by MainActivity's long-Back path, deliberately NOT offered as a
     *  mappable choice in settings (Back semantics stay hardcoded). */
    STOP_PLAYBACK("stopPlayback"),
    NONE("none");

    companion object {
        fun fromWire(s: String): PlayerRemoteAction? = entries.firstOrNull { it.wire == s }
    }
}

/** Actions available while the TV guide is frontmost. */
enum class GuideRemoteAction(val wire: String) {
    PAGE_UP("pageUp"), PAGE_DOWN("pageDown"),
    TIMELINE_BACK("timelineBack"), TIMELINE_FORWARD("timelineForward"),
    JUMP_TO_NOW("jumpToNow"), JUMP_TO_TOP("jumpToTop"),
    FOCUS_GROUP_PILLS("focusGroupPills"),
    RESUME_PLAYER("resumePlayer"), CLOSE_MINI_PLAYER("closeMiniPlayer"),
    PROGRAM_INFO("programInfo"), OPEN_SEARCH("openSearch"),
    NONE("none");

    companion object {
        fun fromWire(s: String): GuideRemoteAction? = entries.firstOrNull { it.wire == s }
    }
}

enum class RemotePreset(val wire: String) {
    DEFAULT("default"), CUSTOM("custom");

    companion object {
        /** Only wires this build has ever written are honored. Anything
         *  else (notably the pre-release preset-picker blobs) is null and
         *  the whole stored map is DISCARDED at decode - those betas'
         *  slot layouts no longer match the shipped scheme, so keeping
         *  them as CUSTOM stranded users on stale mappings. */
        fun fromWire(s: String): RemotePreset? =
            entries.firstOrNull { it.wire == s }
    }
}

data class RemoteControlMap(
    val preset: RemotePreset = RemotePreset.DEFAULT,
    val player: Map<RemoteSlot, PlayerRemoteAction> = emptyMap(),
    val guide: Map<RemoteSlot, GuideRemoteAction> = emptyMap(),
) {
    /** Resolve with fallback to the default map so a partial/legacy blob
     *  never leaves a button dead. Explicit NONE is respected. */
    fun playerAction(slot: RemoteSlot): PlayerRemoteAction =
        player[slot] ?: DEFAULT.player[slot] ?: PlayerRemoteAction.NONE

    fun guideAction(slot: RemoteSlot): GuideRemoteAction =
        guide[slot] ?: DEFAULT.guide[slot] ?: GuideRemoteAction.NONE

    /** First slot currently mapped to [action], for dynamic hint copy. */
    fun playerSlotFor(action: PlayerRemoteAction): RemoteSlot? =
        RemoteSlot.entries.firstOrNull { playerAction(it) == action }

    fun guideSlotFor(action: GuideRemoteAction): RemoteSlot? =
        RemoteSlot.entries.firstOrNull { guideAction(it) == action }

    fun toJson(): String {
        val obj = buildJsonObject {
            put("version", SCHEMA_VERSION)
            put("preset", preset.wire)
            put("player", buildJsonObject {
                player.forEach { (slot, action) -> put(slot.wire, action.wire) }
            })
            put("guide", buildJsonObject {
                guide.forEach { (slot, action) -> put(slot.wire, action.wire) }
            })
        }
        return obj.toString()
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** The PRE-initiative control scheme, kept verbatim so any
         *  regression can be reverted by pointing DEFAULT back at it.
         *  NOT user-selectable (Logan 2026-07-20): the redesigned
         *  scheme below is the app's one standard layout now. */
        val LEGACY_SCHEME = RemoteControlMap(
            preset = RemotePreset.DEFAULT,
            player = mapOf(
                RemoteSlot.UP_SHORT to PlayerRemoteAction.CHANNEL_UP,
                RemoteSlot.DOWN_SHORT to PlayerRemoteAction.CHANNEL_DOWN,
                RemoteSlot.LEFT_SHORT to PlayerRemoteAction.SEEK_BACKWARD,
                RemoteSlot.RIGHT_SHORT to PlayerRemoteAction.SEEK_FORWARD,
                RemoteSlot.OK_SHORT to PlayerRemoteAction.TOGGLE_CONTROLS,
                RemoteSlot.OK_LONG to PlayerRemoteAction.NONE,
                RemoteSlot.PLAY_PAUSE to PlayerRemoteAction.PLAY_PAUSE,
                RemoteSlot.FFWD to PlayerRemoteAction.SEEK_FORWARD,
                RemoteSlot.REWIND to PlayerRemoteAction.SEEK_BACKWARD,
                RemoteSlot.CHANNEL_UP to PlayerRemoteAction.CHANNEL_UP,
                RemoteSlot.CHANNEL_DOWN to PlayerRemoteAction.CHANNEL_DOWN,
            ),
            guide = mapOf(
                RemoteSlot.LEFT_LONG to GuideRemoteAction.TIMELINE_BACK,
                RemoteSlot.RIGHT_LONG to GuideRemoteAction.CLOSE_MINI_PLAYER,
                RemoteSlot.OK_LONG to GuideRemoteAction.PROGRAM_INFO,
                RemoteSlot.PLAY_PAUSE to GuideRemoteAction.RESUME_PLAYER,
            ),
        )

        /** The app's standard control scheme (Logan 2026-07-20 revision:
         *  OK went back to surfacing the player controls, and hold-OK is
         *  unmapped - options live on the chrome's Options button): OK =
         *  show controls, Up/Down = channel surf, hold-Up = recently
         *  watched, hold-Down = search, Right = previous-channel zap,
         *  hold-Right = program info, hold-Left in the guide pages into
         *  already-aired programmes. */
        val DEFAULT = RemoteControlMap(
            preset = RemotePreset.DEFAULT,
            player = mapOf(
                RemoteSlot.OK_SHORT to PlayerRemoteAction.TOGGLE_CONTROLS,
                RemoteSlot.OK_LONG to PlayerRemoteAction.NONE,
                RemoteSlot.UP_SHORT to PlayerRemoteAction.CHANNEL_UP,
                RemoteSlot.DOWN_SHORT to PlayerRemoteAction.CHANNEL_DOWN,
                RemoteSlot.UP_LONG to PlayerRemoteAction.RECENT_CHANNELS,
                RemoteSlot.DOWN_LONG to PlayerRemoteAction.OPEN_SEARCH,
                RemoteSlot.LEFT_SHORT to PlayerRemoteAction.CHANNEL_LIST,
                RemoteSlot.LEFT_LONG to PlayerRemoteAction.MINIMIZE_TO_GUIDE,
                RemoteSlot.RIGHT_SHORT to PlayerRemoteAction.LAST_CHANNEL,
                RemoteSlot.RIGHT_LONG to PlayerRemoteAction.SHOW_PROGRAM_INFO,
                RemoteSlot.PLAY_PAUSE to PlayerRemoteAction.PLAY_PAUSE,
                RemoteSlot.FFWD to PlayerRemoteAction.SEEK_FORWARD,
                RemoteSlot.REWIND to PlayerRemoteAction.SEEK_BACKWARD,
                RemoteSlot.CHANNEL_UP to PlayerRemoteAction.CHANNEL_UP,
                RemoteSlot.CHANNEL_DOWN to PlayerRemoteAction.CHANNEL_DOWN,
            ),
            guide = mapOf(
                RemoteSlot.FFWD to GuideRemoteAction.PAGE_DOWN,
                RemoteSlot.REWIND to GuideRemoteAction.PAGE_UP,
                RemoteSlot.CHANNEL_UP to GuideRemoteAction.PAGE_UP,
                RemoteSlot.CHANNEL_DOWN to GuideRemoteAction.PAGE_DOWN,
                RemoteSlot.OK_LONG to GuideRemoteAction.PROGRAM_INFO,
                RemoteSlot.LEFT_LONG to GuideRemoteAction.TIMELINE_BACK,
                RemoteSlot.RIGHT_LONG to GuideRemoteAction.CLOSE_MINI_PLAYER,
                RemoteSlot.PLAY_PAUSE to GuideRemoteAction.RESUME_PLAYER,
            ),
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Tolerant decode: unknown slots/actions are skipped, malformed
         *  input falls back to [DEFAULT]. Never throws. */
        fun fromJson(raw: String?): RemoteControlMap {
            if (raw.isNullOrBlank()) return DEFAULT
            return runCatching {
                val obj = json.parseToJsonElement(raw).jsonObject
                val preset = RemotePreset.fromWire(
                    obj["preset"]?.jsonPrimitive?.content ?: RemotePreset.DEFAULT.wire,
                ) ?: return DEFAULT
                // preset=default means "track the app's standard scheme":
                // ignore any stored slot dump (older builds persisted the
                // full resolved map, which would otherwise pin those users
                // to that build's defaults forever - e.g. holding upLong on
                // lastChannel after the scheme moved it to recentChannels).
                // Stored slots are only authoritative for CUSTOM.
                if (preset == RemotePreset.DEFAULT) return DEFAULT
                RemoteControlMap(
                    preset = preset,
                    player = decodeContext(obj["player"] as? JsonObject) { PlayerRemoteAction.fromWire(it) },
                    guide = decodeContext(obj["guide"] as? JsonObject) { GuideRemoteAction.fromWire(it) },
                )
            }.getOrDefault(DEFAULT)
        }

        private fun <A : Any> decodeContext(
            obj: JsonObject?,
            action: (String) -> A?,
        ): Map<RemoteSlot, A> {
            if (obj == null) return emptyMap()
            val out = mutableMapOf<RemoteSlot, A>()
            for ((key, value) in obj) {
                val slot = RemoteSlot.fromWire(key) ?: continue
                val act = runCatching { action(value.jsonPrimitive.content) }.getOrNull() ?: continue
                out[slot] = act
            }
            return out
        }
    }
}
