package com.aeriotv.android.feature.miniplayer

import com.aeriotv.android.core.data.M3UChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state holder for the mini-player overlay anchored above the
 * bottom nav. Mirrors iOS NowPlayingManager's surfaced channel handle, but
 * scoped down: v1 ships as a tap-to-resume affordance only — no in-row
 * live video render, no audio continuation across the boundary. The user
 * still sees what they were last watching and can re-enter the player
 * with one tap.
 *
 * State machine:
 *   Hidden  ◀──────────── system back from PlayerScreen
 *      │                  (or explicit Close button)
 *      │
 *      ▼  channel tap
 *   Active(channel)  ◀── system back while playing
 *      │
 *      ▼  tap row
 *   Hidden + nav PLAYER(channel)
 *
 * Why a Hilt @Singleton and not a ViewModel: the session has to outlive
 * every screen-scoped ViewModel and survive nav-stack pops. ViewModels
 * scoped to NavBackStackEntry would lose state every time the user exits
 * the player and the back stack collapses.
 */
@Singleton
class MiniPlayerSession @Inject constructor() {

    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Hidden)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * One-shot resume events. MainActivity.onKeyLongPress emits here when the
     * user HOLDS BACK on Android TV while the mini-player is Active (Google TV
     * remotes have no play/pause key, and a short Back dismisses the mini). The
     * Navigation root collects this and re-pushes the PLAYER route with the
     * active channel. Buffer is 1 + DROP_OLDEST so a resume fired during a nav
     * transition isn't lost.
     */
    private val _resumeRequests = MutableSharedFlow<M3UChannel>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resumeRequests: SharedFlow<M3UChannel> = _resumeRequests.asSharedFlow()

    /**
     * One-shot "jump the guide to the top channel" events, emitted when the
     * user DOUBLE-presses Back while the mini-player is Active (tvOS parity:
     * NowPlayingManager's double-Menu -> .guideScrollToTop). The mini stays
     * Active/playing; only the guide's focus + scroll move. GuideScreen
     * collects this. Buffer 1 + DROP_OLDEST so it survives a compose churn.
     */
    private val _guideTopRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val guideTopRequests: SharedFlow<Unit> = _guideTopRequests.asSharedFlow()

    /** Double-Back on the mini (tvOS): ask the guide to jump to the top
     *  channel WITHOUT touching playback or the mini's Active state. */
    fun requestGuideTop() {
        _guideTopRequests.tryEmit(Unit)
    }

    /**
     * GH #15: one-shot handoff from PlayerScreen's resume-time self-pop to
     * the guide. HOME on TV stops playback and hides the player window
     * (MainActivity.onStop); on return PlayerScreen resumes onto that Hidden
     * window and pops itself, landing on a fresh guide composition with the
     * mini already dismissed -- so the mini-keyed focus-on-return effect
     * bails and NOTHING is focused. Chromecast/Google TV drops Compose focus
     * across the stop/restart (Shield restores it), leaving every D-pad key
     * -- including hold-Left-to-All -- dead.
     *
     * Not a SharedFlow like the requests above: at emit time the guide is
     * NOT composed yet (it composes only after the pop), and a replay-0
     * SharedFlow drops events with no subscriber. A timestamped consume-once
     * slot survives that gap; the age cap discards it if the pop lands
     * somewhere that never consumes (List view, phone).
     */
    @Volatile
    private var guideFocusRestoreAtMs: Long? = null

    /** Called by PlayerScreen right before its hidden-window self-pop. */
    fun requestGuideFocusRestore() {
        guideFocusRestoreAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** True exactly once per request, and only while the request is fresh. */
    fun consumeGuideFocusRestore(maxAgeMs: Long = 5_000L): Boolean {
        val at = guideFocusRestoreAtMs ?: return false
        guideFocusRestoreAtMs = null
        return android.os.SystemClock.elapsedRealtime() - at <= maxAgeMs
    }

    /**
     * Called from MainActivity.onKeyLongPress when BACK is held and the
     * mini-player is Active. Promotes Active -> Pending (so the mini overlay
     * vanishes) and emits a resume event for the NavController to navigate on.
     * No-op when the state isn't Active.
     */
    fun requestResume() {
        val current = _state.value
        val ch = (current as? State.Active)?.channel
        if (ch == null) {
            android.util.Log.w(
                "MiniPlayerResume",
                "requestResume called but state=$current (expected Active); no-op",
            )
            return
        }
        android.util.Log.i(
            "MiniPlayerResume",
            "Active->Pending + emit channel id=${ch.id} name=${ch.name} urlBlank=${ch.url.isBlank()}",
        )
        _state.value = State.Pending(ch)
        val emitted = _resumeRequests.tryEmit(ch)
        android.util.Log.i("MiniPlayerResume", "tryEmit returned $emitted")
    }

    /** Called when PlayerScreen mounts (or flips channel). Holding fullscreen
     * is represented by [State.Pending] so the mini-player UI doesn't render
     * underneath the player; a system back then promotes Pending → Active. */
    fun setCurrentChannel(channel: M3UChannel) {
        _state.value = State.Pending(channel)
    }

    /** System back inside PlayerScreen — promote pending channel into the
     * active mini-player surface. */
    fun showMiniPlayer() {
        val pending = (_state.value as? State.Pending)?.channel
            ?: (_state.value as? State.Active)?.channel
            ?: return
        _state.value = State.Active(pending)
    }

    /** User dismissed the mini-player or hit explicit Close in fullscreen. */
    fun dismiss() {
        _state.value = State.Hidden
    }

    /** User tapped the mini-player to resume; caller navigates back into PlayerScreen. */
    fun resumeChannel(): M3UChannel? {
        val channel = (_state.value as? State.Active)?.channel
        if (channel != null) _state.value = State.Pending(channel)
        return channel
    }

    sealed interface State {
        /** No mini-player; no playback context. */
        data object Hidden : State

        /** Player is currently fullscreen; mini-player UI hidden but channel remembered. */
        data class Pending(val channel: M3UChannel) : State

        /** Mini-player row visible above the bottom nav. */
        data class Active(val channel: M3UChannel) : State
    }
}
