package com.aeriotv.android.core.data.vod

import android.util.Log
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.VodCategoryEntity
import com.aeriotv.android.core.network.DispatcharrClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fills the Room catalog from a playlist's VOD source.
 *
 * Replaces the in-memory sweep this app used to do on every launch, and with it
 * three constants whose reasoning is worth keeping even though the constants
 * themselves are gone:
 *
 *  - `MAX_EAGER_VOD_PAGES = 10` capped how many pages were fetched up front,
 *    because a full library fetch on a 256MB-heap TV box starved the EPG and
 *    produced the Z Fold 5 field report (90MB heap, guide unresponsive).
 *  - `VOD_TOTAL_CAP = 5000` hard-stopped ingestion so a 17k library could not
 *    OOM the process.
 *  - The presence-probe fetched one page just to decide whether a VOD tab
 *    should exist at all.
 *
 * None of those are needed once rows live on disk instead of in the heap: the
 * memory ceiling is now a ~300-row transaction rather than the whole catalog,
 * the UI reads Flows that are correct after page one, and "does VOD exist" is a
 * COUNT query. The pressure they were defending against is still real, so the
 * write path keeps their discipline: chunked transactions, off-main decode, and
 * incremental publishes.
 */
@Singleton
class VodRefreshPipeline @Inject constructor(
    private val repository: VodCatalogRepository,
    private val dispatcharrClient: DispatcharrClient,
) {

    /** One refresh per playlist at a time; overlapping calls are dropped. */
    private val inFlight = mutableSetOf<String>()
    private val lock = Mutex()

    data class Result(
        val movies: Int = 0,
        val series: Int = 0,
        val pruned: Int = 0,
        val skipped: Boolean = false,
        val error: String? = null,
    )

    /**
     * Refresh one playlist's catalog.
     *
     * [full] runs deletion reconciliation afterwards. A delta refresh never
     * prunes: a partial view of the library must not be mistaken for upstream
     * deletions, which is the bug that would silently empty a user's library
     * on a flaky connection.
     */
    suspend fun refresh(
        playlist: PlaylistEntity,
        effectiveBaseUrl: String,
        full: Boolean = true,
    ): Result {
        val playlistId = playlist.id
        lock.withLock {
            if (!inFlight.add(playlistId)) {
                Log.i(TAG, "refresh skipped: already in flight for this playlist")
                return Result(skipped = true)
            }
        }
        val startedAt = System.currentTimeMillis()
        try {
            // Every bail is logged (the CarPlay cold-car lesson): a silent
            // early return here is indistinguishable from "never ran".
            val apiKey = playlist.apiKey?.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "refresh bail: playlist has no api key (source=${playlist.sourceType})")
                return Result(error = "no api key")
            }

            // Categories first: they carry the library namespacing every item
            // row is tagged with, so they must land before the items do.
            val libraries = refreshCategories(playlistId, effectiveBaseUrl, apiKey, startedAt)

            // Page-walk both types. Each page is upserted as it arrives rather
            // than accumulated, so peak memory is one page (not one library)
            // and the UI's Room Flows are useful from page one - the whole
            // reason the old 5,000-item cap and 10-page eager limit existed.
            var movieCount = 0
            walkPages(
                first = { dispatcharrClient.getVODMoviesFirstPage(effectiveBaseUrl, apiKey) },
                next = { url -> dispatcharrClient.getVODMoviesPage(url, apiKey) },
                nextUrlOf = { it.next },
                resultsOf = { it.results },
            ) { page ->
                val rows = page.map { m ->
                    repository.toEntity(
                        movie = m,
                        playlistId = playlistId,
                        serverBaseUrl = effectiveBaseUrl,
                        library = libraries[m.categoryName.orEmpty()],
                        syncedAt = startedAt,
                        sourceKind = SOURCE_DISPATCHARR,
                    )
                }
                repository.upsertMovies(rows)
                movieCount += rows.size
            }

            var seriesCount = 0
            walkPages(
                first = { dispatcharrClient.getVODSeriesFirstPage(effectiveBaseUrl, apiKey) },
                next = { url -> dispatcharrClient.getVODSeriesPage(url, apiKey) },
                nextUrlOf = { it.next },
                resultsOf = { it.results },
            ) { page ->
                val rows = page.map { sItem ->
                    repository.toEntity(
                        series = sItem,
                        playlistId = playlistId,
                        serverBaseUrl = effectiveBaseUrl,
                        library = libraries[sItem.categoryName.orEmpty()],
                        syncedAt = startedAt,
                        sourceKind = SOURCE_DISPATCHARR,
                    )
                }
                repository.upsertSeries(rows)
                seriesCount += rows.size
            }

            val pruned = if (full) repository.pruneNotSeenSince(playlistId, startedAt) else 0

            Log.i(
                TAG,
                "catalog refresh: $movieCount movies, $seriesCount series, " +
                    "$pruned pruned, ${System.currentTimeMillis() - startedAt}ms",
            )
            return Result(movies = movieCount, series = seriesCount, pruned = pruned)
        } catch (t: Throwable) {
            // Never let a catalog refresh take the app down: the UI still has
            // whatever Room already holds, which is the whole point of caching.
            Log.w(TAG, "catalog refresh failed", t)
            return Result(error = t.message ?: t::class.simpleName.orEmpty())
        } finally {
            lock.withLock { inFlight.remove(playlistId) }
        }
    }

    /**
     * Walk a DRF-paginated endpoint, handing each page to [onPage] as it
     * arrives. Bounded by [MAX_PAGES] purely as a runaway guard: a server that
     * returns a self-referential `next` must not spin forever.
     */
    private suspend fun <P, T> walkPages(
        first: suspend () -> P,
        next: suspend (String) -> P,
        nextUrlOf: (P) -> String?,
        resultsOf: (P) -> List<T>,
        onPage: suspend (List<T>) -> Unit,
    ) {
        var page: P = first()
        var walked = 0
        while (true) {
            onPage(resultsOf(page))
            val nextUrl = nextUrlOf(page) ?: break
            if (++walked >= MAX_PAGES) {
                Log.w(TAG, "page walk hit the $MAX_PAGES page guard; stopping")
                break
            }
            page = next(nextUrl)
        }
    }

    /**
     * Category names carry the "{source} - {library}" namespace for personal
     * media. Returns category name -> derived library so item rows can be
     * tagged in the same pass.
     */
    private suspend fun refreshCategories(
        playlistId: String,
        baseUrl: String,
        apiKey: String,
        syncedAt: Long,
    ): Map<String, MediaLibraryAdapter.Library> {
        val result = mutableMapOf<String, MediaLibraryAdapter.Library>()
        val rows = mutableListOf<VodCategoryEntity>()
        // ONE fetch: the endpoint returns every category with its own
        // category_type, so asking twice would just be the same payload twice.
        val categories = runCatching {
            dispatcharrClient.getVODCategories(baseUrl, apiKey)
        }.getOrElse {
            Log.w(TAG, "category fetch failed", it)
            emptyList()
        }
        for (c in categories) {
            // The managed_source marker rides on the provider relation, which
            // the category endpoint does not expose. Until that plumbing lands
            // with the media-library phase, categories derive as provider
            // catalogs - exactly the stock-server behaviour, and therefore
            // correct against every server that exists today.
            val library = MediaLibraryAdapter.library(
                categoryName = c.name,
                categoryType = c.categoryType,
                playlistId = playlistId,
                marker = null,
            )
            result[c.name] = library
            rows += VodCategoryEntity(
                playlistId = playlistId,
                id = c.id.toString(),
                name = c.name,
                categoryType = c.categoryType,
                enabled = c.enabledOnAnyAccount,
                isPersonalLibrary = library.isPersonal,
                librarySource = library.sourceName,
                libraryName = library.libraryName,
                syncedAt = syncedAt,
            )
        }
        if (rows.isNotEmpty()) repository.upsertCategories(rows)
        return result
    }

    private companion object {
        const val TAG = "VodRefreshPipeline"
        const val SOURCE_DISPATCHARR = "dispatcharr"
        /** Runaway guard only; a real library is far under this. */
        const val MAX_PAGES = 500
    }
}
