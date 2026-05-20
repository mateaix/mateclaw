package vip.mate.channel.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.document.GeneratedFileCache;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Channel-agnostic scanner that finds {@code /api/v1/files/generated/{id}}
 * URLs in agent output, looks each id up in {@link GeneratedFileCache},
 * and rewrites the URL so the IM bubble shows a meaningful surface
 * (file name marker or "retry" hint) while collecting the bytes for the
 * adapter to upload as a native attachment.
 *
 * <p>Cache miss has two causes (both surfaced with the same retry hint
 * so the user just resubmits):
 * <ol>
 *   <li>The LLM hallucinated a UUID-shaped string without ever calling
 *       a render tool. {@link GeneratedFileCache#put} logs every real
 *       put, so its absence here is proof the file was never generated
 *       this turn.</li>
 *   <li>The 10-min cache entry expired before the IM client got around
 *       to clicking, or was wiped on JVM restart.</li>
 * </ol>
 * Without this rewrite, IM clients tap a markdown link that returns
 * 404, save the HTML 404 body as the requested file extension, then
 * report "file is corrupted" to support.
 *
 * <p>Originally lived as a private method on {@code WeComChannelAdapter};
 * extracted here so Feishu / DingTalk / future channels share one
 * implementation and one set of log conventions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeneratedFileScrubber {

    private final GeneratedFileCache cache;

    /**
     * One attachment hit produced by {@link #scrub}.
     *
     * @param bytes       raw file content (from cache)
     * @param fileName    original file name (kept for upload + display)
     * @param mimeType    MIME from cache entry — used to decide
     *                    {@code image} vs {@code file} on upload
     * @param mediaType   coarse classification: {@code "image"} when
     *                    {@code mimeType} starts with {@code image/},
     *                    otherwise {@code "file"}
     */
    public record AttachmentHit(byte[] bytes, String fileName, String mimeType, String mediaType) {}

    /**
     * Result of scrubbing one text block.
     *
     * @param rewrittenText same text with each generated-URL replaced
     *                      by either a {@code "📎 filename"} marker
     *                      (cache hit) or a retry warning (cache miss)
     * @param attachments   one entry per cache hit, in document order
     */
    public record ScrubResult(String rewrittenText, List<AttachmentHit> attachments) {}

    /**
     * Scan {@code text} for generated-file URLs and produce a
     * {@link ScrubResult}. Returns the input unchanged (and an empty
     * attachment list) when {@code text} is null/empty or contains no
     * matches.
     */
    public ScrubResult scrub(String text) {
        if (text == null || text.isEmpty()) {
            return new ScrubResult(text, List.of());
        }
        Matcher m = GeneratedFileCache.GENERATED_URL_PATTERN.matcher(text);
        if (!m.find()) {
            return new ScrubResult(text, List.of());
        }
        StringBuilder out = new StringBuilder();
        List<AttachmentHit> hits = new ArrayList<>();
        m.reset();
        while (m.find()) {
            String id = m.group(1);
            GeneratedFileCache.Entry entry = cache.get(id).orElse(null);
            if (entry != null) {
                String mediaType = isImageMime(entry.mimeType()) ? "image" : "file";
                hits.add(new AttachmentHit(entry.bytes(), entry.filename(), entry.mimeType(), mediaType));
                m.appendReplacement(out, Matcher.quoteReplacement("📎 " + entry.filename()));
            } else {
                log.warn("[generated-file-scrubber] cache miss for id={} — likely LLM skipped the render tool and wrote a fake URL", id);
                m.appendReplacement(out, Matcher.quoteReplacement(GeneratedFileCache.MISSING_REFERENCE_NOTICE));
            }
        }
        m.appendTail(out);
        return new ScrubResult(out.toString(), hits);
    }

    private static boolean isImageMime(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }
}
