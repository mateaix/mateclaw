package vip.mate.channel.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.document.GeneratedFileCache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pin the cache-hit vs. cache-miss rewrite contract.
 *
 * <p>The scrubber rewrites differently per outcome because the user
 * experience differs:
 * <ul>
 *   <li>Cache hit → upload as native attachment; bubble text shows
 *       "📎 filename" so the link doesn't dangle.</li>
 *   <li>Cache miss (LLM hallucinated the URL OR the 10 min TTL
 *       expired) → bubble text shows
 *       {@link GeneratedFileCache#MISSING_REFERENCE_NOTICE} so the
 *       user knows to retry the request rather than tap a 404.</li>
 * </ul>
 */
class GeneratedFileScrubberTest {

    @Test
    @DisplayName("text without any generated URL is returned unchanged")
    void noMatchesReturnInput() {
        GeneratedFileScrubber scrubber = new GeneratedFileScrubber(new GeneratedFileCache());
        String text = "hello world\nnothing to see here";
        GeneratedFileScrubber.ScrubResult r = scrubber.scrub(text);
        assertSame(text, r.rewrittenText());
        assertEquals(0, r.attachments().size());
    }

    @Test
    @DisplayName("cache hit replaces URL with file-name marker and queues bytes for upload")
    void cacheHitProducesAttachment() {
        GeneratedFileCache cache = new GeneratedFileCache();
        byte[] bytes = "fake-pdf".getBytes();
        String id = cache.put(bytes, "report.pdf", "application/pdf");

        GeneratedFileScrubber scrubber = new GeneratedFileScrubber(cache);
        String text = "See attached: /api/v1/files/generated/" + id + " for details.";
        GeneratedFileScrubber.ScrubResult r = scrubber.scrub(text);

        assertTrue(r.rewrittenText().contains("📎 report.pdf"),
                "marker should appear; got: " + r.rewrittenText());
        assertEquals(1, r.attachments().size());
        GeneratedFileScrubber.AttachmentHit hit = r.attachments().get(0);
        assertEquals("report.pdf", hit.fileName());
        assertEquals("file", hit.mediaType());
        assertSame(bytes, hit.bytes());
    }

    @Test
    @DisplayName("cache hit with image MIME classifies as image media type")
    void cacheHitImageMime() {
        GeneratedFileCache cache = new GeneratedFileCache();
        String id = cache.put(new byte[]{1, 2, 3}, "screenshot.png", "image/png");

        GeneratedFileScrubber scrubber = new GeneratedFileScrubber(cache);
        GeneratedFileScrubber.ScrubResult r = scrubber.scrub("look: /api/v1/files/generated/" + id);
        assertEquals(1, r.attachments().size());
        assertEquals("image", r.attachments().get(0).mediaType());
    }

    @Test
    @DisplayName("cache miss replaces URL with retry hint and produces no attachment")
    void cacheMissProducesRetryHint() {
        GeneratedFileScrubber scrubber = new GeneratedFileScrubber(new GeneratedFileCache());
        // Random UUID-shaped string the LLM might hallucinate
        String text = "click /api/v1/files/generated/00000000-0000-4000-8000-000000000000 to get it";
        GeneratedFileScrubber.ScrubResult r = scrubber.scrub(text);
        assertEquals(0, r.attachments().size());
        assertTrue(r.rewrittenText().contains(GeneratedFileCache.MISSING_REFERENCE_NOTICE),
                "miss should swap in MISSING_REFERENCE_NOTICE; got: " + r.rewrittenText());
    }

    @Test
    @DisplayName("multiple URLs in one text produce hits in document order")
    void multipleHitsOrdered() {
        GeneratedFileCache cache = new GeneratedFileCache();
        String idA = cache.put(new byte[]{1}, "a.pdf", "application/pdf");
        String idB = cache.put(new byte[]{2}, "b.png", "image/png");

        GeneratedFileScrubber scrubber = new GeneratedFileScrubber(cache);
        String text = "first: /api/v1/files/generated/" + idA
                + " then: /api/v1/files/generated/" + idB + ".";
        GeneratedFileScrubber.ScrubResult r = scrubber.scrub(text);
        assertEquals(2, r.attachments().size());
        assertEquals("a.pdf", r.attachments().get(0).fileName());
        assertEquals("b.png", r.attachments().get(1).fileName());
    }
}
