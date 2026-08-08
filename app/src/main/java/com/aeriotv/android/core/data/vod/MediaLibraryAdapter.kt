package com.aeriotv.android.core.data.vod

/**
 * EVERY assumption about Dispatcharr's personal-media-library support lives in
 * this one file, so server-side drift shows up here instead of as scattered
 * breakage across the Movies & TV UI. Mirrors the Apple
 * DispatcharrMediaLibraryAdapter one-for-one.
 *
 * What the server actually provides: imported personal media is written into
 * the SAME /api/vod/ models a provider catalog uses. There is no content API of
 * its own, no library endpoint a normal user may read, no seasons model, no
 * cast, no server-side watch state. Two things distinguish personal content:
 *
 *  1. the provider relation carries `custom_properties.managed_source ==
 *     "media_server"` (plus provider / integration_name / provider_library),
 *  2. its category is namespaced "{source name} - {library name}".
 *
 * Neither exists on a stock server and both are additive JSON, so absence is
 * the normal case and simply reads as a provider catalog.
 *
 * House rule: `provider` is recorded for diagnostics only. Third-party media
 * server product names must never reach user-facing copy; the UI says "media
 * server" or uses the user's own source name.
 */
object MediaLibraryAdapter {

    const val MANAGED_SOURCE_MARKER = "media_server"

    /** The delimiter the import layer writes between source and library. */
    private const val DELIMITER = " - "

    /**
     * The marker fields this app reads off a provider relation. All nullable:
     * old servers omit the whole object, and a tolerant shape is what keeps
     * deserialization working against BOTH server versions.
     */
    data class ProviderMarker(
        val managedSource: String? = null,
        val provider: String? = null,
        val integrationId: Int? = null,
        val integrationName: String? = null,
        val providerLibrary: String? = null,
    ) {
        val isPersonalLibrary: Boolean get() = managedSource == MANAGED_SOURCE_MARKER
    }

    data class Library(
        val key: String,
        val sourceName: String,
        val libraryName: String,
        val mediaType: String,
        val isPersonal: Boolean,
    ) {
        /** Row header / scope chip label. */
        val displayName: String
            get() = if (sourceName.isEmpty()) libraryName else "$sourceName: $libraryName"
    }

    fun isPersonalLibrary(marker: ProviderMarker?): Boolean = marker?.isPersonalLibrary == true

    /**
     * Split "{source} - {library}" on the FIRST delimiter only: library names
     * routinely contain a dash ("Kids - Bedtime"), source names much less so,
     * and the import writes source first. A provider catalog's category is
     * never split, so "Action - Thriller" survives intact.
     */
    fun library(
        categoryName: String,
        categoryType: String,
        playlistId: String,
        marker: ProviderMarker? = null,
    ): Library {
        val key = "$playlistId|$categoryName"
        val personal = isPersonalLibrary(marker)
        if (!personal) {
            return Library(key, "", categoryName, categoryType, false)
        }
        val idx = categoryName.indexOf(DELIMITER)
        if (idx < 0) {
            return Library(key, marker?.integrationName.orEmpty(), categoryName, categoryType, true)
        }
        val parsedSource = categoryName.substring(0, idx).trim()
        val libraryName = categoryName.substring(idx + DELIMITER.length).trim()
        // Prefer the relation's own integration_name when present: it is the
        // user's actual source name, unmangled by any delimiter the library
        // name may also contain.
        val source = marker?.integrationName?.takeIf { it.isNotBlank() } ?: parsedSource
        return Library(key, source, libraryName, categoryType, true)
    }

    /**
     * Poster URL policy. THE most dangerous field in the integration: for
     * media-library items `logo.url` is a path on the SERVER'S FILESYSTEM
     * (e.g. /data/media/Movies/Dune/poster.jpg), which is useless to a client
     * and must never be handed to an image loader. `cache_url`
     * (/api/vod/vodlogos/{id}/cache/, AllowAny) is correct for those and works
     * fine for ordinary provider VOD too.
     *
     * Resolved ONCE at ingest and stored in Room, so the UI never touches logo
     * objects and cannot reintroduce the mistake.
     */
    fun posterUrl(cacheUrl: String?, url: String?, serverBaseUrl: String?): String {
        if (!cacheUrl.isNullOrBlank()) {
            if (cacheUrl.startsWith("http://", true) || cacheUrl.startsWith("https://", true)) {
                return cacheUrl
            }
            val base = serverBaseUrl?.trimEnd('/')
            if (!base.isNullOrBlank()) return base + "/" + cacheUrl.trimStart('/')
        }
        if (!url.isNullOrBlank() &&
            (url.startsWith("http://", true) || url.startsWith("https://", true))
        ) {
            return url
        }
        // A filesystem path (or anything else non-absolute) yields nothing so
        // the UI shows its placeholder instead of a broken or local reference.
        return ""
    }

    /** Leading-article-stripped, lowercased sort key. Stored at ingest. */
    fun sortTitle(title: String): String {
        var t = title.trim().lowercase()
        for (article in listOf("the ", "a ", "an ")) {
            if (t.startsWith(article)) {
                t = t.removePrefix(article)
                break
            }
        }
        return t
    }

    /** Alpha-jump bucket; anything non-alphabetic buckets under "#". */
    fun letterBucket(sortTitle: String): String {
        val first = sortTitle.firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    /** Personal libraries first, then provider catalogs, each alphabetical. */
    fun ordered(libraries: List<Library>): List<Library> =
        libraries.sortedWith(
            compareByDescending<Library> { it.isPersonal }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
}
