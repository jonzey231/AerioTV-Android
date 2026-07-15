package com.aeriotv.android.core.cast

import android.content.Context
import com.aeriotv.android.BuildConfig
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Sender-side Cast configuration (GH #33). The Cast framework instantiates this
 * (named by the OPTIONS_PROVIDER_CLASS_NAME manifest meta-data) the first time
 * CastContext.getSharedInstance() runs, to learn which receiver App ID to launch
 * and how.
 *
 * setAndroidReceiverCompatible(true) is the flag that makes Cast Connect work:
 * without it the framework launches the Cast WEB receiver even when the target
 * is a Chromecast-with-Google-TV, which cannot play AerioTV's raw MPEG-TS. With
 * it, when the target is an Android TV device that has AerioTV installed, the
 * framework launches the AerioTV Android-TV app as the receiver so playback runs
 * through the app's own ExoPlayer (raw TS + ffmpeg AC-3).
 *
 * The receiver App ID comes from BuildConfig.CAST_RECEIVER_APP_ID, which is
 * empty until the owner registers a Cast App ID in the Cast Developer Console
 * and associates this package. CastContext is only ever warmed when that id is
 * non-blank (see MainActivity), so getCastOptions() is not reached with an empty
 * id in practice; the DEFAULT_MEDIA_RECEIVER fallback below is defence in depth
 * so a stray CastContext request can never crash on an invalid application id.
 */
class AerioCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val receiverAppId = BuildConfig.CAST_RECEIVER_APP_ID.ifBlank {
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        }

        val launchOptions = LaunchOptions.Builder()
            // Launch the AerioTV Android-TV app as the receiver (Cast Connect)
            // rather than the web receiver when the target supports it.
            .setAndroidReceiverCompatible(true)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(receiverAppId)
            .setLaunchOptions(launchOptions)
            // The framework's own notification/lock-screen media UI is redundant
            // with AerioTV's MediaSession-driven controls; leave the defaults.
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
