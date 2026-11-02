package com.aeriotv.android.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field run 2026-11-02: empty Favorites and Watchlist pushes at format 2 were
 * still read back as old format. The appData folder held a second copy of each
 * name; upload updated only the first, the offer read the last.
 */
class DriveDuplicateFormatTest {
    private data class F(val id: String, val name: String, var body: String, val trashed: Boolean = false)

    /** In-memory Drive appData folder behind OkHttp: list, download, PATCH update. */
    private class FakeDrive(val files: MutableList<F>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            val u = r.url
            val out: String = when {
                r.method == "GET" && u.queryParameter("alt") == "media" ->
                    files.first { it.id == u.pathSegments.last() }.body
                r.method == "GET" -> {
                    val q = u.queryParameter("q").orEmpty()
                    val name = Regex("name = '([^']+)'").find(q)?.groupValues?.get(1)
                    val hits = files.filter { (name == null || it.name == name) && (!q.contains("trashed = false") || !it.trashed) }
                    "{\"files\":[" + hits.joinToString(",") { "{\"id\":\"${it.id}\",\"name\":\"${it.name}\"}" } + "]}"
                }
                r.method == "PATCH" -> {
                    val b = Buffer().also { r.body!!.writeTo(it) }.readUtf8()
                    files.first { it.id == u.pathSegments.last() }.body = b; "{}"
                }
                else -> error("unexpected ${r.method}")
            }
            return Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(out.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val old = """{"envelope":{"version":1,"lastModified":1,"device":"old"},"entries":[]}"""

    @Test fun emptyPushesLeaveNoOldFormatCopyForTheOffer() = runBlocking {
        val drive = FakeDrive(mutableListOf(
            F("f1", SyncCategory.Favorites.fileName, old), F("f2", SyncCategory.Favorites.fileName, old),
            F("w1", SyncCategory.Watchlist.fileName, old), F("w2", SyncCategory.Watchlist.fileName, old),
            F("t1", SyncCategory.Reminders.fileName, old, trashed = true),
        ))
        val client = DriveAppDataClient(OkHttpClient.Builder().addInterceptor(drive).build())
        val env = DriveCanon.writerEnvelope(2L, "t", null)
        assertTrue(client.upload("x", SyncCategory.Favorites.fileName, json.encodeToString(FavoritesSnapshot(env, emptyList()))).isSuccess)
        assertTrue(client.upload("x", SyncCategory.Watchlist.fileName, json.encodeToString(WatchlistSnapshot(env, emptyList()))).isSuccess)

        // The offer's read (DriveSyncManager.backupOutdated): listAll, last id per name wins.
        val ids = client.listAll("x").associate { (id, name) -> name to id }
        val formats = listOf(SyncCategory.Favorites, SyncCategory.Watchlist, SyncCategory.Reminders)
            .mapNotNull { c -> ids[c.fileName]?.let { client.download("x", it) } }
            .map { DriveCanon.envelopeFormat(it) }
        assertFalse("offer fired on files this build wrote: $formats", DriveCanon.backupOutdated(formats))
    }
}
