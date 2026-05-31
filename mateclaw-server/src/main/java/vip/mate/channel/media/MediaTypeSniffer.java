package vip.mate.channel.media;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Best-effort MIME / extension detection from a file's leading bytes.
 *
 * <p>IM channels frequently deliver media without a reliable filename or
 * Content-Type: forwarded files arrive nameless, and personal-WeChat images
 * are saved with a fixed {@code image.jpg} hint regardless of the real format.
 * Labelling a PNG / WEBP / HEIC photo as {@code image/jpeg} makes some
 * multimodal model gateways reject the request, and a nameless PDF saved as
 * {@code file.bin} stops PDF tools from firing. Sniffing the magic bytes
 * recovers an accurate type so downstream routing and vision models work.
 *
 * <p>Covers the formats users routinely send to bots: common raster images
 * (incl. HEIC from iPhones and WEBP from screenshots), documents, archives,
 * and audio / video containers. ZIP-based containers (DOCX/XLSX/PPTX/ODF/EPUB/
 * JAR) share one magic number, so a successful ZIP match is refined by peeking
 * at the first archive entries.
 */
public final class MediaTypeSniffer {

    private MediaTypeSniffer() {
    }

    /** Sniff result: a leading-dot extension plus the matching MIME type. */
    public record Sniffed(String extension, String contentType) {
        /** Fallback when no signature matches. */
        public static final Sniffed UNKNOWN = new Sniffed(".bin", "application/octet-stream");

        public boolean isKnown() {
            return !UNKNOWN.equals(this);
        }

        public boolean isImage() {
            return contentType.startsWith("image/");
        }

        public boolean isVideo() {
            return contentType.startsWith("video/");
        }

        public boolean isAudio() {
            return contentType.startsWith("audio/");
        }
    }

    /**
     * Detect the type of {@code data} from its leading bytes.
     *
     * @param data the full file bytes (may be null/empty — returns
     *             {@link Sniffed#UNKNOWN}); only the first bytes are inspected,
     *             except for ZIP containers which are scanned a little deeper.
     * @return the detected type, never null.
     */
    public static Sniffed sniff(byte[] data) {
        if (data == null || data.length < 4) {
            return Sniffed.UNKNOWN;
        }

        Sniffed basic = sniffHead(data);
        // ZIP container needs a deeper look — DOCX/XLSX/PPTX/ODF/EPUB/JAR all
        // share the PK\x03\x04 magic. Peek inside the first few entries.
        if (".zip".equals(basic.extension())) {
            return refineZipKind(data, basic);
        }
        return basic;
    }

    /** Signature match against the leading bytes only. */
    private static Sniffed sniffHead(byte[] h) {
        // PDF: %PDF
        if (match(h, 0x25, 0x50, 0x44, 0x46)) {
            return new Sniffed(".pdf", "application/pdf");
        }
        // PNG: 89 50 4E 47
        if (match(h, 0x89, 0x50, 0x4E, 0x47)) {
            return new Sniffed(".png", "image/png");
        }
        // JPEG: FF D8 FF
        if (match(h, 0xFF, 0xD8, 0xFF)) {
            return new Sniffed(".jpg", "image/jpeg");
        }
        // GIF: "GIF8"
        if (match(h, 0x47, 0x49, 0x46, 0x38)) {
            return new Sniffed(".gif", "image/gif");
        }
        // BMP: "BM"
        if (match(h, 0x42, 0x4D)) {
            return new Sniffed(".bmp", "image/bmp");
        }
        // TIFF: little-endian "II*\0" or big-endian "MM\0*"
        if (match(h, 0x49, 0x49, 0x2A, 0x00) || match(h, 0x4D, 0x4D, 0x00, 0x2A)) {
            return new Sniffed(".tiff", "image/tiff");
        }
        // RIFF container: bytes 0..3 = "RIFF", bytes 8..11 identify the payload.
        // WEBP is the one users send (screenshots / phone photos); WAV is audio.
        if (h.length >= 12 && match(h, 0x52, 0x49, 0x46, 0x46)) {
            if (matchAt(h, 8, 0x57, 0x45, 0x42, 0x50)) { // "WEBP"
                return new Sniffed(".webp", "image/webp");
            }
            if (matchAt(h, 8, 0x57, 0x41, 0x56, 0x45)) { // "WAVE"
                return new Sniffed(".wav", "audio/wav");
            }
        }
        // ISO Base Media (ftyp at bytes 4..7). The brand at bytes 8..11
        // distinguishes HEIC photos / M4A audio / QuickTime from plain MP4 —
        // critical because iPhone photos are HEIC, not video.
        if (h.length >= 12 && matchAt(h, 4, 0x66, 0x74, 0x79, 0x70)) {
            return classifyFtyp(brandAt(h, 8));
        }
        // ZIP-based container: PK\x03\x04 (refined by the caller).
        if (match(h, 0x50, 0x4B, 0x03, 0x04)) {
            return new Sniffed(".zip", "application/zip");
        }
        // Legacy Office (DOC/XLS/PPT): D0 CF 11 E0 A1 B1 1A E1
        if (h.length >= 8 && match(h, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return new Sniffed(".doc", "application/msword");
        }
        // RTF: "{\rtf"
        if (h.length >= 5 && match(h, 0x7B, 0x5C, 0x72, 0x74, 0x66)) {
            return new Sniffed(".rtf", "application/rtf");
        }
        // 7z: 37 7A BC AF 27 1C
        if (h.length >= 6 && match(h, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) {
            return new Sniffed(".7z", "application/x-7z-compressed");
        }
        // RAR: "Rar!\x1A\x07"
        if (h.length >= 6 && match(h, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) {
            return new Sniffed(".rar", "application/x-rar-compressed");
        }
        // MP3: ID3v2 tag "ID3"
        if (match(h, 0x49, 0x44, 0x33)) {
            return new Sniffed(".mp3", "audio/mpeg");
        }
        // MP3: MPEG audio frame sync (0xFFFB / 0xFFF3 / 0xFFF2)
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) {
            return new Sniffed(".mp3", "audio/mpeg");
        }
        // OGG: "OggS"
        if (match(h, 0x4F, 0x67, 0x67, 0x53)) {
            return new Sniffed(".ogg", "audio/ogg");
        }
        // AMR (WeChat / WeCom voice): "#!AMR"
        if (h.length >= 5 && match(h, 0x23, 0x21, 0x41, 0x4D, 0x52)) {
            return new Sniffed(".amr", "audio/amr");
        }
        // SILK (WeChat voice): "#!SILK"
        if (h.length >= 6 && match(h, 0x23, 0x21, 0x53, 0x49, 0x4C, 0x4B)) {
            return new Sniffed(".silk", "audio/silk");
        }
        return Sniffed.UNKNOWN;
    }

    /** Map an ISO-BMFF major brand to a concrete type. */
    private static Sniffed classifyFtyp(String brand) {
        if (brand == null) {
            return new Sniffed(".mp4", "video/mp4");
        }
        // HEIF / HEIC still images (iPhone camera default).
        switch (brand) {
            case "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs",
                 "mif1", "msf1" -> {
                return new Sniffed(".heic", "image/heic");
            }
            case "avif", "avis" -> {
                return new Sniffed(".avif", "image/avif");
            }
            case "qt  " -> {
                return new Sniffed(".mov", "video/quicktime");
            }
            case "M4A ", "M4B " -> {
                return new Sniffed(".m4a", "audio/mp4");
            }
            case "M4V " -> {
                return new Sniffed(".m4v", "video/x-m4v");
            }
            default -> {
                return new Sniffed(".mp4", "video/mp4");
            }
        }
    }

    /**
     * Peek inside a ZIP container to distinguish OOXML (DOCX/XLSX/PPTX), ODF
     * (ODT/ODS/ODP), JAR, and EPUB from a plain ZIP. Reads local file headers
     * in order; the discriminator entry is almost always within the first few
     * entries, so iteration is capped at 16 to bound CPU. Returns the supplied
     * {@code zipDefault} when nothing specific is detected.
     */
    private static Sniffed refineZipKind(byte[] data, Sniffed zipDefault) {
        if (data == null || data.length < 30) {
            return zipDefault;
        }
        String mimetypeContent = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zis.getNextEntry()) != null && seen < 16) {
                seen++;
                String name = entry.getName();
                if (name.startsWith("word/")) {
                    return new Sniffed(".docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                }
                if (name.startsWith("xl/")) {
                    return new Sniffed(".xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                }
                if (name.startsWith("ppt/")) {
                    return new Sniffed(".pptx",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
                }
                if (name.startsWith("visio/")) {
                    return new Sniffed(".vsdx", "application/vnd.ms-visio.drawing");
                }
                if ("META-INF/MANIFEST.MF".equals(name)) {
                    return new Sniffed(".jar", "application/java-archive");
                }
                // EPUB always carries META-INF/container.xml.
                if ("META-INF/container.xml".equals(name)) {
                    return new Sniffed(".epub", "application/epub+zip");
                }
                // ODF / EPUB also declare the type in a leading "mimetype" entry.
                if ("mimetype".equals(name)) {
                    byte[] body = zis.readAllBytes();
                    mimetypeContent = new String(body, StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Exception e) {
            return zipDefault;
        }
        // Match leniently (contains) — the mimetype body occasionally carries a
        // trailing newline or charset noise.
        if (mimetypeContent != null) {
            if (mimetypeContent.contains("opendocument.text")) {
                return new Sniffed(".odt", "application/vnd.oasis.opendocument.text");
            }
            if (mimetypeContent.contains("opendocument.spreadsheet")) {
                return new Sniffed(".ods", "application/vnd.oasis.opendocument.spreadsheet");
            }
            if (mimetypeContent.contains("opendocument.presentation")) {
                return new Sniffed(".odp", "application/vnd.oasis.opendocument.presentation");
            }
            if (mimetypeContent.contains("epub")) {
                return new Sniffed(".epub", "application/epub+zip");
            }
        }
        return zipDefault;
    }

    /** True when the leading bytes equal the given unsigned-byte signature. */
    private static boolean match(byte[] data, int... signature) {
        return matchAt(data, 0, signature);
    }

    /** True when bytes starting at {@code offset} equal the signature. */
    private static boolean matchAt(byte[] data, int offset, int... signature) {
        if (data.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[offset + i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** Read a 4-character ASCII brand at the given offset, or null. */
    private static String brandAt(byte[] data, int offset) {
        if (data.length < offset + 4) {
            return null;
        }
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }
}
