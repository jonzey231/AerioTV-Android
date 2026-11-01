package com.aeriotv.android.core.sync

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Two-step Google account sign-in for Drive Sync.
 *
 *   1. [signIn] uses Credential Manager with GetSignInWithGoogleOption, the
 *      modern "Sign in with Google" branded sheet that picks one of the
 *      accounts already on the device. Returns the user's email + GoogleId
 *      token so the UI can render "Signed in as you@example.com".
 *
 *   2. [requestDriveAuthorization] uses Identity.AuthorizationClient to add
 *      the Drive AppData scope to that identity, returning a bearer access
 *      token DriveAppDataClient sends as Authorization: Bearer.
 *
 * Both pieces require BuildConfig.GOOGLE_DRIVE_WEB_CLIENT_ID to be set, the
 * Web Client ID issued for your Google Cloud project. The signing-cert SHA-1
 * for the running build must be registered with an Android Client ID in the
 * same project, or both steps reject with API_NOT_CONNECTED.
 */
class GoogleDriveAuth(private val context: Context) {

    /**
     * Show the Sign-in-with-Google sheet. Returns [SignInResult] with the
     * Google email + idToken on success, null on user cancel, throws on
     * misconfiguration. Caller is responsible for surfacing the failure.
     */
    suspend fun signIn(activity: Activity): SignInResult? {
        if (!SyncConfig.isConfigured()) {
            Log.w(TAG, "WEB_CLIENT_ID is blank, set local.properties.GOOGLE_DRIVE_WEB_CLIENT_ID first")
            throw IllegalStateException("Drive Sync OAuth client id is not configured.")
        }
        val credentialManager = CredentialManager.create(context)
        val option = GetSignInWithGoogleOption.Builder(SyncConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return runCatching {
            val response = credentialManager.getCredential(activity, request)
            val cred = response.credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Log.w(TAG, "Unexpected credential type ${cred::class.java.simpleName}")
                return@runCatching null
            }
            val google = try {
                GoogleIdTokenCredential.createFrom(cred.data)
            } catch (e: GoogleIdTokenParsingException) {
                Log.w(TAG, "GoogleId token parse failed", e)
                return@runCatching null
            }
            SignInResult(
                email = google.id,
                displayName = google.displayName.orEmpty(),
                idToken = google.idToken,
            )
        }.onFailure { Log.w(TAG, "signIn failed", it) }
            .getOrNull()
    }

    /**
     * Request Drive AppData scope authorization. If the user hasn't granted
     * the scope yet, [AuthorizationResult.hasResolution] returns true and the
     * caller must launch [AuthorizationResult.pendingIntent] via a regular
     * activity result contract, then call [extractAccessToken] with the
     * returned intent.
     *
     * Returns null when [SyncConfig.isConfigured] is false.
     */
    suspend fun requestDriveAuthorization(): AuthorizationResult? {
        if (!SyncConfig.isConfigured()) return null
        val client = Identity.getAuthorizationClient(context)
        // No requestOfflineAccess: AerioTV uses the access token client-side
        // (there's no backend to exchange a server auth code for), and forcing
        // an auth code makes authorize() re-prompt for consent on every call,
        // which defeats silent re-authorization after an app restart. Without
        // it, authorize() returns a cached access token with NO UI once the
        // user has granted the Drive AppData scope -- the basis for
        // [DriveSyncManager.ensureSignedIn].
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SyncConfig.DRIVE_APPDATA_SCOPE)))
            .build()
        return suspendCancellableCoroutine { cont ->
            client.authorize(request)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { t ->
                    Log.w(TAG, "authorize() failed", t)
                    if (cont.isActive) cont.resumeWithException(t)
                }
        }
    }

    /**
     * Drop [token] from Google Play services' token cache. authorize() hands
     * back its cached access token without checking it, so after Drive
     * rejects one (401) the cache must be cleared first or the "refresh"
     * returns the same rejected token.
     */
    suspend fun clearToken(token: String) {
        val client = Identity.getAuthorizationClient(context)
        val req = com.google.android.gms.auth.api.identity.ClearTokenRequest.builder().setToken(token).build()
        suspendCancellableCoroutine<Unit> { cont ->
            client.clearToken(req)
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { t ->
                    Log.w(TAG, "clearToken failed: ${t.javaClass.simpleName}")
                    if (cont.isActive) cont.resume(Unit)
                }
        }
    }

    /** Parse the activity result from the Drive scope consent flow. */
    fun extractAccessToken(data: android.content.Intent?): String? {
        if (data == null) return null
        return runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
        }.onFailure { Log.w(TAG, "extractAccessToken failed", it) }
            .getOrNull()
    }

    /** Sign-out: clear the cached Credential Manager state. */
    suspend fun signOut() {
        runCatching {
            CredentialManager.create(context).clearCredentialState(
                androidx.credentials.ClearCredentialStateRequest(),
            )
        }.onFailure { Log.w(TAG, "clearCredentialState failed", it) }
    }

    data class SignInResult(
        val email: String,
        val displayName: String,
        val idToken: String,
    )

    companion object { private const val TAG = "GoogleDriveAuth" }
}
