package androidx.media3.decoder.ffmpeg;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;

import java.util.Collections;

/**
 * Public door onto the bundled media3 FFmpeg audio decoder.
 *
 * <p>{@code FfmpegAudioDecoder} is package-private in the vendored
 * media3-decoder-ffmpeg AAR, and Kotlin has no package-private access at
 * all, so the cast HLS proxy cannot construct it directly. This class
 * lives in the AAR's own package (same runtime package: one app
 * classloader, so package-private access resolves) and exposes the few
 * operations the proxy's audio transcoder needs as a small synchronous
 * queue/drain surface.
 *
 * <p>Used by {@code CastAudioTranscoder} on phones that ship no AC-3 /
 * E-AC-3 / MP2 MediaCodec (for example the Qualcomm "yupik" Nothing
 * Phone, whose vendor media_codecs list has none): local playback
 * already decodes those through this same FFmpeg extension via
 * {@code aerioRenderersFactory}, so casting must not refuse where
 * playback works.
 *
 * <p>Threading: the underlying {@code SimpleDecoder} runs its own decode
 * thread; {@link #queue} and {@link #dequeueOutput} are non-blocking and
 * are both driven from the proxy's single ingest thread.
 */
@OptIn(markerClass = UnstableApi.class)
public final class AerioFfmpegPcmDecoder {

    /** Matches the renderer's own pool sizes (media3 FfmpegAudioRenderer). */
    private static final int NUM_INPUT_BUFFERS = 16;
    private static final int NUM_OUTPUT_BUFFERS = 16;
    /** Biggest plausible AC-3/E-AC-3 access unit; buffers grow if needed. */
    private static final int INITIAL_INPUT_BUFFER_SIZE = 8 * 1024;

    private final FfmpegAudioDecoder decoder;

    private AerioFfmpegPcmDecoder(Format format) throws DecoderException {
        this.decoder = new FfmpegAudioDecoder(
                format,
                NUM_INPUT_BUFFERS,
                NUM_OUTPUT_BUFFERS,
                INITIAL_INPUT_BUFFER_SIZE,
                /* outputFloat= */ false);
    }

    /** True when the bundled libffmpegJNI carries a decoder for this mime. */
    public static boolean isSupported(String sampleMimeType) {
        return FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(sampleMimeType);
    }

    /**
     * Builds a decoder for a bare elementary stream: mime plus channel
     * count and sample rate off the parsed frame header, no
     * initialization data (AC-3 / E-AC-3 / MP2 carry none).
     */
    public static AerioFfmpegPcmDecoder create(String sampleMimeType, int channelCount, int sampleRate)
            throws DecoderException {
        Format format = new Format.Builder()
                .setSampleMimeType(sampleMimeType)
                .setChannelCount(channelCount)
                .setSampleRate(sampleRate)
                .setInitializationData(Collections.<byte[]>emptyList())
                .build();
        return new AerioFfmpegPcmDecoder(format);
    }

    /** e.g. "ffmpeg6.0-ac3". */
    public String getName() {
        return decoder.getName();
    }

    /** Channel count of the decoded PCM; valid after the first output. */
    public int getChannelCount() {
        return decoder.getChannelCount();
    }

    /** Sample rate of the decoded PCM; valid after the first output. */
    public int getSampleRate() {
        return decoder.getSampleRate();
    }

    /**
     * Queues one access unit. Returns false when every input buffer is
     * still in flight, in which case the caller should drain and retry.
     */
    public boolean queue(byte[] data, int offset, int length, long timeUs) throws DecoderException {
        DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
        if (inputBuffer == null) {
            return false;
        }
        inputBuffer.ensureSpaceForWrite(length);
        inputBuffer.data.put(data, offset, length);
        inputBuffer.timeUs = timeUs;
        inputBuffer.flip();
        decoder.queueInputBuffer(inputBuffer);
        return true;
    }

    /**
     * Next decoded buffer, or null when none is ready. The caller must
     * {@link SimpleDecoderOutputBuffer#release()} what it gets back.
     * Interleaved 16-bit PCM in {@code buffer.data}, presentation stamp
     * in {@code buffer.timeUs} (carried straight through from the
     * queued access unit).
     */
    @Nullable
    public SimpleDecoderOutputBuffer dequeueOutput() throws DecoderException {
        return decoder.dequeueOutputBuffer();
    }

    public void flush() {
        decoder.flush();
    }

    public void release() {
        decoder.release();
    }
}
