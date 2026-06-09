package vip.mate.tool.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the download-URL builder that backs every tool-generated file link.
 *
 * <p>The link must be usable once it leaves the web UI — echoed as plain text,
 * copied, or delivered to a channel without a dedicated attachment rewriter. So
 * when {@code mateclaw.server.public-base-url} is set the URL is absolute, and
 * the shared scrub pattern must match both the relative and absolute forms so
 * downstream guards/scrubbers stay consistent.
 */
class GeneratedFileUrlTest {

    private GeneratedFileCache cache;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        cache = new GeneratedFileCache(tempDir);
    }

    @Test
    @DisplayName("no base configured and no bound request → relative path")
    void relativeWhenUnconfigured() {
        // No HTTP request is bound on the test thread, so the resolver falls
        // back to a relative path (the web UI resolves it against its origin).
        String url = cache.downloadUrl("abc-123");
        assertEquals("/api/v1/files/generated/abc-123", url);
    }

    @Test
    @DisplayName("configured base-url → absolute link, trailing slash trimmed")
    void absoluteWhenConfigured() {
        ReflectionTestUtils.setField(cache, "publicBaseUrl", "https://mateclaw.example.com/");
        String url = cache.downloadUrl("abc-123");
        assertEquals("https://mateclaw.example.com/api/v1/files/generated/abc-123", url);
    }

    @Test
    @DisplayName("blank base-url is treated as unconfigured")
    void blankBaseIgnored() {
        ReflectionTestUtils.setField(cache, "publicBaseUrl", "   ");
        assertEquals("/api/v1/files/generated/abc-123", cache.downloadUrl("abc-123"));
    }

    @Test
    @DisplayName("ToolContext origin baseUrl → absolute link (covers async/streaming threads)")
    void absoluteFromToolContext() {
        // No config and no bound request, but the ChatOrigin carries a base URL
        // captured on the controller thread — this is the streaming path.
        ToolContext ctx = vip.mate.agent.context.ChatOrigin
                .web("c1", "user", null, null, "http://host:18088")
                .toToolContext();
        assertEquals("http://host:18088/api/v1/files/generated/abc-123",
                cache.downloadUrl("abc-123", ctx));
    }

    @Test
    @DisplayName("configured base-url overrides the ToolContext origin baseUrl")
    void configWinsOverToolContext() {
        ReflectionTestUtils.setField(cache, "publicBaseUrl", "https://public.example.com");
        ToolContext ctx = vip.mate.agent.context.ChatOrigin
                .web("c1", "user", null, null, "http://internal:18088")
                .toToolContext();
        assertEquals("https://public.example.com/api/v1/files/generated/abc-123",
                cache.downloadUrl("abc-123", ctx));
    }

    @Test
    @DisplayName("shared pattern matches an absolute URL and captures the bare id")
    void patternMatchesAbsolute() {
        Matcher m = GeneratedFileCache.GENERATED_URL_PATTERN
                .matcher("see https://mateclaw.example.com/api/v1/files/generated/xy-9 now");
        assertTrue(m.find());
        assertEquals("xy-9", m.group(1), "id group must exclude the scheme://host prefix");
        assertEquals("https://mateclaw.example.com/api/v1/files/generated/xy-9", m.group(0),
                "full match must include the host so scrubbers replace the whole URL");
    }

    @Test
    @DisplayName("scrub of a fake absolute URL leaves no dangling host fragment")
    void scrubAbsoluteFakeLeavesNoHost() {
        String text = "下载: https://mateclaw.example.com/api/v1/files/generated/"
                + "00000000-0000-0000-0000-000000000000";
        String scrubbed = cache.scrubMissingReferences(text);
        assertFalse(scrubbed.contains("/api/v1/files/generated/"));
        assertFalse(scrubbed.contains("mateclaw.example.com"),
                "the absolute URL's host must not survive a scrub; got: " + scrubbed);
        assertTrue(scrubbed.contains(GeneratedFileCache.MISSING_REFERENCE_NOTICE));
    }

    @Test
    @DisplayName("scrub of a live absolute URL passes through verbatim for channel rewrite")
    void scrubAbsoluteLivePassesThrough() {
        String id = cache.put("hi".getBytes(), "report.pdf", "application/pdf");
        String text = "下载: https://mateclaw.example.com/api/v1/files/generated/" + id;
        assertEquals(text, cache.scrubMissingReferences(text));
    }
}
