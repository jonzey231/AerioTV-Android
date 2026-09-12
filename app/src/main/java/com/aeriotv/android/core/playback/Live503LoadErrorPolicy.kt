package com.aeriotv.android.core.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Live-source load-error policy: ExoPlayer must NOT silently re-GET a
 * Dispatcharr 503 on its own.
 *
 * The default policy retried every 503 up to its own ladder (Nothing Phone
 * session4.txt 01:48:51: four retries, then the holder's in-place reload burned
 * four more) before anything in the app even learned the server had answered.
 * By the time the app could act, 20 s had gone and the only thing left to say
 * was a guess. Returning [C.TIME_UNSET] for a 503 means "do not retry this
 * load": the error surfaces immediately to
 * [AerioExoPlayerHolder]'s load-error handler, which reads the 503 body and
 * decides between waiting out a "Channel is stopping" teardown and walking to
 * the next member stream.
 *
 * Every other error keeps the stock behavior, so transient connect/read blips
 * are still absorbed by Media3 exactly as before.
 */
@UnstableApi
class Live503LoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long {
        if (Dispatcharr503.parse(loadErrorInfo.exception) != null) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
