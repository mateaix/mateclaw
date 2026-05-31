package vip.mate.channel.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mate.channel.ExponentialBackoff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared inbound-media pipeline for IM channels.
 *
 * <p>Every channel that receives images / files / audio / video from users
 * needs the same three steps after it knows how to fetch the raw bytes:
 * <ol>
 *   <li>fetch with retry + backoff (mobile uploads over flaky networks fail
 *       transiently — a single attempt drops the attachment);</li>
 *   <li>sniff the real type from magic bytes so the stored file and the
 *       {@code MessageContentPart} carry an accurate MIME (a screenshot saved
 *       as {@code image.jpg} but actually PNG/WEBP/HEIC otherwise gets a wrong
 *       Content-Type that some multimodal gateways reject);</li>
 *   <li>write to disk under a collision-resistant, URL-safe name.</li>
 * </ol>
 *
 * <p>The channel-specific protocol (AES decryption, API auth, CDN URL shape)
 * stays in the adapter and is supplied as a {@link ByteSource}. This class owns
 * only the cross-channel concerns above.
 */
public final class InboundMediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(InboundMediaDownloader.class);

    private InboundMediaDownloader() {
    }

    /**
     * Fetches the raw (already-decrypted) bytes for a piece of media. May throw;
     * the downloader retries a throwing source before giving up.
     */
    @FunctionalInterface
    public interface ByteSource {
        byte[] fetch() throws Exception;
    }

    /** A successfully downloaded and typed file on local disk. */
    public record DownloadedMedia(
            Path localPath,
            String storedName,
            String fileName,
            String contentType,
            long fileSize,
            String fileUrl) {

        public boolean isImage() {
            return contentType != null && contentType.startsWith("image/");
        }

        public boolean isVideo() {
            return contentType != null && contentType.startsWith("video/");
        }

        public boolean isAudio() {
            return contentType != null && contentType.startsWith("audio/");
        }
    }

    /** Total fetch attempts (1 initial + retries) before giving up. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long RETRY_INITIAL_DELAY_MS = 300;
    private static final long RETRY_MAX_DELAY_MS = 3000;

    /**
     * Download with the default retry policy and no servable URL. See
     * {@link #download(ByteSource, String, Path, String, String, int, Function)}.
     */
    public static Optional<DownloadedMedia> download(ByteSource source,
                                                     String filenameHint,
                                                     Path targetDir,
                                                     String storedNamePrefix,
                                                     String dedupSeed) {
        return download(source, filenameHint, targetDir, storedNamePrefix, dedupSeed,
                DEFAULT_MAX_ATTEMPTS, null);
    }

    /**
     * Download with a custom attempt count and no servable URL. See
     * {@link #download(ByteSource, String, Path, String, String, int, Function)}.
     */
    public static Optional<DownloadedMedia> download(ByteSource source,
                                                     String filenameHint,
                                                     Path targetDir,
                                                     String storedNamePrefix,
                                                     String dedupSeed,
                                                     int maxAttempts) {
        return download(source, filenameHint, targetDir, storedNamePrefix, dedupSeed, maxAttempts, null);
    }

    /**
     * Download with the default retry policy and a servable-URL builder. See
     * {@link #download(ByteSource, String, Path, String, String, int, Function)}.
     */
    public static Optional<DownloadedMedia> download(ByteSource source,
                                                     String filenameHint,
                                                     Path targetDir,
                                                     String storedNamePrefix,
                                                     String dedupSeed,
                                                     Function<String, String> fileUrlBuilder) {
        return download(source, filenameHint, targetDir, storedNamePrefix, dedupSeed,
                DEFAULT_MAX_ATTEMPTS, fileUrlBuilder);
    }

    /**
     * Fetch the bytes (with retry), detect the real type, and persist the file.
     *
     * @param source          fetches the decrypted bytes; retried on failure
     * @param filenameHint    the real user-supplied filename when known, else
     *                        {@code null}/blank. A name with a meaningful
     *                        extension is kept; a blank, extension-less, or
     *                        {@code .bin} hint is replaced with the sniffed
     *                        extension
     * @param targetDir       directory to write into (created if absent)
     * @param storedNamePrefix short channel tag prefixed to the stored file
     *                        name (e.g. {@code "weixin"})
     * @param dedupSeed       stable string (e.g. the source URL / media key)
     *                        hashed into the stored name so the same media maps
     *                        to the same file
     * @param maxAttempts     total fetch attempts (>= 1)
     * @param fileUrlBuilder  optional mapping from the stored filename to a
     *                        browser-servable URL (e.g.
     *                        {@code name -> "/api/v1/chat/files/" + convId + "/" + name});
     *                        {@code null} leaves {@link DownloadedMedia#fileUrl()}
     *                        null for channels with no serve path
     * @return the stored file, or empty when every attempt failed
     */
    public static Optional<DownloadedMedia> download(ByteSource source,
                                                     String filenameHint,
                                                     Path targetDir,
                                                     String storedNamePrefix,
                                                     String dedupSeed,
                                                     int maxAttempts,
                                                     Function<String, String> fileUrlBuilder) {
        byte[] data = fetchWithRetry(source, Math.max(1, maxAttempts), filenameHint);
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(targetDir);

            MediaTypeSniffer.Sniffed sniff = MediaTypeSniffer.sniff(data);

            // Derive a display name. The hint is authoritative only when the
            // caller passed a real user-supplied filename with a meaningful
            // extension; for media the caller passes null/blank and we
            // synthesize a name from the sniffed type. ".bin" is treated as
            // "no real extension" since it is the universal unknown-binary
            // placeholder. This keeps the contract channel-agnostic — no
            // per-channel sentinel names leak into this shared layer.
            String safeName = sanitize(filenameHint);
            boolean hintIsGeneric = "media".equals(safeName)
                    || !safeName.contains(".")
                    || safeName.toLowerCase().endsWith(".bin");
            String fileName = safeName;
            if (hintIsGeneric && sniff.isKnown()) {
                fileName = stripExtension(safeName) + sniff.extension();
            }

            String seed = (dedupSeed == null || dedupSeed.isBlank()) ? fileName : dedupSeed;
            String hash = md5Short(seed);
            String prefix = (storedNamePrefix == null || storedNamePrefix.isBlank())
                    ? "media" : sanitize(storedNamePrefix);
            String storedName = prefix + "_" + hash + "_" + fileName;

            Path filePath = targetDir.resolve(storedName);
            Files.write(filePath, data);

            // Prefer the sniffed MIME; fall back to extension-based guess only
            // when sniffing was inconclusive.
            String contentType = sniff.isKnown() ? sniff.contentType() : mimeFromExtension(fileName);

            String fileUrl = null;
            if (fileUrlBuilder != null) {
                try {
                    fileUrl = fileUrlBuilder.apply(storedName);
                } catch (Exception e) {
                    log.warn("[media] fileUrl builder failed for {}: {}", storedName, e.getMessage());
                }
            }

            log.info("[media] Inbound media saved: {} ({} bytes, type={}, sniffed={})",
                    filePath, data.length, contentType, sniff.isKnown());
            return Optional.of(new DownloadedMedia(
                    filePath.toAbsolutePath(),
                    storedName,
                    fileName,
                    contentType,
                    data.length,
                    fileUrl));
        } catch (Exception e) {
            log.error("[media] Failed to persist inbound media (hint={}): {}", filenameHint, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private static byte[] fetchWithRetry(ByteSource source, int maxAttempts, String hint) {
        ExponentialBackoff backoff = new ExponentialBackoff(
                RETRY_INITIAL_DELAY_MS, RETRY_MAX_DELAY_MS, 2.0, maxAttempts, 0.2);
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                byte[] data = source.fetch();
                if (data != null && data.length > 0) {
                    return data;
                }
                log.warn("[media] Download attempt {}/{} returned empty (hint={})", attempt, maxAttempts, hint);
            } catch (Exception e) {
                last = e;
                log.warn("[media] Download attempt {}/{} failed (hint={}): {}",
                        attempt, maxAttempts, hint, e.getMessage());
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(backoff.nextDelayMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (last != null) {
            log.error("[media] Download exhausted after {} attempts (hint={}): {}",
                    maxAttempts, hint, last.getMessage());
        }
        return null;
    }

    /** Strip everything except a safe, file-system-friendly character set. */
    private static String sanitize(String name) {
        String raw = (name == null) ? "" : name.trim();
        String safe = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "media" : safe;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String mimeFromExtension(String fileName) {
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "heic", "heif" -> "image/heic";
            case "bmp" -> "image/bmp";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "mp3" -> "audio/mpeg";
            case "amr" -> "audio/amr";
            case "wav" -> "audio/wav";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private static String md5Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
