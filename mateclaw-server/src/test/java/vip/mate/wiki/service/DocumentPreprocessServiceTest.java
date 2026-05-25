package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.WikiChunkDraft;
import vip.mate.wiki.model.WikiRawMaterialEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-051 PR-1c: pin the preprocessor's metadata extraction so future PR-1c
 * extensions (Tika, smarter chunkers) don't silently drop page numbers or
 * heading breadcrumbs.
 */
class DocumentPreprocessServiceTest {

    private DocumentPreprocessService service;
    private WikiContentNormalizer normalizer;

    /** Single-window chunker: each test asserts at chunk[0]. */
    private static final DocumentPreprocessService.Chunker WHOLE_AS_ONE_CHUNK =
            text -> List.of(new int[]{0, text.length()});

    @BeforeEach
    void setUp() {
        normalizer = new WikiContentNormalizer();
        service = new DocumentPreprocessService(normalizer, new WikiProperties());
    }

    private WikiRawMaterialEntity raw(String type) {
        WikiRawMaterialEntity r = new WikiRawMaterialEntity();
        r.setSourceType(type);
        return r;
    }

    @Test
    @DisplayName("markdown headings produce header_breadcrumb and source_section")
    void markdownHeadingsBecomeBreadcrumb() {
        String text = "# Intro\nWelcome.\n## Setup\nDo this.\n### Linux\nDetails follow.";
        DocumentPreprocessService.Chunker chunker = t -> {
            int linuxIdx = t.indexOf("Details");
            return List.of(new int[]{linuxIdx, t.length()});
        };
        List<WikiChunkDraft> drafts = service.preprocess(raw("markdown"), text, chunker);
        assertEquals(1, drafts.size());
        WikiChunkDraft d = drafts.get(0);
        assertEquals("Intro / Setup / Linux", d.headerBreadcrumb());
        assertEquals("Linux", d.sourceSection());
    }

    @Test
    @DisplayName("PDF page markers map chunk to its enclosing page number")
    void pdfPageMarkers() {
        String text = "--- Page 1 ---\nFirst page body.\n--- Page 2 ---\nSecond page body here.";
        DocumentPreprocessService.Chunker chunker = t -> {
            int second = t.indexOf("Second page body");
            return List.of(new int[]{second, t.length()});
        };
        List<WikiChunkDraft> drafts = service.preprocess(raw("pdf"), text, chunker);
        assertEquals(1, drafts.size());
        assertEquals(2, drafts.get(0).pageNumber());
    }

    @Test
    @DisplayName("token_count uses ceil(charCount / 4) for every chunk")
    void tokenCountHeuristic() {
        String text = "a".repeat(17); // 17 chars → 5 tokens
        List<WikiChunkDraft> drafts = service.preprocess(raw("text"), text, WHOLE_AS_ONE_CHUNK);
        assertEquals(1, drafts.size());
        assertEquals(5, drafts.get(0).tokenCount());
    }

    @Test
    @DisplayName("chunk before any heading has null breadcrumb")
    void noHeadingsAboveChunk() {
        String text = "Plain paragraph with no headings at all.";
        List<WikiChunkDraft> drafts = service.preprocess(raw("text"), text, WHOLE_AS_ONE_CHUNK);
        assertEquals(1, drafts.size());
        assertNull(drafts.get(0).headerBreadcrumb());
        assertNull(drafts.get(0).sourceSection());
        assertNull(drafts.get(0).pageNumber());
    }

    @Test
    @DisplayName("blank input yields no drafts")
    void blankInputIsEmpty() {
        assertTrue(service.preprocess(raw("text"), "", WHOLE_AS_ONE_CHUNK).isEmpty());
        assertTrue(service.preprocess(raw("text"), null, WHOLE_AS_ONE_CHUNK).isEmpty());
    }

    @Test
    @DisplayName("HTML normalization strips nav/footer/script and emits headings on their own lines")
    void htmlNormalizationCleansNoise() {
        String html = "<html><head><script>alert(1)</script><style>.x{}</style></head>" +
                "<body><nav>menu</nav><h1>Title</h1><p>Body para.</p>" +
                "<footer>copy</footer></body></html>";
        String normalized = normalizer.normalize("html", html);
        assertFalse(normalized.contains("alert"), "<script> content must be stripped");
        assertFalse(normalized.contains(".x{}"), "<style> content must be stripped");
        assertFalse(normalized.contains("menu"), "<nav> text must be stripped");
        assertFalse(normalized.contains("copy"), "<footer> text must be stripped");
        assertTrue(normalized.contains("# Title"), "h1 must be marked with heading prefix");
        assertTrue(normalized.contains("Body para."), "body content must survive");
    }

    @Test
    @DisplayName("PPTX slide markers count as page numbers")
    void slideMarkersAreTreatedLikePages() {
        String text = "--- Slide 4 ---\nSlide 4 body content here.";
        DocumentPreprocessService.Chunker chunker = t -> {
            int b = t.indexOf("Slide 4 body");
            return List.of(new int[]{b, t.length()});
        };
        List<WikiChunkDraft> drafts = service.preprocess(raw("pptx"), text, chunker);
        assertEquals(4, drafts.get(0).pageNumber());
    }
}
