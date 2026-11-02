package com.aeriotv.android.core.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Minimal Drive v3 REST client scoped to the appDataFolder. Built on OkHttp
 * because the project already pulls it; the official google-api-services-drive
 * artifact would multiply the APK size for what amounts to four endpoints we
 * actually use.
 *
 * Auth: caller supplies the OAuth access token per call. A 401 never reads
 * as "missing" or "empty": it throws [DriveAuthException], and with an
 * [authHandler] every call refreshes the token once and retries
 * ([DriveAuthRetry]); a second 401 or a failed refresh marks Drive as
 * needing sign-in, after which no call goes out until the user signs in.
 *
 * Each "file" stored here is the JSON snapshot for a single SyncCategory. We
 * list to discover the file id, then PATCH to update or POST a multipart for
 * the first upload. AppData files never appear in the user's main Drive UI.
 */
class DriveAppDataClient(
    private val okHttp: OkHttpClient,
    /** Token refresh and the needs-sign-in gate (DriveSyncManager). Null: no retry, a 401 throws. */
    private val authHandler: DriveAuthHandler? = null,
    private val driveBaseUrl: String = "https://www.googleapis.com/drive/v3/files",
    private val uploadBaseUrl: String = "https://www.googleapis.com/upload/drive/v3/files",
) {

    private val driveBase = driveBaseUrl.toHttpUrl()
    private val uploadBase = uploadBaseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Look up the file id for [fileName] in the appDataFolder. Returns null
     * only when Drive answered and the file doesn't exist yet (first upload
     * path); any failed answer throws, so a 401 never leads to a create.
     */
    suspend fun findFileId(token: String, fileName: String): String? = withContext(Dispatchers.IO) {
        val url = driveBase.newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("q", "name = '$fileName' and trashed = false")
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        authed(token) { t ->
            okHttp.newCall(req.withToken(t)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Codes only: the body can echo request details.
                    warn("findFileId $fileName -> HTTP ${resp.code}")
                    throw httpError(resp.code, "findFileId $fileName")
                }
                val body = resp.body?.string().orEmpty()
                val tree = json.parseToJsonElement(body).jsonObject
                tree["files"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
            }
        }
    }

    /** Read a JSON file as a string, or null when missing/unreadable. A 401 throws. */
    suspend fun download(token: String, fileId: String): String? = withContext(Dispatchers.IO) {
        val url = driveBase.newBuilder()
            .addPathSegment(fileId)
            .addQueryParameter("alt", "media")
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        authed(token) { t ->
            runCatching {
                okHttp.newCall(req.withToken(t)).execute().use { resp ->
                    if (resp.code == 401) throw DriveAuthException("download")
                    if (!resp.isSuccessful) {
                        warn("download $fileId -> HTTP ${resp.code}")
                        null
                    } else {
                        resp.body?.string()
                    }
                }
            }.onFailure { if (it is DriveAuthException) throw it }.getOrNull()
        }
    }

    /**
     * Create-or-update [fileName] with [contentJson]. Single round-trip uses
     * Drive's multipart upload when creating; PATCH /upload for updates.
     */
    suspend fun upload(token: String, fileName: String, contentJson: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            authed(token) { t ->
                // Every copy of the name, not only the first: readers that list
                // the folder (backup update offer, setup probe) may land on any
                // duplicate (two devices creating the same file at once), and
                // an untouched duplicate kept the old format forever.
                val existing = findFileIdsRaw(t, fileName)
                if (existing.isEmpty()) {
                    createMultipart(t, fileName, contentJson)
                } else {
                    if (existing.size > 1) info("upload $fileName copies=${existing.size}")
                    existing.forEach { updateContent(t, it, contentJson) }
                    existing.first()
                }
            }
        }.onFailure { warn("upload $fileName FAILED: ${it.javaClass.simpleName} ${it.message}") }
            .onSuccess { info("upload $fileName OK -> id=$it") }
    }

    private fun createMultipart(token: String, fileName: String, contentJson: String): String {
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(fileName))
            put("parents", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("appDataFolder"))))
        }
        // Drive's multipart/related upload: part 1 = metadata JSON, part 2 =
        // the file content. The per-part Content-Type MUST ride on the body's
        // MediaType -- OkHttp rejects a "Content-Type" entry in the Part
        // headers with IllegalArgumentException("Unexpected header: Content-Type").
        val jsonMedia = "application/json; charset=UTF-8".toMediaType()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(jsonMedia))
            .addPart(contentJson.toRequestBody(jsonMedia))
            .build()
        val req = Request.Builder()
            .url(uploadBase.newBuilder().addQueryParameter("uploadType", "multipart").build())
            .post(multipart)
            .header("Authorization", "Bearer $token")
            .build()
        okHttp.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError(resp.code, "create $fileName")
            val obj = json.parseToJsonElement(body).jsonObject
            return obj["id"]?.jsonPrimitive?.content
                ?: error("create $fileName: missing id in response")
        }
    }

    private fun updateContent(token: String, fileId: String, contentJson: String) {
        val req = Request.Builder()
            .url(uploadBase.newBuilder().addPathSegment(fileId).addQueryParameter("uploadType", "media").build())
            .patch(contentJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw httpError(resp.code, "update $fileId")
        }
    }

    /** Delete a file by id. Idempotent, a 404 is treated as success. */
    suspend fun delete(token: String, fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            authed(token) { t ->
                val req = Request.Builder()
                    .url(driveBase.newBuilder().addPathSegment(fileId).build())
                    .delete()
                    .header("Authorization", "Bearer $t")
                    .build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 404) throw httpError(resp.code, "delete $fileId")
                }
            }
        }
    }

    /**
     * List all AppData file metadata (id + name). Throws when Drive did not
     * answer with a listing (401, any HTTP error, network): an unreadable
     * Drive is never an empty Drive (setup probe, section 10a).
     */
    suspend fun listAll(token: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val url = driveBase.newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            // Same filter as findFileId: a trashed copy is never read as the backup.
            .addQueryParameter("q", "trashed = false")
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        authed(token) { t ->
            okHttp.newCall(req.withToken(t)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    warn("listAll -> HTTP ${resp.code}")
                    throw httpError(resp.code, "listAll")
                }
                val body = resp.body?.string().orEmpty()
                val tree = json.parseToJsonElement(body).jsonObject
                tree["files"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    id to name
                }
            }
        }
    }

    /** [findFileId] without its own retry, for use inside an [authed] block. */
    private fun findFileIdRaw(token: String, fileName: String): String? = findFileIdsRaw(token, fileName).firstOrNull()

    /** Every non-trashed file id named [fileName], in Drive's order. */
    private fun findFileIdsRaw(token: String, fileName: String): List<String> {
        val url = driveBase.newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("q", "name = '$fileName' and trashed = false")
            .build()
        val req = Request.Builder().url(url).get().header("Authorization", "Bearer $token").build()
        return okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                warn("findFileId $fileName -> HTTP ${resp.code}")
                throw httpError(resp.code, "findFileId $fileName")
            }
            val tree = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            tree["files"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
        }
    }

    private fun Request.withToken(t: String): Request = newBuilder().header("Authorization", "Bearer $t").build()

    private fun httpError(code: Int, what: String): Exception =
        if (code == 401) DriveAuthException(what) else DriveHttpException(code, what)

    private suspend fun <T> authed(token: String, call: suspend (String) -> T): T {
        val h = authHandler ?: return call(token)
        return DriveAuthRetry.run(h, token, call)
    }

    // Wrapped so the JVM unit tests (no android.util.Log) run the same code.
    private fun warn(msg: String) { runCatching { Log.w(TAG, msg) } }
    private fun info(msg: String) { runCatching { Log.i(TAG, msg) } }

    companion object {
        private const val TAG = "DriveAppDataClient"
    }
}
