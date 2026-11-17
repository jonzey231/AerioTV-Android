package com.aeriotv.android.core.network

import android.util.Log
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.debug.DebugLogger
import com.aeriotv.android.core.debug.LogSanitizer
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging

/**
 * Install Ktor request/response logging on a [HttpClient], mirroring iOS's
 * NWHTTP console stream. Logs at INFO (method + URL + status + timing -- no
 * headers or bodies, so no large-body spam). A line is only emitted on debug
 * builds OR when the user turns on Settings -> Developer -> Enable Debug
 * Logging; otherwise the logger discards it. That makes a release build
 * diagnosable for "no EPG / slow load" style reports without a debug sideload,
 * while staying silent (and cheap) by default. Every emitted line still runs
 * through [LogSanitizer], so an Xtream query credential or an api_key sitting
 * in a URL can never reach the shareable log file. Read with
 * `adb logcat -s AerioNet`.
 */
fun HttpClientConfig<*>.installSanitizedLogging() {
    install(Logging) {
        // INFO unconditionally so the plugin is live on release too; whether a
        // given line is actually written is gated per-message below.
        level = LogLevel.INFO
        logger = object : Logger {
            override fun log(message: String) {
                if (BuildConfig.DEBUG || DebugLogger.isLoggingEnabled()) {
                    Log.d("AerioNet", LogSanitizer.maskUrlsIn(LogSanitizer.redact(message)))
                }
            }
        }
    }
}
