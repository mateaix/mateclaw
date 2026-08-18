package vip.mate.stt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Strip the RIFF/WAVE header off a WAV blob to expose raw PCM samples.
 *
 * <p>Used for pre-flight audio diagnostics: the web recorder (see
 * {@code mateclaw-ui/src/utils/wavEncoder.ts}) emits a 16 kHz mono 16-bit
 * WAV with the canonical 44-byte header, and unwrapping it lets STT
 * providers run a peak/RMS silence check on the raw samples before paying
 * for a recognition call — "mic captured nothing" then surfaces as a
 * precise local error instead of an empty transcript.
 *
 * <p>Limitations: handles only the canonical 44-byte WAV layout produced by
 * MateClaw's WavRecorder. WAVs with extra chunks (LIST, JUNK, …) before the
 * data chunk would need a chunk-walking parser. Callers should gate on
 * {@link #isCanonicalWav} and skip the diagnostics for anything else,
 * rather than treating non-WAV input as an error.
 */
public final class WavPcmExtractor {

    /** Bytes before the "data" chunk in a canonical mono 16-bit PCM WAV. */
    public static final int CANONICAL_HEADER_BYTES = 44;

    /** Sample rate field offset in the canonical WAV header. */
    private static final int OFFSET_SAMPLE_RATE = 24;

    private WavPcmExtractor() {}

    /**
     * True only for the 44-byte PCM16/mono layout produced by MateClaw's web
     * recorder. Stereo WAVs and files with extra chunks are still valid audio,
     * but callers must send them directly to STT instead of applying the
     * mono-specific sample math in this helper.
     */
    public static boolean isCanonicalWav(byte[] bytes) {
        return bytes != null && bytes.length >= CANONICAL_HEADER_BYTES
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E'
                && bytes[12] == 'f' && bytes[13] == 'm' && bytes[14] == 't' && bytes[15] == ' '
                && unsignedShort(bytes, 20) == 1
                && unsignedShort(bytes, 22) == 1
                && unsignedShort(bytes, 34) == 16
                && bytes[36] == 'd' && bytes[37] == 'a' && bytes[38] == 't' && bytes[39] == 'a';
    }

    /**
     * Extract raw PCM bytes from a WAV blob. Throws when the input is too short
     * or the magic header bytes don't look like RIFF/WAVE — better to fail loud
     * here than ship garbage to DashScope and chase a confusing error code.
     */
    public static byte[] extract(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < CANONICAL_HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "WAV input too short: " + (wavBytes == null ? 0 : wavBytes.length) + " bytes");
        }
        if (wavBytes[0] != 'R' || wavBytes[1] != 'I' || wavBytes[2] != 'F' || wavBytes[3] != 'F'
                || wavBytes[8] != 'W' || wavBytes[9] != 'A' || wavBytes[10] != 'V' || wavBytes[11] != 'E') {
            throw new IllegalArgumentException("Not a WAV (missing RIFF/WAVE magic)");
        }
        byte[] pcm = new byte[wavBytes.length - CANONICAL_HEADER_BYTES];
        System.arraycopy(wavBytes, CANONICAL_HEADER_BYTES, pcm, 0, pcm.length);
        return pcm;
    }

    /**
     * Read the sample rate from a WAV header. Used by callers that need to
     * tell DashScope the actual rate of the audio (the API requires the rate
     * up front in the {@code run-task} message — getting it wrong produces
     * recognisable but distorted transcripts).
     */
    public static int sampleRate(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < CANONICAL_HEADER_BYTES) {
            throw new IllegalArgumentException("WAV input too short for sample-rate read");
        }
        return ByteBuffer.wrap(wavBytes, OFFSET_SAMPLE_RATE, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

}
