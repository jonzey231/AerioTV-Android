package com.aeriotv.android.core.data

import java.util.UUID

/**
 * Mirrors iOS Aerio/Networking/PlaylistParsers.swift M3UChannel (lines 4-14).
 * Pure data class with no Room annotations yet — Phase 2b will add @Entity.
 */
data class M3UChannel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val groupTitle: String = "",
    val tvgID: String = "",
    val tvgName: String = "",
    val tvgLogo: String = "",
    val channelNumber: Int? = null,
    val rawAttributes: Map<String, String> = emptyMap(),
    /**
     * Dispatcharr's primary-key integer for this channel, used when scheduling
     * server-side recordings (`/api/channels/recordings/` requires the int id).
     * Null for M3U / Xtream sources that don't carry it.
     */
    val dispatcharrChannelId: Int? = null,
)
