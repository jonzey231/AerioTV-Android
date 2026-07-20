package com.aeriotv.android.feature.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped layout state for the single persistent Media3 PlayerView.
 *
 * Direct counterpart of [MpvWindowState] for the Media3 migration.
 * Architecture is identical: ONE PlayerView lives in MainActivity's
 * setContent root Box outside the NavHost; routes inside the NavHost
 * (PlayerScreen, TvMiniPlayerOverlay) flip this state instead of
 * creating their own AndroidView. The View's parent never changes,
 * so SurfaceView reattach across nav transitions is avoided.
 *
 * SurfaceFlinger's hardware scaler handles the resize for free, so
 * 210x118 mini -> 1920x1080 fullscreen is essentially free pixel-
 * pushing-wise. Same as the MPV side.
 *
 * Why a separate state class (rather than reusing MpvWindowState):
 * during the Live TV migration phase BOTH players are mounted in
 * MainActivity (MPV for any path we haven't ported, Exo for Live TV).
 * Each player owns its own state so flipping one mode doesn't
 * inadvertently move the other's view. Once libmpv is torn out
 * (task #67) MpvWindowState goes away and only this one survives.
 */
@Singleton
class ExoWindowState @Inject constructor() {

    enum class Mode { Hidden, Fullscreen, Mini }

    private val _mode = MutableStateFlow(Mode.Hidden)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    fun requestFullscreen() { _mode.value = Mode.Fullscreen }
    fun requestMini() { _mode.value = Mode.Mini }
    fun hide() { _mode.value = Mode.Hidden }

    /**
     * Live channel-flip hook for the hardware-key path. The fullscreen live
     * PlayerScreen registers this while mounted (cleared on dispose); MainActivity
     * .dispatchKeyEvent invokes it on D-pad UP/DOWN while [mode] == Fullscreen so
     * channel surfing works even when the player controls overlay is on screen
     * (Compose focus traversal otherwise swallows UP/DOWN among the chrome pills).
     * Delta is +1 for UP (next channel) / -1 for DOWN (previous). Returns true if
     * the flip was consumed; PlayerScreen returns false while a menu/sheet is open
     * so UP/DOWN fall through to normal menu navigation there. Mirrors the
     * PipState.onPipDismissed shared-hook pattern.
     */
    @Volatile var onLiveChannelFlip: ((Int) -> Boolean)? = null

    /**
     * Remote Control phase A2: generic player-action hook, same lifecycle
     * as [onLiveChannelFlip] (registered by the fullscreen live
     * PlayerScreen, cleared on dispose). MainActivity dispatches mapped
     * actions that arrive on ACTIVITY-level keys (long Up/Down, media
     * keys) through this so PlayerScreen state (chrome, menus, info card,
     * last-channel zap) can execute them. Returns true when consumed.
     */
    @Volatile var onPlayerRemoteAction:
        ((com.aeriotv.android.core.remote.PlayerRemoteAction) -> Boolean)? = null

    /**
     * Remote Control phase A2: session-scoped last-channel zap memory
     * (`lastChannel` zap-back, a 1-deep stack). [recordTune] is called
     * on every successful live tune with the channel's stable id; it
     * shifts the previous current into [lastChannelId]. In-memory only,
     * deliberately not persisted or synced.
     */
    @Volatile var currentChannelId: String? = null
        private set
    @Volatile var lastChannelId: String? = null
        private set

    fun recordTune(channelId: String) {
        if (channelId == currentChannelId) return
        lastChannelId = currentChannelId
        currentChannelId = channelId
    }
}
