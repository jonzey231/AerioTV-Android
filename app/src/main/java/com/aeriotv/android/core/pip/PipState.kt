package com.aeriotv.android.core.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide PiP state. MainActivity flips [inPictureInPicture] inside its
 * onPictureInPictureModeChanged callback, and any composable currently mounted
 * (player chromes, mini-player) reads it to decide whether to hide controls
 * and switch to a stripped-down PiP-friendly surface.
 *
 * Singleton because PiP is an activity-level concept and Compose currently
 * doesn't expose the mode through a CompositionLocal. Keep this tiny — the
 * only writer is MainActivity.
 */
object PipState {
    val inPictureInPicture = mutableStateOf(false)

    /**
     * True while a player screen is foregrounded showing VIDEO (not audio-only).
     * MainActivity mirrors this into the window's PiP params (setAutoEnterEnabled
     * on API 31+) and uses it in onUserLeaveHint on older versions, so leaving the
     * app while video plays auto-enters Picture-in-Picture.
     */
    val videoPlaybackActive = MutableStateFlow(false)

    /**
     * True while a player screen is in AUDIO-ONLY mode. Leaving the app then must
     * NOT enter PiP; instead a foreground media notification keeps the audio alive
     * on the status bar + lock screen.
     */
    val audioPlaybackActive = MutableStateFlow(false)

    /** Now-playing labels for the background notification used on audio-only leave. */
    @Volatile var nowPlayingTitle: String = "AerioTV"
    @Volatile var nowPlayingSubtitle: String = ""
}

/** Walk a Compose context chain to the host Activity. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Is the device capable of PiP? PiP requires API 26+ and the FEATURE_PICTURE_IN_PICTURE feature. */
fun Context.supportsPip(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}

/**
 * Request that the host activity enter PiP at 16:9. Safe to call on any API
 * level / device — bails silently when PiP isn't supported. Aspect ratio is
 * pinned at 16:9 because all our content is video and the system clamps
 * to ranges around 2.39:1 / 1:2.39 anyway.
 */
fun Activity.enterPip16x9() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
    runCatching {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        enterPictureInPictureMode(params)
    }.onFailure { Log.w("PipState", "enterPictureInPictureMode failed", it) }
}
