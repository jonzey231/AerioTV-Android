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
}
