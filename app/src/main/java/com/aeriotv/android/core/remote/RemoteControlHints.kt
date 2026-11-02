package com.aeriotv.android.core.remote

/**
 * Human copy for the on-screen gesture hint chips, derived from the
 * EFFECTIVE remote map so a remapped button never advertises a stale
 * gesture (the chips used to hardcode the standard scheme: "Press Select
 * for program info", "Hold left ... earlier programs").
 *
 * Actions without a natural short phrase return null and their sentence
 * is simply omitted - a hint that says nothing beats one that lies.
 */
/**
 * One "key  action" reminder for the on-screen hint strip. [key] is the
 * button as printed on the remote ("Hold Left", "Play/Pause"), [action] is
 * what that press does right now: sentence case, no punctuation.
 */
data class RemoteHint(val key: String, val action: String)

object RemoteControlHints {

    private fun playerPhrase(action: PlayerRemoteAction): String? = when (action) {
        PlayerRemoteAction.SHOW_PROGRAM_INFO -> "program info"
        PlayerRemoteAction.TOGGLE_CONTROLS -> "player controls"
        PlayerRemoteAction.OPTIONS_MENU -> "options"
        PlayerRemoteAction.CHANNEL_LIST -> "the channel list"
        PlayerRemoteAction.RECENT_CHANNELS -> "recently watched channels"
        PlayerRemoteAction.OPEN_SEARCH -> "search"
        PlayerRemoteAction.LAST_CHANNEL -> "the previous channel"
        PlayerRemoteAction.MINIMIZE_TO_GUIDE -> "the TV guide"
        else -> null
    }

    /** The player's Select line, compressed: "Select: X  ·  Hold: Y".
     *  Null when neither OK slot maps to a phrasable action. */
    fun selectHint(map: RemoteControlMap): String? {
        val short = playerPhrase(map.playerAction(RemoteSlot.OK_SHORT))
        val long = playerPhrase(map.playerAction(RemoteSlot.OK_LONG))
        val parts = buildList {
            short?.let { add("Select: $it") }
            long?.let { add("Hold Select: $it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    /** Whether Up/Down still channel-surf (gates the flip hint chip). */
    fun verticalFlipMapped(map: RemoteControlMap): Boolean =
        map.playerAction(RemoteSlot.UP_SHORT) == PlayerRemoteAction.CHANNEL_UP &&
            map.playerAction(RemoteSlot.DOWN_SHORT) == PlayerRemoteAction.CHANNEL_DOWN

    /** The player's Left/Right line (Logan 2026-08-06: users forgot what the
     *  horizontal presses do). CHANNEL_LIST gets the fixed second-press note -
     *  Left inside the channel list opens the group sidebar - because that
     *  stage is built into the overlay, not the map. Null when neither slot
     *  maps to a phrasable action. */
    fun playerHorizontalHint(map: RemoteControlMap): String? {
        val left = map.playerAction(RemoteSlot.LEFT_SHORT)
        val right = map.playerAction(RemoteSlot.RIGHT_SHORT)
        val parts = buildList {
            if (left == PlayerRemoteAction.CHANNEL_LIST) {
                add("Left = channel list")
                add("Left again = groups")
            } else {
                playerPhrase(left)?.let { add("Left = $it") }
            }
            playerPhrase(right)?.let { add("Right = $it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    private fun guidePhrase(action: GuideRemoteAction): String? = when (action) {
        GuideRemoteAction.TIMELINE_BACK -> "browse earlier programs"
        GuideRemoteAction.TIMELINE_FORWARD -> "browse later programs"
        GuideRemoteAction.FOCUS_GROUP_PILLS -> "jump to the group pills"
        GuideRemoteAction.PAGE_UP -> "page channels up"
        GuideRemoteAction.PAGE_DOWN -> "page channels down"
        GuideRemoteAction.JUMP_TO_NOW -> "jump to now"
        GuideRemoteAction.JUMP_TO_DAY -> "jump to a day and time"
        GuideRemoteAction.JUMP_TO_TOP -> "jump to the top channel"
        GuideRemoteAction.RESUME_PLAYER -> "return to the player"
        else -> null
    }

    /** The guide's hold-Left chip; null when the slot is unmapped. */
    fun guideHoldLeftHint(map: RemoteControlMap, sidebarGroups: Boolean = false): String? =
        guidePhrase(map.guideAction(RemoteSlot.LEFT_LONG, sidebarGroups))
            ?.let { "Hold left on remote to $it." }

    /**
     * Terse phrases for the corner hint chip. [guidePhrase] is written for
     * full sentences ("browse earlier programs"); dropped verbatim into the
     * chip it pushed the pill under the centered tab bar, so the chip gets
     * its own two-or-three-word forms. Mirrors Apple's `guidePhraseShort`.
     */
    private fun guidePhraseShort(action: GuideRemoteAction): String? = when (action) {
        GuideRemoteAction.TIMELINE_BACK -> "earlier"
        GuideRemoteAction.TIMELINE_FORWARD -> "later"
        GuideRemoteAction.FOCUS_GROUP_PILLS -> "group pills"
        GuideRemoteAction.PAGE_UP -> "page up"
        GuideRemoteAction.PAGE_DOWN -> "page down"
        GuideRemoteAction.JUMP_TO_NOW -> "now"
        GuideRemoteAction.JUMP_TO_DAY -> "jump to"
        GuideRemoteAction.JUMP_TO_TOP -> "top channel"
        GuideRemoteAction.RESUME_PLAYER -> "player"
        else -> null
    }

    /** Terse form for the compact combined guide nav chip: "Hold Left = X". */
    fun guideHoldLeftShort(map: RemoteControlMap, sidebarGroups: Boolean = false): String? =
        guidePhraseShort(map.guideAction(RemoteSlot.LEFT_LONG, sidebarGroups))
            ?.let { "Hold Left = $it" }

    // ---------------------------------------------------------------
    // The remote hint STRIP (Logan 2026-09-11). One line of "key action"
    // pairs, generated from the CURRENT effective map plus the current app
    // state, never hard-coded: a user who remaps a button in Settings >
    // Remote Control must see their own button named here. A slot whose
    // action is unmapped, or not applicable in this mode, drops out.
    // ---------------------------------------------------------------

    /** The button name as printed on the remote, for the strip's key column. */
    fun slotLabel(slot: RemoteSlot): String = when (slot) {
        RemoteSlot.OK_SHORT -> "OK"
        RemoteSlot.OK_LONG -> "Hold OK"
        RemoteSlot.UP_SHORT -> "Up"
        RemoteSlot.UP_LONG -> "Hold Up"
        RemoteSlot.DOWN_SHORT -> "Down"
        RemoteSlot.DOWN_LONG -> "Hold Down"
        RemoteSlot.LEFT_SHORT -> "Left"
        RemoteSlot.LEFT_LONG -> "Hold Left"
        RemoteSlot.RIGHT_SHORT -> "Right"
        RemoteSlot.RIGHT_LONG -> "Hold Right"
        RemoteSlot.PLAY_PAUSE -> "Play/Pause"
        RemoteSlot.FFWD -> "Fast Forward"
        RemoteSlot.REWIND -> "Rewind"
        RemoteSlot.CHANNEL_UP -> "Channel Up"
        RemoteSlot.CHANNEL_DOWN -> "Channel Down"
        RemoteSlot.BACK_SHORT -> "Back"
        RemoteSlot.BACK_LONG -> "Hold Back"
    }

    /** Strip wording for a guide action; null = nothing worth advertising. */
    private fun guideStripAction(action: GuideRemoteAction, sidebarGroups: Boolean): String? =
        when (action) {
            GuideRemoteAction.FOCUS_GROUP_PILLS -> if (sidebarGroups) "Groups" else "Group pills"
            GuideRemoteAction.TIMELINE_BACK -> "Earlier programs"
            GuideRemoteAction.TIMELINE_FORWARD -> "Later programs"
            GuideRemoteAction.PAGE_UP -> "Page up"
            GuideRemoteAction.PAGE_DOWN -> "Page down"
            GuideRemoteAction.JUMP_TO_NOW -> "Now"
            GuideRemoteAction.JUMP_TO_DAY -> "Jump to day"
            GuideRemoteAction.JUMP_TO_TOP -> "Top channel"
            GuideRemoteAction.RESUME_PLAYER -> "Resume"
            GuideRemoteAction.CLOSE_MINI_PLAYER -> "Close mini"
            GuideRemoteAction.PROGRAM_INFO -> "Program info"
            GuideRemoteAction.PROGRAM_MENU -> "Program menu"
            GuideRemoteAction.RECORD -> "Record"
            GuideRemoteAction.PLAY -> "Play"
            GuideRemoteAction.OPEN_SEARCH -> "Search"
            GuideRemoteAction.BACK_LADDER -> "Top channel"
            GuideRemoteAction.NAVIGATE, GuideRemoteAction.NONE -> null
        }

    /** Strip wording for a player action; null = nothing worth advertising. */
    private fun playerStripAction(action: PlayerRemoteAction): String? = when (action) {
        PlayerRemoteAction.TOGGLE_CONTROLS -> "Player controls"
        PlayerRemoteAction.SHOW_PROGRAM_INFO -> "Program info"
        PlayerRemoteAction.OPTIONS_MENU -> "Options"
        PlayerRemoteAction.CHANNEL_LIST -> "Channel list"
        PlayerRemoteAction.RECENT_CHANNELS -> "Recent channels"
        PlayerRemoteAction.OPEN_SEARCH -> "Search"
        PlayerRemoteAction.LAST_CHANNEL -> "Previous channel"
        PlayerRemoteAction.MINIMIZE_TO_GUIDE -> "TV Guide"
        PlayerRemoteAction.CHANNEL_UP -> "Channel up"
        PlayerRemoteAction.CHANNEL_DOWN -> "Channel down"
        PlayerRemoteAction.PLAY_PAUSE -> "Play or pause"
        PlayerRemoteAction.SEEK_FORWARD -> "Seek forward"
        PlayerRemoteAction.SEEK_BACKWARD -> "Seek back"
        PlayerRemoteAction.RESTART_PROGRAM -> "Restart"
        PlayerRemoteAction.JUMP_TO_LIVE -> "Jump to live"
        PlayerRemoteAction.SUBTITLES -> "Subtitles"
        PlayerRemoteAction.AUDIO_TRACKS -> "Audio"
        PlayerRemoteAction.ASPECT_RATIO -> "Aspect ratio"
        PlayerRemoteAction.RECORD -> "Record"
        PlayerRemoteAction.SLEEP_TIMER -> "Sleep timer"
        PlayerRemoteAction.STOP_PLAYBACK -> "Stop"
        PlayerRemoteAction.NONE -> null
    }

    /** Cap: the strip is ONE line, so an unbounded list would just truncate. */
    private const val MAX_PAIRS = 6

    /**
     * Live TV tab strip. [sidebarGroups] is the guide's group-selector mode
     * ("sidebar" drawer vs the pill row), [miniActive] whether the corner mini
     * is playing.
     *
     * The Select / Left / Right pairs read the same resolution GuideGrid.kt
     * dispatches (RemoteControlMap.guideAction with the group selector): an
     * unset hold-Left opens the drawer in sidebar mode and browses earlier
     * programs with the pills; a slot left on its default navigation or
     * play is not advertised. With a mini up the guide's Back belongs to the
     * mini (single = resume, double = top channel, fixed); without one the
     * single Back and Hold Back pairs come from the guide map's backShort and
     * backLong slots (default: the Back ladder, hold unmapped).
     */
    fun guideStripHints(
        map: RemoteControlMap,
        sidebarGroups: Boolean,
        miniActive: Boolean,
        /** Radio Mode on the guide (Apple TV parity): Play/Pause switches to
         *  video and hold Right or Back twice stops, as the banner's two buttons. */
        radioActive: Boolean = false,
    ): List<RemoteHint> = buildList {
        // Select plays by default, so only a remapped Select is worth a pair;
        // the arrows and their holds are advertised whenever they do something
        // other than move focus (truthful to the map, Settings > Remote Control).
        map.guideAction(RemoteSlot.OK_SHORT, sidebarGroups).takeIf { it != GuideRemoteAction.PLAY }
            ?.let { guideStripAction(it, sidebarGroups) }
            ?.let { add(RemoteHint(slotLabel(RemoteSlot.OK_SHORT), it)) }
        for (slot in listOf(RemoteSlot.LEFT_LONG, RemoteSlot.LEFT_SHORT, RemoteSlot.RIGHT_SHORT, RemoteSlot.RIGHT_LONG)) {
            val action = map.guideAction(slot, sidebarGroups)
            // The mini-player pairs below cover these, and only while a mini is up.
            if (action == GuideRemoteAction.CLOSE_MINI_PLAYER || action == GuideRemoteAction.RESUME_PLAYER) continue
            if (radioActive && slot == RemoteSlot.RIGHT_LONG) continue
            guideStripAction(action, sidebarGroups)?.let {
                add(RemoteHint(slotLabel(slot), it))
            }
        }
        if (miniActive) {
            add(RemoteHint("Double Back", "Top channel"))
        } else {
            for (slot in listOf(RemoteSlot.BACK_SHORT, RemoteSlot.BACK_LONG)) {
                val action = map.guideAction(slot, sidebarGroups)
                if (action == GuideRemoteAction.CLOSE_MINI_PLAYER || action == GuideRemoteAction.RESUME_PLAYER) continue
                guideStripAction(action, sidebarGroups)?.let { add(RemoteHint(slotLabel(slot), it)) }
            }
        }
        if (radioActive && !miniActive) {
            // Back twice stops Radio Mode (Apple TV parity), the player's pair style.
            add(RemoteHint("Back twice", "Stop"))
            add(RemoteHint(slotLabel(RemoteSlot.PLAY_PAUSE), "Switch to Video Mode"))
            add(RemoteHint(slotLabel(RemoteSlot.RIGHT_LONG), "Stop"))
        }
        if (miniActive) {
            map.guideSlotFor(GuideRemoteAction.RESUME_PLAYER)?.let {
                add(RemoteHint(slotLabel(it), "Resume"))
            }
            map.guideSlotFor(GuideRemoteAction.CLOSE_MINI_PLAYER)?.let {
                add(RemoteHint(slotLabel(it), "Close mini"))
            }
        }
    }.take(MAX_PAIRS)

    /**
     * Live player strip. Mirrors PlayerScreen's own key routing: in catch-up
     * (and while the Live Rewind buffer is rolling with a seek-mapped slot)
     * Left/Right scrub the timeline instead of running their mapped actions,
     * Up/Down only channel-surf while [channelFlip] is on and not replaying,
     * and Back is fixed (minimize to the corner mini, hold to stop).
     */
    fun livePlayerStripHints(
        map: RemoteControlMap,
        catchupMode: Boolean,
        rewindBuffering: Boolean,
        channelFlip: Boolean,
        controlsVisible: Boolean,
    ): List<RemoteHint> = buildList {
        val scrubbing = catchupMode ||
            (
                rewindBuffering &&
                    (
                        map.playerAction(RemoteSlot.LEFT_SHORT) == PlayerRemoteAction.SEEK_BACKWARD ||
                            map.playerAction(RemoteSlot.RIGHT_SHORT) == PlayerRemoteAction.SEEK_FORWARD
                        )
                )
        // With the controls on screen OK operates the FOCUSED control, not the
        // mapped chrome toggle, so the OK pairs would be lying; the strip only
        // ever renders in that state, but the gate is explicit so a future
        // chrome-hidden caller gets the right copy (Logan 2026-09-11).
        if (!controlsVisible) {
            playerStripAction(map.playerAction(RemoteSlot.OK_SHORT))?.let {
                add(RemoteHint(slotLabel(RemoteSlot.OK_SHORT), it))
            }
            playerStripAction(map.playerAction(RemoteSlot.OK_LONG))?.let {
                add(RemoteHint(slotLabel(RemoteSlot.OK_LONG), it))
            }
        }
        if (scrubbing) {
            add(RemoteHint("Left/Right", "Scrub"))
        } else {
            if (channelFlip && verticalFlipMapped(map)) {
                add(RemoteHint("Up/Down", "Channels"))
            } else {
                if (channelFlip) {
                    playerStripAction(map.playerAction(RemoteSlot.UP_SHORT))?.let {
                        add(RemoteHint(slotLabel(RemoteSlot.UP_SHORT), it))
                    }
                    playerStripAction(map.playerAction(RemoteSlot.DOWN_SHORT))?.let {
                        add(RemoteHint(slotLabel(RemoteSlot.DOWN_SHORT), it))
                    }
                }
            }
            playerStripAction(map.playerAction(RemoteSlot.LEFT_SHORT))?.let {
                add(RemoteHint(slotLabel(RemoteSlot.LEFT_SHORT), it))
            }
            playerStripAction(map.playerAction(RemoteSlot.RIGHT_SHORT))?.let {
                add(RemoteHint(slotLabel(RemoteSlot.RIGHT_SHORT), it))
            }
            playerStripAction(map.playerAction(RemoteSlot.LEFT_LONG))?.let {
                add(RemoteHint(slotLabel(RemoteSlot.LEFT_LONG), it))
            }
            playerStripAction(map.playerAction(RemoteSlot.RIGHT_LONG))?.let {
                add(RemoteHint(slotLabel(RemoteSlot.RIGHT_LONG), it))
            }
        }
        // Back is fixed and identical whether or not the controls are up:
        // PlayerScreen's BackHandler minimizes straight to the corner mini (the
        // old reveal-chrome-first rung was removed deliberately), and a
        // catch-up replay exits to where the user came from.
        add(RemoteHint("Back", if (catchupMode) "Exit" else "Mini player"))
        // Logan 2026-09-14: Back twice (within 300 ms) ends playback outright
        // - no mini, stream stopped, back on the page the user came from. A
        // catch-up replay already exits on the first Back, so it has no
        // second rung to advertise.
        if (!catchupMode) add(RemoteHint("Back twice", "Close"))
        // The two Back rungs are the fixed tail of this strip, so it carries
        // one pair more than the shared cap rather than dropping "Back twice".
    }.take(MAX_PAIRS + 1)

    /**
     * VOD / recording player strip. VODPlayerScreen runs its OWN transport
     * model (a focus ZONE walked with Left/Right, Up into the scrubber) and
     * deliberately does not consult the remote map, so these pairs follow the
     * zone rather than the mapping. [scrubberZone] = the scrubber holds the
     * zone.
     */
    fun vodPlayerStripHints(
        scrubberZone: Boolean,
        isPaused: Boolean,
    ): List<RemoteHint> = buildList {
        if (scrubberZone) {
            add(RemoteHint("Left/Right", "Scrub"))
            add(RemoteHint("OK", "Go to time"))
            add(RemoteHint("Down", "Controls"))
        } else {
            add(RemoteHint("OK", if (isPaused) "Play" else "Pause"))
            add(RemoteHint("Left/Right", "Controls"))
            add(RemoteHint("Up", "Scrubber"))
        }
        add(RemoteHint("Play/Pause", if (isPaused) "Play" else "Pause"))
        add(RemoteHint("Back", "Exit"))
    }.take(MAX_PAIRS)
}
