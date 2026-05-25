package vip.mate.tool.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the cache-side scrubber that powers the server-wide fake-URL guard.
 *
 * <p>Without this guard, an LLM-hallucinated {@code /api/v1/files/generated/{id}}
 * URL surfaces verbatim to every channel (Web, Slack, DingTalk, Telegram, …),
 * users tap it, and the IM client saves the resulting 404 HTML body as a
 * {@code .docx} which they then report as a "corrupted file". These tests
 * pin the cache-vs-text contract so future callers (FinalAnswerNode,
 * channel adapters) get a single, consistent behaviour.
 */
class GeneratedFileCacheScrubTest {

    private GeneratedFileCache cache;

    @BeforeEach
    void setUp() {
        cache = new GeneratedFileCache();
    }

    @Test
    @DisplayName("text without any generated-URL is returned unchanged (cheap fast path)")
    void noUrlReturnsUnchanged() {
        String text = "这是一段普通的回答，没有任何文件链接。";
        assertSame(text, cache.scrubMissingReferences(text),
                "scrub must short-circuit when no URL pattern is found");
    }

    @Test
    @DisplayName("null and empty input pass through")
    void nullEmptyPassThrough() {
        assertNull(cache.scrubMissingReferences(null));
        assertEquals("", cache.scrubMissingReferences(""));
    }

    @Test
    @DisplayName("hallucinated URL whose id is not in the cache → replaced with warning")
    void unknownIdReplacedWithWarning() {
        // The LLM emitted a UUID-shaped string but never called a render
        // tool, so nothing was ever inserted into the cache.
        String text = "您的文档已生成: /api/v1/files/generated/a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        String scrubbed = cache.scrubMissingReferences(text);
        assertTrue(scrubbed.contains(GeneratedFileCache.MISSING_REFERENCE_NOTICE),
                "missing id should be replaced with the user-visible notice; got: " + scrubbed);
        assertFalse(scrubbed.contains("/api/v1/files/generated/"),
                "the broken URL must not survive in the scrubbed text; got: " + scrubbed);
    }

    @Test
    @DisplayName("real cached URL → left intact for downstream channel adapters to rewrite")
    void liveIdLeftIntact() {
        // Genuine render-tool output: bytes are in the cache, id is real.
        String id = cache.put("hello".getBytes(), "report.pdf", "application/pdf");
        String text = "下载: /api/v1/files/generated/" + id;
        String scrubbed = cache.scrubMissingReferences(text);
        assertEquals(text, scrubbed,
                "live URLs must pass through verbatim so channel adapters can still rewrite them");
    }

    @Test
    @DisplayName("mix of one real + one fake URL — only the fake one is scrubbed")
    void mixedRealAndFake() {
        String realId = cache.put("real-bytes".getBytes(), "real.pdf", "application/pdf");
        String fakeId = "00000000-0000-0000-0000-000000000000";
        String text = "真实: /api/v1/files/generated/" + realId
                + " 伪造: /api/v1/files/generated/" + fakeId;
        String scrubbed = cache.scrubMissingReferences(text);
        assertTrue(scrubbed.contains("/api/v1/files/generated/" + realId),
                "real URL must survive; got: " + scrubbed);
        assertFalse(scrubbed.contains(fakeId),
                "fake URL must not survive; got: " + scrubbed);
        assertTrue(scrubbed.contains(GeneratedFileCache.MISSING_REFERENCE_NOTICE));
    }

    @Test
    @DisplayName("two fake URLs in same answer both get individual warnings")
    void twoFakesBothScrubbed() {
        String text = "/api/v1/files/generated/fake-1 then /api/v1/files/generated/fake-2";
        String scrubbed = cache.scrubMissingReferences(text);
        assertFalse(scrubbed.contains("fake-1"));
        assertFalse(scrubbed.contains("fake-2"));
        // Two fakes → notice should appear twice (each occurrence replaced individually).
        int firstHit = scrubbed.indexOf(GeneratedFileCache.MISSING_REFERENCE_NOTICE);
        int secondHit = scrubbed.indexOf(GeneratedFileCache.MISSING_REFERENCE_NOTICE, firstHit + 1);
        assertTrue(firstHit >= 0 && secondHit > firstHit,
                "both fakes should be replaced; got: " + scrubbed);
    }

    @Test
    @DisplayName("URL pattern is package-shared so channel adapters and graph nodes match identically")
    void patternIsExposed() {
        // A regression here would mean the graph-side guard and the
        // channel-side sniffer scan with different regexes — easy way to
        // ship divergent behaviour. Pin the pattern so both call sites
        // import the same constant.
        assertNotNull(GeneratedFileCache.GENERATED_URL_PATTERN);
        assertTrue(GeneratedFileCache.GENERATED_URL_PATTERN
                .matcher("/api/v1/files/generated/abc-123").find());
    }
}
