package com.aeriotv.android.core.update

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fetches the latest GitHub release for the updater (github flavor only).
 *
 * Unauthenticated GET against the public repo. The API allows 60
 * requests/hour/IP and a cached 304 still counts against that quota, so the
 * caller's 12h auto-check throttle is the quota control (no ETag plumbing;
 * the response is ~10KB).
 *
 * Asset selection FAILS CLOSED: exactly one fully-uploaded .apk asset must
 * exist, otherwise the release is treated as not-ready (covers the window
 * where the tag exists but the APK is still uploading, and any future
 * multi-APK mistake). Release-process rule: draft the release, attach the
 * APK, then publish.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    sealed interface Outcome {
        /** A newer release with exactly one uploaded APK asset. */
        data class UpdateAvailable(val info: UpdateInfo) : Outcome

        /** Remote latest is not newer than the running version. */
        data object UpToDate : Outcome

        /** Tag is newer but no installable asset yet; retry later, do not
         *  stamp lastCheckedAt. */
        data object NotReady : Outcome

        /** Rate-limited or transient failure; silent on automatic checks. */
        data class Failed(val message: String) : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 15_000
            }
        }
    }

    suspend fun fetchLatest(localVersionName: String): Outcome {
        val response = try {
            client.get(LATEST_RELEASE_URL) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
        } catch (t: Throwable) {
            return Outcome.Failed(t.message ?: "network error")
        }
        if (response.status == HttpStatusCode.Forbidden ||
            response.status.value == 429
        ) {
            return Outcome.Failed("rate limited")
        }
        if (response.status != HttpStatusCode.OK) {
            return Outcome.Failed("HTTP ${response.status.value}")
        }
        val release: GithubRelease = try {
            response.body()
        } catch (t: Throwable) {
            return Outcome.Failed("bad response: ${t.message}")
        }
        if (release.prerelease) return Outcome.UpToDate
        val remote = release.tagName.removePrefix("v").removePrefix("V")
        if (!isNewer(remote, localVersionName)) return Outcome.UpToDate

        val apks = release.assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                it.state == "uploaded" &&
                it.size > 0
        }
        if (apks.size != 1) return Outcome.NotReady
        val apk = apks.single()
        if (!apk.browserDownloadUrl.startsWith("https://")) {
            return Outcome.Failed("non-https asset URL")
        }
        return Outcome.UpdateAvailable(
            UpdateInfo(
                versionName = remote,
                notes = release.body.orEmpty().trim(),
                apkUrl = apk.browserDownloadUrl,
                apkSizeBytes = apk.size,
            ),
        )
    }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val prerelease: Boolean = false,
        val assets: List<GithubAsset> = emptyList(),
    )

    @Serializable
    private data class GithubAsset(
        val name: String,
        val size: Long = 0L,
        val state: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    )

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/jonzey231/AerioTV-Android/releases/latest"

        /**
         * Semver-ish compare for our vX.Y.Z tags. Numeric triple compare; a
         * bare version outranks the same triple with a suffix (0.3.0-beta1 <
         * 0.3.0). Advisory only: the authoritative gate is the downloaded
         * APK's versionCode in ApkVerifier.
         */
        fun isNewer(remote: String, local: String): Boolean {
            fun split(v: String): Pair<List<Int>, String> {
                val base = v.substringBefore('-')
                val suffix = v.substringAfter('-', "")
                val nums = base.split('.').map { it.toIntOrNull() ?: 0 }
                return (nums + List((3 - nums.size).coerceAtLeast(0)) { 0 }).take(3) to suffix
            }
            val (r, rSuf) = split(remote)
            val (l, lSuf) = split(local)
            for (i in 0..2) {
                if (r[i] != l[i]) return r[i] > l[i]
            }
            // Same triple: only "remote bare vs local suffixed" counts as newer.
            return rSuf.isEmpty() && lSuf.isNotEmpty()
        }
    }
}
