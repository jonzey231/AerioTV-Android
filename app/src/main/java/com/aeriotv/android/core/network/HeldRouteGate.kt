package com.aeriotv.android.core.network

/**
 * While a profile is held after its playlist's server address changed (item
 * 8), no request of any kind goes to the held addresses (2026-11-11, Apple
 * parity: its gate refuses every request there). The Dispatcharr API calls
 * ([heldRouteRefusal]) and the streams (StreamGate) are refused at their own
 * gates; this covers everything else that can reach those addresses: logos
 * and artwork, guide and playlist downloads, the on-demand redirect read, the
 * Xtream Codes bridge, and the LAN reachability probe. A refused logo shows
 * the existing placeholder.
 */
object HeldRouteGate {
    /** A request to a held address, refused before it went out. */
    class Refused : java.io.IOException(com.aeriotv.android.feature.settings.DISPATCHARR_UNREACHABLE)

    /** [url] is on an address the active profile is held at. */
    fun refuses(url: String): Boolean = com.aeriotv.android.core.profiles.ProfileScope.isSealedRoute(url)

    /**
     * OkHttp: throws [Refused] before a request to a held address goes out.
     * A deleted playlist's request is refused too, and its answer dropped
     * when it arrives after the delete ([PlaylistRevocation]).
     */
    val interceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        if (refuses(url)) throw Refused()
        val key = requestApiKey(request.header("X-API-Key"), request.header("Authorization"))
        PlaylistRevocation.check(url, key)
        val response = chain.proceed(request)
        PlaylistRevocation.refusal(url, key)?.let { id ->
            response.close()
            throw PlaylistRevocation.Refused(id)
        }
        response
    }
}
