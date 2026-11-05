package com.aeriotv.android.feature.player

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.db.entity.canSwitchStream
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * The channel long-press menu's Radio Mode row (guide grid and channel list,
 * every form factor). "Exit Radio Mode" on the channel Radio Mode is playing,
 * "Radio Mode" on any other is_radio channel (switches the audio when already in Radio
 * Mode). Null while casting (hidden, Apple parity) or for a channel with no
 * stream URL.
 */
fun radioMenuAction(
    context: Context,
    channel: M3UChannel,
    subtitle: String,
    beforeStart: () -> Unit = {},
): TvMenuAction? {
    if (channel.url.isBlank()) return null
    val entry = EntryPointAccessors.fromApplication(
        context.applicationContext,
        MainScaffoldEntryPoint::class.java,
    )
    if (entry.castSender().state.value is com.aeriotv.android.core.cast.AerioCastSender.State.Connected) return null
    val playingHere = RadioMode.state.value?.channel?.id == channel.id
    if (playingHere) {
        return TvMenuAction("Exit Radio Mode", Icons.Filled.Radio) { RadioMode.stop(context, "menu Exit") }
    }
    // 2026-10-14 decision: Radio Mode is offered only on channels the server
    // flags is_radio (tvOS review 2026-11-05, Apple parity).
    if (!channel.isRadio) return null
    return TvMenuAction("Radio Mode", Icons.Filled.Radio) {
        beforeStart()
        val headers = RadioMode.headersProvider?.invoke() ?: entry.exoPlayerHolder().httpHeaders
        if (RadioMode.isActive) RadioMode.switchTo(context, channel, headers, subtitle)
        else RadioMode.startFromMenu(context, channel, headers, subtitle)
    }
}

/**
 * Picks of a server-declared radio channel ([M3UChannel.isRadio]) open Radio
 * Mode (Logan 2026-10-14). The long-press menu's Watch wraps its play call in
 * [watchAsVideo] so it opens the full-screen player (artwork card, declared
 * fast start) instead.
 */
object RadioSelect {
    private const val TAG = "AerioRadio"
    @Volatile private var bypass = false

    /** Mirror of Settings > Player > Radio Mode by Default (AppPreferences
     *  radioModeByDefault), kept current by Navigation. When true every pick
     *  takes the declared radio path below. */
    @Volatile var byDefault = false

    /** True when a plain pick of [channel] would open Radio Mode (declared
     *  radio OR Radio Mode by Default). TV long-press menus show Watch first
     *  exactly then, so video stays one press away (Apple parity). */
    fun picksOpenRadio(channel: M3UChannel): Boolean =
        com.aeriotv.android.core.playback.DeclaredRadioChannels.isFlagged(channel.id) || byDefault

    /** Run [play] as a plain Watch: no Radio Mode auto-open for this call. */
    fun watchAsVideo(play: () -> Unit) {
        bypass = true
        try { play() } finally { bypass = false }
    }

    /**
     * A plain pick (tap, Select) of [channel]: when it is flagged radio,
     * start Radio Mode through the same entry as the long-press Radio Mode
     * item and return true. False (today's behavior) for an unflagged
     * channel, a Watch, while Radio Mode is already on (the pick switches the
     * audio), while a Multiview runs, or while casting.
     */
    fun maybeOpen(context: Context, channel: M3UChannel, subtitle: String, beforeStart: () -> Unit = {}): Boolean {
        val declared = com.aeriotv.android.core.playback.DeclaredRadioChannels.isFlagged(channel.id)
        if (!(declared || byDefault) || bypass || RadioMode.isActive) return false
        if (com.aeriotv.android.feature.multiview.MultiviewBackground.state.value != null) return false
        val action = radioMenuAction(context, channel, subtitle, beforeStart) ?: return false
        android.util.Log.i(TAG, "[RADIO] ${if (declared) "declared radio channel" else "Radio Mode by Default"} pick; opening Radio Mode ch=${channel.name}")
        action.onClick()
        return true
    }
}

/** Denial line for a 403 on the radio flag PATCH. */
internal const val RADIO_FLAG_DENIED_MESSAGE =
    "Your Dispatcharr account can't mark radio channels. Marking needs an administrator account."

/**
 * The channel long-press menu's "Mark as Radio Channel" / "Unmark Radio
 * Channel" row, right after Radio Mode. Dispatcharr Direct Connect only, for
 * an account whose cached permissions say admin (the Switch Stream gate).
 */
fun radioFlagMenuAction(
    context: Context,
    channel: M3UChannel,
    playlist: com.aeriotv.android.core.data.db.entity.PlaylistEntity?,
    viewModel: com.aeriotv.android.feature.playlist.PlaylistViewModel,
): TvMenuAction? {
    if (playlist == null || channel.dispatcharrChannelId == null) return null
    if (!playlist.isDispatcharrDirectConnect() || !playlist.canSwitchStream()) return null
    val target = !com.aeriotv.android.core.playback.DeclaredRadioChannels.isFlagged(channel.id)
    val label = if (target) "Mark as Radio Channel" else "Unmark Radio Channel"
    return TvMenuAction(label, Icons.Filled.Radio) {
        viewModel.toggleChannelRadio(channel, target) { error ->
            android.widget.Toast.makeText(
                context.applicationContext,
                switchStreamFailureMessage("update the radio flag", RADIO_FLAG_DENIED_MESSAGE, error),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
