package vip.mate.stt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pinned behaviour for the WAV → raw-PCM helper.
 *
 * <p>Why this matters: DashScope's realtime ASR rejects bare WAV with
 * "format mismatch" because the first 44 bytes look like garbage when
 * interpreted as PCM. {@link WavPcmExtractor} is the chokepoint that
 * converts the frontend's WAV blob to the bytes DashScope actually wants.
 * Wrong header offset → silent garbage transcripts; wrong sample-rate read
 * → audibly distorted.
 */
class WavPcmExtractorTest {

    @Test
    @DisplayName("extract: drops the 44-byte canonical header and returns the PCM tail")
    void extract_dropsCanonicalHeader() {
        // Build a minimal valid WAV: 44-byte header + 8 bytes of fake PCM.
        byte[] wav = buildWav(16_000, 16, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        byte[] pcm = WavPcmExtractor.extract(wav);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, pcm);
    }

    @Test
    @DisplayName("extract: rejects non-WAV input loudly (no silent garbage)")
    void extract_rejectsNonWav() {
        // Anything without the RIFF/WAVE magic must fail fast — sending non-WAV
        // bytes to DashScope wastes API quota and produces confusing errors.
        byte[] junk = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                                  16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
                                  32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45};
        assertThrows(IllegalArgumentException.class, () -> WavPcmExtractor.extract(junk));
    }

    @Test
    @DisplayName("extract: rejects too-short input (no out-of-bounds)")
    void extract_rejectsTooShort() {
        assertThrows(IllegalArgumentException.class, () -> WavPcmExtractor.extract(new byte[10]));
        assertThrows(IllegalArgumentException.class, () -> WavPcmExtractor.extract(null));
    }

    @Test
    @DisplayName("sampleRate: reads 16 kHz from the canonical header offset")
    void sampleRate_reads16kHz() {
        byte[] wav = buildWav(16_000, 16, new byte[8]);
        assertEquals(16_000, WavPcmExtractor.sampleRate(wav));
    }

    @Test
    @DisplayName("sampleRate: reads 44.1 kHz when Safari-style mic captures at the device default")
    void sampleRate_reads44100() {
        // Defends against the Safari-on-iOS path where the frontend can't
        // force 16 kHz at capture time. We resample on the way out, but the
        // server-side helper still needs to read the actual rate.
        byte[] wav = buildWav(44_100, 16, new byte[8]);
        assertEquals(44_100, WavPcmExtractor.sampleRate(wav));
    }

    /* ------------------------------------------------------------------ */
    /* Helper: build a minimal valid WAV with the canonical 44-byte header.*/
    /* Mirrors the layout produced by mateclaw-ui/src/utils/wavEncoder.ts. */
    /* ------------------------------------------------------------------ */
    private static byte[] buildWav(int sampleRate, int bitsPerSample, byte[] pcmData) {
        int dataSize = pcmData.length;
        int numChannels = 1;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);                                              // fmt chunk size
        buf.putShort((short) 1);                                      // PCM
        buf.putShort((short) numChannels);
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * numChannels * (bitsPerSample / 8));   // byte rate
        buf.putShort((short) (numChannels * (bitsPerSample / 8)));    // block align
        buf.putShort((short) bitsPerSample);
        buf.put("data".getBytes());
        buf.putInt(dataSize);
        buf.put(pcmData);
        return buf.array();
    }
}
