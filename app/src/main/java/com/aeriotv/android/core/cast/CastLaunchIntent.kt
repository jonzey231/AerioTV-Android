package com.aeriotv.android.core.cast

/**
 * Which receiver a Cast launch is for (owner rule 2026-11-14: a basic cast
 * must not need the app). A pick under "Google Cast" is a plain cast to the
 * web receiver through the phone proxy, even on a TV that has AerioTV; only
 * the "AerioTV on TV" entry launches the AerioTV Android TV app (Cast
 * Connect). The Cast SDK launches the TV app whenever it is installed
 * (LaunchOptions androidReceiverCompatible, set once at startup), so the
 * sender names its intent in the launch credentials and the TV app's launch
 * request checker declines a plain cast; the Cast framework then loads the
 * web receiver instead. Nothing secret rides here: no key, no sign-in.
 */
enum class CastLaunchIntent(val wire: String) {
    /** A plain cast to the web receiver (the phone proxy path). */
    WebReceiver("aeriotv-launch:web"),

    /** The AerioTV Android TV app, picked from "AerioTV on TV". */
    TvApp("aeriotv-launch:app"),
    ;

    companion object {
        fun of(wire: String?): CastLaunchIntent? = entries.firstOrNull { it.wire == wire?.trim() }

        /**
         * The TV app's answer to a launch request: it declines only an
         * explicit plain cast. A launch with no intent (an older sender, or
         * another platform's sender before it names one) launches the app as
         * before.
         */
        fun tvAppAccepts(credentials: String?): Boolean = of(credentials) != WebReceiver

        /**
         * The intent once a session ends: a device switch that is waiting to
         * start keeps the intent of the entry it was picked from; otherwise the
         * next launch is a plain cast unless "AerioTV on TV" is picked again.
         */
        fun afterSessionEnd(pendingSwitch: CastLaunchIntent?): CastLaunchIntent = pendingSwitch ?: WebReceiver
    }
}
