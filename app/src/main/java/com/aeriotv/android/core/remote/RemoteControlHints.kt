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

    /** The player's Select line: "Press Select for X. Hold Select for Y."
     *  Null when neither OK slot maps to a phrasable action. */
    fun selectHint(map: RemoteControlMap): String? {
        val short = playerPhrase(map.playerAction(RemoteSlot.OK_SHORT))
        val long = playerPhrase(map.playerAction(RemoteSlot.OK_LONG))
        val parts = buildList {
            short?.let { add("Press Select for $it.") }
            long?.let { add("Hold Select for $it.") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** Whether Up/Down still channel-surf (gates the flip hint chip). */
    fun verticalFlipMapped(map: RemoteControlMap): Boolean =
        map.playerAction(RemoteSlot.UP_SHORT) == PlayerRemoteAction.CHANNEL_UP &&
            map.playerAction(RemoteSlot.DOWN_SHORT) == PlayerRemoteAction.CHANNEL_DOWN

    private fun guidePhrase(action: GuideRemoteAction): String? = when (action) {
        GuideRemoteAction.TIMELINE_BACK -> "browse earlier programs"
        GuideRemoteAction.TIMELINE_FORWARD -> "browse later programs"
        GuideRemoteAction.FOCUS_GROUP_PILLS -> "jump to the group pills"
        GuideRemoteAction.PAGE_UP -> "page channels up"
        GuideRemoteAction.PAGE_DOWN -> "page channels down"
        GuideRemoteAction.JUMP_TO_NOW -> "jump to now"
        GuideRemoteAction.JUMP_TO_TOP -> "jump to the top channel"
        GuideRemoteAction.RESUME_PLAYER -> "return to the player"
        else -> null
    }

    /** The guide's hold-Left chip; null when the slot is unmapped. */
    fun guideHoldLeftHint(map: RemoteControlMap): String? =
        guidePhrase(map.guideAction(RemoteSlot.LEFT_LONG))
            ?.let { "Hold left on remote to $it." }
}
