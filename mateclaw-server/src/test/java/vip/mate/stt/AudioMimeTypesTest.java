package vip.mate.stt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pinned behaviour for the filename / content-type inference. The pre-fix bug
 * was a single hardcoded {@code "audio.ogg"} default that lied about WebM
 * content — DashScope inspected the extension and rejected the bytes. These
 * tests pin the new contract: filename and content-type stay in sync with the
 * real audio format whichever side the caller supplied.
 */
class AudioMimeTypesTest {

    @Test
    @DisplayName("resolveFileName: trusts a caller filename with a known extension")
    void resolveFileName_trustsKnownExtension() {
        assertEquals("clip.mp3", AudioMimeTypes.resolveFileName("clip.mp3", null));
        assertEquals("speech.WAV", AudioMimeTypes.resolveFileName("speech.WAV", null));
    }

    @Test
    @DisplayName("resolveFileName: synthesises from content-type when filename is missing")
    void resolveFileName_synthesisesFromContentType() {
        // The crucial case — frontend sends bare bytes + content-type only.
        assertEquals("audio.mp3", AudioMimeTypes.resolveFileName(null, "audio/mpeg"));
        assertEquals("audio.wav", AudioMimeTypes.resolveFileName(null, "audio/wav"));
        assertEquals("audio.webm", AudioMimeTypes.resolveFileName(null, "audio/webm"));
        assertEquals("audio.m4a", AudioMimeTypes.resolveFileName(null, "audio/mp4"));
    }

    @Test
    @DisplayName("resolveFileName: falls back to wav when both inputs are blank/unknown")
    void resolveFileName_fallsBackToWav() {
        // WAV is the lowest common denominator every STT provider accepts.
        assertEquals("audio.wav", AudioMimeTypes.resolveFileName(null, null));
        assertEquals("audio.wav", AudioMimeTypes.resolveFileName("", ""));
        // Unknown extension on filename → re-derive from contentType / fallback.
        assertEquals("audio.wav", AudioMimeTypes.resolveFileName("blob.bin", null));
    }

    @Test
    @DisplayName("resolveFileName: strips content-type parameters before lookup")
    void resolveFileName_handlesContentTypeWithParameters() {
        // MediaRecorder emits "audio/webm;codecs=opus" — must not break the lookup.
        assertEquals("audio.webm", AudioMimeTypes.resolveFileName(null, "audio/webm;codecs=opus"));
    }
}
