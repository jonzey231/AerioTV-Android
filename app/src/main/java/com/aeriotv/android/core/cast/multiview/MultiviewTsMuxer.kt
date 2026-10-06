package com.aeriotv.android.core.cast.multiview

import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG-TS muxer for the composited Multiview cast: one H.264 video
 * PID and one AAC (ADTS) audio PID, 90 kHz timestamps. Its output is fed to
 * the cast proxy's [com.aeriotv.android.core.cast.hlsproxy.TsToFmp4Remuxer]
 * exactly like a channel's raw TS, so the composite rides the same
 * segmenter, playlists, init segments and server as every channel cast.
 *
 * PAT and PMT go out before the first packet and before every key frame,
 * and every key frame carries its SPS / PPS in band, so a fresh remuxer can
 * join at any IDR. Pure (no Android types): unit tested.
 *
 * Not thread-safe: the compositor serializes video and audio writes.
 */
class MultiviewTsMuxer(private val out: (ByteArray) -> Unit) {

    companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        private const val PACKET = 188
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_AAC_ADTS = 0x0F
        private const val PTS_MASK = (1L shl 33) - 1
        /** PCR runs this far behind the video PTS (decoder buffering room). */
        private const val PCR_LEAD_TICKS = 9_000L
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())

        /** MPEG-2 CRC32 (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection). */
        fun crc32Mpeg(data: ByteArray, from: Int, to: Int): Int {
            var crc = -1
            for (i in from until to) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
                repeat(8) {
                    crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
                }
            }
            return crc
        }

        /** NAL unit types present in an Annex-B access unit. */
        fun nalTypes(annexB: ByteArray): List<Int> {
            val types = ArrayList<Int>()
            var i = 0
            while (i + 3 < annexB.size) {
                if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 &&
                    (annexB[i + 2].toInt() == 1 || (annexB[i + 2].toInt() == 0 && i + 4 < annexB.size && annexB[i + 3].toInt() == 1))
                ) {
                    val start = if (annexB[i + 2].toInt() == 1) i + 3 else i + 4
                    if (start < annexB.size) types.add(annexB[start].toInt() and 0x1F)
                    i = start
                } else {
                    i++
                }
            }
            return types
        }
    }

    private val cc = IntArray(0x2000)
    private var tablesWritten = false

    /** One H.264 access unit (Annex-B). [parameterSets] (Annex-B SPS + PPS)
     *  are prepended to a key frame that does not already carry an SPS. */
    fun writeVideo(annexB: ByteArray, pts90k: Long, keyframe: Boolean, parameterSets: ByteArray?) {
        if (keyframe || !tablesWritten) writeTables()
        val au = ByteArrayOutputStream(annexB.size + 64)
        au.write(AUD)
        if (keyframe && parameterSets != null && 7 !in nalTypes(annexB)) au.write(parameterSets)
        au.write(annexB)
        val pes = pes(0xE0, au.toByteArray(), pts90k, boundedLength = false)
        packetize(VIDEO_PID, pes, pcr = if (keyframe) (pts90k - PCR_LEAD_TICKS).coerceAtLeast(0L) else null, randomAccess = keyframe)
    }

    /** One ADTS AAC frame. */
    fun writeAudio(adtsFrame: ByteArray, pts90k: Long) {
        if (!tablesWritten) writeTables()
        packetize(AUDIO_PID, pes(0xC0, adtsFrame, pts90k, boundedLength = true), pcr = null, randomAccess = false)
    }

    private fun writeTables() {
        tablesWritten = true
        // PAT: program 1 -> PMT_PID.
        val pat = section(
            tableId = 0x00, idExt = 1,
            body = byteArrayOf(0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte()),
        )
        writePsi(0x0000, pat)
        val pmtBody = byteArrayOf(
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), // PCR PID
            0xF0.toByte(), 0x00, // program_info_length 0
            STREAM_TYPE_H264.toByte(), (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00,
            STREAM_TYPE_AAC_ADTS.toByte(), (0xE0 or (AUDIO_PID shr 8)).toByte(), (AUDIO_PID and 0xFF).toByte(), 0xF0.toByte(), 0x00,
        )
        writePsi(PMT_PID, section(tableId = 0x02, idExt = 1, body = pmtBody))
    }

    private fun section(tableId: Int, idExt: Int, body: ByteArray): ByteArray {
        val sectionLength = 5 + body.size + 4
        val s = ByteArray(3 + sectionLength)
        s[0] = tableId.toByte()
        s[1] = (0xB0 or (sectionLength shr 8)).toByte()
        s[2] = (sectionLength and 0xFF).toByte()
        s[3] = (idExt shr 8).toByte()
        s[4] = (idExt and 0xFF).toByte()
        s[5] = 0xC1.toByte() // version 0, current_next 1
        s[6] = 0
        s[7] = 0
        System.arraycopy(body, 0, s, 8, body.size)
        val crc = crc32Mpeg(s, 0, s.size - 4)
        s[s.size - 4] = (crc ushr 24).toByte()
        s[s.size - 3] = (crc ushr 16).toByte()
        s[s.size - 2] = (crc ushr 8).toByte()
        s[s.size - 1] = crc.toByte()
        return s
    }

    private fun writePsi(pid: Int, section: ByteArray) {
        val p = ByteArray(PACKET) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (0x40 or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = (0x10 or nextCc(pid)).toByte()
        p[4] = 0 // pointer_field
        System.arraycopy(section, 0, p, 5, section.size)
        out(p)
    }

    private fun pes(streamId: Int, payload: ByteArray, pts90k: Long, boundedLength: Boolean): ByteArray {
        val header = 9 + 5
        val pes = ByteArray(header + payload.size)
        pes[0] = 0; pes[1] = 0; pes[2] = 1
        pes[3] = streamId.toByte()
        val len = 3 + 5 + payload.size
        if (boundedLength && len <= 0xFFFF) {
            pes[4] = (len shr 8).toByte()
            pes[5] = (len and 0xFF).toByte()
        }
        pes[6] = 0x80.toByte()
        pes[7] = 0x80.toByte() // PTS only (no B-frames: DTS == PTS)
        pes[8] = 5
        val pts = pts90k and PTS_MASK
        pes[9] = (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte()
        pes[10] = (pts shr 22).toByte()
        pes[11] = (((pts shr 14).toInt() and 0xFE) or 1).toByte()
        pes[12] = (pts shr 7).toByte()
        pes[13] = (((pts shl 1).toInt() and 0xFE) or 1).toByte()
        System.arraycopy(payload, 0, pes, header, payload.size)
        return pes
    }

    private fun packetize(pid: Int, pes: ByteArray, pcr: Long?, randomAccess: Boolean) {
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val p = ByteArray(PACKET)
            p[0] = 0x47
            p[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            p[2] = (pid and 0xFF).toByte()
            // Adaptation field: PCR / random access on the first packet,
            // stuffing on the last one.
            val wantPcr = first && pcr != null
            val wantRai = first && randomAccess
            val afFixed = if (wantPcr || wantRai) (2 + if (wantPcr) 6 else 0) else 0
            val remaining = pes.size - offset
            val room = PACKET - 4 - afFixed
            val payloadLen: Int
            val afLen: Int // total adaptation field bytes including its length byte, 0 = none
            if (remaining >= room) {
                payloadLen = room
                afLen = afFixed
            } else {
                payloadLen = remaining
                afLen = PACKET - 4 - payloadLen
            }
            val hasAf = afLen > 0
            p[3] = ((if (hasAf) 0x30 else 0x10) or nextCc(pid)).toByte()
            var w = 4
            if (hasAf) {
                p[w++] = (afLen - 1).toByte()
                if (afLen > 1) {
                    var flags = 0
                    if (wantRai) flags = flags or 0x40
                    if (wantPcr) flags = flags or 0x10
                    p[w++] = flags.toByte()
                    if (wantPcr) {
                        val base = pcr and PTS_MASK
                        p[w++] = (base shr 25).toByte()
                        p[w++] = (base shr 17).toByte()
                        p[w++] = (base shr 9).toByte()
                        p[w++] = (base shr 1).toByte()
                        p[w++] = (((base and 1L) shl 7).toInt() or 0x7E).toByte()
                        p[w++] = 0
                    }
                    while (w < 4 + afLen) p[w++] = 0xFF.toByte()
                }
            }
            System.arraycopy(pes, offset, p, w, payloadLen)
            offset += payloadLen
            first = false
            out(p)
        }
    }

    private fun nextCc(pid: Int): Int {
        val c = cc[pid]
        cc[pid] = (c + 1) and 0x0F
        return c
    }
}

/** ADTS framing for the composite's AAC-LC stereo track. */
object Adts {
    /** Sampling frequency index (ISO 14496-3 table 1.18); 48 kHz = 3. */
    fun sampleRateIndex(rate: Int): Int =
        intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            .indexOf(rate).let { if (it < 0) 3 else it }

    /** [raw] AAC-LC frame wrapped in a 7-byte ADTS header (no CRC). */
    fun wrap(raw: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val len = raw.size + 7
        val sri = sampleRateIndex(sampleRate)
        val out = ByteArray(len)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // MPEG-4, layer 0, no CRC
        out[2] = (((2 - 1) shl 6) or (sri shl 2) or ((channels shr 2) and 1)).toByte() // profile LC
        out[3] = (((channels and 3) shl 6) or ((len shr 11) and 3)).toByte()
        out[4] = ((len shr 3) and 0xFF).toByte()
        out[5] = (((len and 7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        System.arraycopy(raw, 0, out, 7, raw.size)
        return out
    }
}
