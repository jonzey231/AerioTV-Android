package com.aeriotv.android.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that attaches the active playlist's Dispatcharr API
 * key to every Coil image fetch whose URL belongs to the same Dispatcharr
 * server (audit task #54).
 *
 * Dispatcharr's logo endpoint (`/api/channels/logos/<id>/cache/`) and a
 * handful of VOD-poster endpoints sit behind the same auth-gate as the
 * JSON API: a request without `X-API-Key` returns 401 and Coil renders
 * the broken-image placeholder. DispatcharrClient already attaches the
 * header for its own JSON calls; this interceptor extends that to Coil's
 * dedicated OkHttp client.
 *
 * Header strategy mirrors DispatcharrClient:
 *   - `X-API-Key: <key>` — preferred header name.
 *   - `Authorization: ApiKey <key>` — fallback some Dispatcharr deployments
 *     accept. Sending both is harmless and matches the auth header pair
 *     the rest of the app uses.
 *
 * Scope: the interceptor matches the URL against
 * [ActivePlaylistCredentials.apiKeyFor], which returns the key only when
 * the URL starts with one of the registered prefixes (the canonical
 * urlString + optional lanUrl). Third-party tvg-logo CDNs are untouched.
 */
class DispatcharrImageAuthInterceptor(
    private val credentials: ActivePlaylistCredentials,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // If the caller already supplied an X-API-Key (e.g. a future code
        // path that adds it explicitly), don't double up.
        if (req.header(HEADER_X_API_KEY) != null) return chain.proceed(req)
        val key = credentials.apiKeyFor(req.url.toString()) ?: return chain.proceed(req)
        val authed = req.newBuilder()
            .addHeader(HEADER_X_API_KEY, key)
            .addHeader(HEADER_AUTHORIZATION, "ApiKey $key")
            .build()
        return chain.proceed(authed)
    }

    private companion object {
        const val HEADER_X_API_KEY = "X-API-Key"
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}
