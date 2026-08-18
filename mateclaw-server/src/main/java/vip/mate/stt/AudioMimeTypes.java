package vip.mate.stt;

import java.util.Locale;
import java.util.Map;

/**
 * Filename inference helper for the OpenAI Whisper STT path.
 *
 * <p>Whisper's {@code /v1/audio/transcriptions} endpoint reads the
 * <em>filename extension</em> on the multipart {@code file} part to infer
 * audio format; the {@code Content-Type} alone isn't enough because
 * Hutool's 3-arg {@code form(name, bytes, fileName)} overload deduces
 * the multipart Content-Type from the extension we pass. Hence this
 * helper picks an extension that matches the actual bytes.
 *
 * <p>Previous bug (pre-fix): providers hardcoded {@code "audio.ogg"} as the
 * default filename even when upstream content was WebM/Opus. The helper now
 * serves both multipart filenames (Whisper-compatible endpoints) and MIME-
 * qualified data URLs (Qwen3-ASR).
 */
public final class AudioMimeTypes {

    /** Fallback when neither contentType nor filename gives us a hint. */
    private static final String DEFAULT_EXTENSION = "wav";

    /** content-type → conventional file extension. Ordered for documentation only. */
    private static final Map<String, String> CONTENT_TYPE_TO_EXTENSION = Map.ofEntries(
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/wave", "wav"),
            Map.entry("audio/x-wav", "wav"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/mp3", "mp3"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/m4a", "m4a"),
            Map.entry("audio/x-m4a", "m4a"),
            Map.entry("audio/aac", "aac"),
            Map.entry("audio/flac", "flac"),
            Map.entry("audio/ogg", "ogg"),
            Map.entry("audio/webm", "webm"),
            Map.entry("audio/amr", "amr"));

    /** file extension → conventional content-type (the inverse, for filename-first cases). */
    private static final Map<String, String> EXTENSION_TO_CONTENT_TYPE = Map.ofEntries(
            Map.entry("wav", "audio/wav"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("mp4", "audio/mp4"),
            Map.entry("aac", "audio/aac"),
            Map.entry("flac", "audio/flac"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("webm", "audio/webm"),
            Map.entry("amr", "audio/amr"));

    private AudioMimeTypes() {}

    /**
     * Choose a filename for the upload. Order of precedence:
     * <ol>
     *   <li>The caller-supplied filename, when it has a known audio extension.</li>
     *   <li>A name synthesised from the content-type, e.g. {@code audio/mpeg → audio.mp3}.</li>
     *   <li>{@code audio.wav} as a final fallback (WAV is universally accepted).</li>
     * </ol>
     */
    public static String resolveFileName(String fileName, String contentType) {
        if (fileName != null && !fileName.isBlank() && extensionOf(fileName) != null) {
            return fileName;
        }
        String extension = extensionForContentType(contentType);
        return "audio." + (extension != null ? extension : DEFAULT_EXTENSION);
    }

    /**
     * Resolve the MIME type that describes the actual encoded bytes.
     *
     * <p>Data-URL based APIs (notably Qwen3-ASR) inspect the media type in
     * {@code data:audio/wav;base64,...}. Sending an empty media type can make
     * the service mis-detect or truncate otherwise valid audio while still
     * returning HTTP 200, so callers must never emit {@code data:;base64,...}.
     */
    public static String resolveContentType(String fileName, String contentType) {
        String extension = extensionForContentType(contentType);
        if (extension != null) {
            return EXTENSION_TO_CONTENT_TYPE.get(extension);
        }
        String resolvedFileName = resolveFileName(fileName, null);
        String fileExtension = extensionOf(resolvedFileName);
        return fileExtension != null
                ? EXTENSION_TO_CONTENT_TYPE.get(fileExtension)
                : EXTENSION_TO_CONTENT_TYPE.get(DEFAULT_EXTENSION);
    }

    /** Extract the lower-cased extension (without the dot), or null. Package-private for tests. */
    static String extensionOf(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSION_TO_CONTENT_TYPE.containsKey(ext) ? ext : null;
    }

    /** Map a content-type to its conventional extension, or null when unknown. */
    static String extensionForContentType(String contentType) {
        if (contentType == null) return null;
        // Strip parameters: "audio/webm; codecs=opus" → "audio/webm"
        int semi = contentType.indexOf(';');
        String base = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
        return CONTENT_TYPE_TO_EXTENSION.get(base);
    }
}
