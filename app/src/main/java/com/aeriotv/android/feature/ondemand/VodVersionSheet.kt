package com.aeriotv.android.feature.ondemand

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.data.VodLearnedStream
import com.aeriotv.android.core.network.DispatcharrVODProviderMedia
import com.aeriotv.android.core.network.DispatcharrVODProviderRelation
import java.util.Locale

/**
 * iOS `VODStreamFormatting` parity: the one place MEASURED stream properties
 * turn into descriptor text, so a copy described by the server and a copy
 * measured during playback on this device read identically in the picker.
 */
object VodStreamFormatting {
    /**
     * Resolution bucket from the REAL frame size. An upscaled file sold as
     * "4K" that is actually 1920 wide reads as 1080p here, which is the whole
     * reason the picker prefers measurements over provider titles.
     */
    fun resolutionLabel(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return when (maxOf(width, height)) {
            in 3200..Int.MAX_VALUE -> "4K"
            in 2400..3199 -> "1440p"
            in 1800..2399 -> "1080p"
            in 1200..1799 -> "720p"
            in 900..1199 -> "576p"
            else -> "480p"
        }
    }

    /** ffprobe codec name (or the fourCC spellings a container reports) ->
     *  what a human calls it. Unrecognised codecs surface uppercased rather
     *  than being dropped: it is still a real measurement. */
    fun videoCodecLabel(raw: String?): String? {
        val codec = raw?.takeIf { it.isNotBlank() } ?: return null
        return when (codec.lowercase()) {
            "hevc", "h265", "hvc1", "hev1" -> "HEVC"
            "h264", "avc", "avc1" -> "H.264"
            "av1", "av01" -> "AV1"
            "vp9" -> "VP9"
            "mpeg2video" -> "MPEG-2"
            else -> codec.uppercase()
        }
    }

    /** Codec plus a channel-layout suffix, e.g. "E-AC-3 5.1". Layouts other
     *  than 8 / 6 / 2 channels get no suffix (mono and the odd 7-channel mix
     *  have no name the picker would gain anything by inventing). */
    fun audioLabel(codec: String?, channels: Int?): String? {
        val raw = codec?.takeIf { it.isNotBlank() } ?: return null
        val name = when (raw.lowercase()) {
            "eac3", "ec-3" -> "E-AC-3"
            "ac3", "ac-3" -> "AC-3"
            "aac", "mp4a" -> "AAC"
            "truehd" -> "TrueHD"
            "dts" -> "DTS"
            "opus" -> "Opus"
            "mp3" -> "MP3"
            else -> raw.uppercase()
        }
        return when (channels) {
            8 -> "$name 7.1"
            6 -> "$name 5.1"
            2 -> "$name 2.0"
            else -> name
        }
    }

    /** Whole-file bitrate. Server-reported only: the player knows the bitrate
     *  of the track it is decoding, not of the file as a whole. */
    fun bitrateLabel(kbps: Int?): String? {
        if (kbps == null || kbps <= 0) return null
        return if (kbps >= 1000) {
            String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
        } else {
            "$kbps kbps"
        }
    }
}

/**
 * Measured descriptors for one provider copy, most significant first:
 * resolution, video codec, audio, bitrate.
 *
 * Server measurements win FIELD BY FIELD, and any field the server left blank
 * is filled from what this device measured while playing that same copy. Both
 * sources are real measurements, so they compose cleanly -- a row can end up
 * with a server resolution and a learned audio track. Bitrate stays
 * server-only. Audio falls back as a PAIR (codec + channels) so a server codec
 * is never dressed in a learned channel count.
 */
fun vodMeasuredDescriptors(
    media: DispatcharrVODProviderMedia?,
    learned: VodLearnedStream? = null,
): List<String> = buildList {
    val resolution = VodStreamFormatting.resolutionLabel(media?.width, media?.height)
        ?: VodStreamFormatting.resolutionLabel(learned?.width, learned?.height)
    val videoCodec = VodStreamFormatting.videoCodecLabel(media?.videoCodec)
        ?: VodStreamFormatting.videoCodecLabel(learned?.videoCodec)
    val audio = VodStreamFormatting.audioLabel(media?.audioCodec, media?.audioChannels)
        ?: VodStreamFormatting.audioLabel(learned?.audioCodec, learned?.audioChannels)
    resolution?.let(::add)
    videoCodec?.let(::add)
    audio?.let(::add)
    VodStreamFormatting.bitrateLabel(media?.bitrateKbps)?.let(::add)
}

/**
 * One selectable provider copy of a deduped VOD item (movie or series), for
 * the Version picker. Built from a /providers/ relation row; "Auto" (server
 * priority + failover) is represented by the ABSENCE of a selection, never by
 * an option instance. [streamId] pins movie playback (episodes pin by
 * [accountId] only).
 */
data class VodProviderOption(
    val relationId: Int,
    val accountId: Int,
    val accountName: String?,
    val streamId: String?,
    val label: String,
)

/**
 * Maps a /providers/ relation row into a picker option, or null when the row
 * carries no account id to pin (nothing selectable without one). Label rule:
 * "{account name} · {EXT} · {measured descriptors}"; account name falls back
 * "Source {id}". [media] is this copy's MEASURED stream data when the server
 * has reported it, and contributes nothing when it has not; [learned] is what
 * THIS device measured while playing the same copy, which fills the fields the
 * server left blank.
 */
fun DispatcharrVODProviderRelation.toVodProviderOption(
    media: DispatcharrVODProviderMedia? = null,
    learned: VodLearnedStream? = null,
): VodProviderOption? {
    val account = m3uAccount ?: return null
    val name = account.name?.takeIf { it.isNotBlank() } ?: "Source ${account.id}"
    // Account + container, then whatever was MEASURED for this copy
    // (resolution, codec, audio, bitrate) by the server, this device, or both.
    // Nothing about picture or sound is derived from the provider's own title:
    // those titles are marketing text and routinely claim a quality the file
    // does not deliver.
    val parts = buildList {
        add(name)
        containerExtension?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        addAll(vodMeasuredDescriptors(media, learned))
    }
    return VodProviderOption(
        relationId = id,
        accountId = account.id,
        accountName = account.name?.takeIf { it.isNotBlank() },
        streamId = streamId,
        label = parts.joinToString(" · "),
    )
}


/**
 * Maps a whole relation list to options with UNIQUE labels. One account
 * frequently carries several copies of the same title (measured on a live
 * server: one movie had two copies from one account and two from another, all
 * tagged 4K), so the raw label repeats and the rows become indistinguishable.
 * Collisions get a trailing "(n)" in server order.
 *
 * [media] is relation id -> server-measured stream data and arrives
 * progressively; [learned] is relation id -> what this device measured while
 * playing that copy, and grows as copies get watched. Uniquing runs on the
 * FINAL labels, so two copies that looked identical before their measurements
 * landed stop colliding once they differ. Both maps are keyed by RELATION id,
 * so [learned] is meaningful for movies only (an episode option pins an
 * account, not a file).
 */
fun List<DispatcharrVODProviderRelation>.toVodProviderOptions(
    media: Map<Int, DispatcharrVODProviderMedia> = emptyMap(),
    learned: Map<Int, VodLearnedStream> = emptyMap(),
): List<VodProviderOption> {
    val mapped = mapNotNull { it.toVodProviderOption(media[it.id], learned[it.id]) }
    val counts = mapped.groupingBy { it.label }.eachCount()
    val used = mutableMapOf<String, Int>()
    return mapped.map { option ->
        if ((counts[option.label] ?: 0) <= 1) return@map option
        val n = (used[option.label] ?: 0) + 1
        used[option.label] = n
        option.copy(label = "${option.label} ($n)")
    }
}

/**
 * Version picker: "Auto (recommended)" (server priority + failover) plus one
 * radio row per provider copy. FormFactorModal keeps it touch + TV D-pad safe;
 * clones the player sheets' RadioButton-row layout so it reads the same.
 * onSelect(null) = Auto.
 */
@Composable
fun VodVersionPickerSheet(
    options: List<VodProviderOption>,
    selected: VodProviderOption?,
    onSelect: (VodProviderOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    com.aeriotv.android.ui.FormFactorModal(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Version",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            VodVersionRow(
                label = "Auto (recommended)",
                selected = selected == null,
                onClick = { onSelect(null) },
            )
            options.forEach { option ->
                VodVersionRow(
                    label = option.label,
                    selected = selected?.relationId == option.relationId,
                    onClick = { onSelect(option) },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun VodVersionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
