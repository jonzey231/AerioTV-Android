package com.aeriotv.android.core.parser

import com.aeriotv.android.core.data.M3UChannel

/**
 * M3U/M3U8 playlist parser. Ports iOS Aerio/Networking/PlaylistParsers.swift
 * (lines 16-105) to Kotlin. Matches algorithm and edge-case handling line-for-line:
 *  - Line-by-line scan with lookahead for the URL line after #EXTINF
 *  - Skip blank lines and # comments between #EXTINF and its URL
 *  - Attributes parsed by regex `([\w-]+)="([^"]*)"`, keys lowercased
 *  - Display name comes from text after the last comma in the #EXTINF line
 *  - Aggressive whitespace trimming on every candidate line
 *  - UTF-8 first, fall back to ISO-8859-1 (handled by [parseBytes])
 *  - Unquoted attributes silently ignored
 *  - #EXTINF without a URL line is silently dropped (not an error)
 *  - #EXTVLCOPT / #KODIPROP / #EXTGRP / #EXT-X-* are not parsed
 */
object M3UParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")

    /**
     * Try UTF-8, fall back to ISO-8859-1. Mirrors iOS String(data:encoding:) fallback.
     * BOM is consumed automatically by both decoders.
     */
    fun parseBytes(bytes: ByteArray): List<M3UChannel> {
        val text = runCatching { String(bytes, Charsets.UTF_8) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        return parse(text)
    }

    fun parse(content: String): List<M3UChannel> {
        return parseLines(content.splitToSequence('\n', '\r'))
    }

    /**
     * GH #26: parse a downloaded playlist FILE line-by-line in constant
     * memory. A full XC-panel M3U (live + VOD) runs 100-200MB; the old
     * path (whole-body ByteArray -> String -> split) held several copies
     * resident at once and OOM'd 256MB-heap phones during Add Playlist.
     * Charset semantics match [parseBytes]: strict UTF-8 first, and ANY
     * malformed byte re-parses the whole file as ISO-8859-1.
     */
    fun parseFile(file: java.io.File): List<M3UChannel> {
        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            java.io.BufferedReader(java.io.InputStreamReader(file.inputStream(), strictUtf8))
                .use { r -> parseLines(r.lineSequence()) }
        } catch (_: java.nio.charset.CharacterCodingException) {
            file.inputStream().bufferedReader(Charsets.ISO_8859_1)
                .use { r -> parseLines(r.lineSequence()) }
        }
    }

    /**
     * The ONE parse implementation, consuming lines as a sequence so file
     * and string inputs share it. Semantics preserved from the original
     * index/lookahead loop:
     *  - blank lines and #-comments between #EXTINF and its URL are skipped
     *  - a second #EXTINF before the first found its URL is skipped like a
     *    comment (the URL that follows still binds to the FIRST #EXTINF)
     *  - #EXTINF without a URL line is silently dropped
     *  - a bare URL with no preceding #EXTINF is ignored
     */
    private fun parseLines(lines: Sequence<String>): List<M3UChannel> {
        val channels = mutableListOf<M3UChannel>()
        var pendingExtInf: String? = null
        var pendingKodiProps: MutableMap<String, String>? = null
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    if (pendingExtInf == null) {
                        pendingExtInf = line
                        pendingKodiProps = null
                    }
                }
                // GH #27: capture Kodi inputstream properties between the
                // #EXTINF and its URL -- the convention playlists use to
                // carry DASH DRM license info (license_type / license_key).
                line.startsWith("#KODIPROP:") -> {
                    val body = line.removePrefix("#KODIPROP:")
                    val eq = body.indexOf('=')
                    if (pendingExtInf != null && eq > 0) {
                        val props = pendingKodiProps
                            ?: mutableMapOf<String, String>().also { pendingKodiProps = it }
                        props[body.substring(0, eq).trim().lowercase()] =
                            body.substring(eq + 1).trim()
                    }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    pendingExtInf?.let { ext ->
                        channels += buildChannel(ext, line, pendingKodiProps)
                    }
                    pendingExtInf = null
                    pendingKodiProps = null
                }
            }
        }
        return channels
    }

    private fun buildChannel(
        extInfLine: String,
        url: String,
        kodiProps: Map<String, String>? = null,
    ): M3UChannel {
        val attrs = parseExtInf(extInfLine)
        val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
        // Stable ID — prefer tvg-id (the broadcaster's canonical
        // channel key), fall back to the stream URL which is also
        // stable across refreshes of the same source. iOS uses the
        // raw UUID per fetch and stores its own per-channel id in
        // ChannelDisplayItem; Android keeps the favorites store
        // keyed off this stable string so the user's saved rows
        // survive a playlist reload.
        return M3UChannel(
            id = "m3u:${tvgId ?: url}",
            name = attrs["name"]?.ifBlank { null } ?: "Unknown Channel",
            url = url,
            groupTitle = attrs["group-title"].orEmpty(),
            tvgID = tvgId.orEmpty(),
            tvgName = attrs["tvg-name"].orEmpty(),
            tvgLogo = attrs["tvg-logo"].orEmpty(),
            channelNumber = attrs["tvg-chno"]?.trim()?.takeIf { it.isNotBlank() },
            rawAttributes = attrs.filterKeys { it != "name" },
            drmLicenseType = kodiProps?.get("inputstream.adaptive.license_type")
                ?.takeIf { it.isNotBlank() },
            drmLicenseKey = kodiProps?.get("inputstream.adaptive.license_key")
                ?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseExtInf(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        val commaIndex = line.lastIndexOf(',')
        if (commaIndex >= 0 && commaIndex < line.length - 1) {
            result["name"] = line.substring(commaIndex + 1).trim()
        }

        for (match in ATTRIBUTE_REGEX.findAll(line)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
            result[key] = value
        }

        return result
    }
}
