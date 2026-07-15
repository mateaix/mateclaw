package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.PageSearchResult;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Tests for the token-budgeted knowledge-base injection in
 * {@link WikiContextService} — both the per-query relevance block
 * ({@code buildRelevantContext}) and the system-prompt page listing
 * ({@code buildWikiContext}).
 */
class WikiContextServiceBudgetTest {

    private WikiKnowledgeBaseService kbService;
    private WikiPageService pageService;
    private HybridRetriever hybridRetriever;
    private WikiProperties properties;
    private WikiContextService service;

    @BeforeEach
    void setUp() {
        kbService = Mockito.mock(WikiKnowledgeBaseService.class);
        hybridRetriever = Mockito.mock(HybridRetriever.class);
        pageService = Mockito.mock(WikiPageService.class);
        properties = new WikiProperties();
        service = new WikiContextService(kbService, pageService, hybridRetriever, properties);

        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(7L);
        Mockito.when(kbService.resolvePrimaryKb(anyLong())).thenReturn(kb);
    }

    private void stubHits(int count, int excerptChars) {
        List<PageSearchResult> hits = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            hits.add(PageSearchResult.of("page-" + i, "页面" + i, null,
                    "摘".repeat(excerptChars), List.of("keyword"), null, 1.0));
        }
        Mockito.when(hybridRetriever.search(any(), anyString(), anyString(), anyInt()))
                .thenReturn(hits);
    }

    @Test
    @DisplayName("null budget keeps the chars-only behavior — all hits injected")
    void nullBudgetKeepsAllHits() {
        stubHits(3, 200);
        String result = service.buildRelevantContext(1L, "如何配置数据库连接", null);
        assertTrue(result.contains("page-0"));
        assertTrue(result.contains("page-2"));
    }

    @Test
    @DisplayName("token budget cuts the tail hits and appends the search hint")
    void budgetCutsTailHits() {
        stubHits(3, 400); // each entry ≈ 400+ tokens (CJK)
        String result = service.buildRelevantContext(1L, "如何配置数据库连接", 600);
        assertTrue(result.contains("page-0"));
        assertFalse(result.contains("page-2"));
        assertTrue(result.contains("use wiki_search_pages for more"));
    }

    @Test
    @DisplayName("budget too small for even one entry → injection skipped entirely")
    void tinyBudgetSkipsInjection() {
        stubHits(3, 400);
        String result = service.buildRelevantContext(1L, "如何配置数据库连接", 50);
        assertEquals("", result);
    }

    @Test
    @DisplayName("zero or negative budget short-circuits without retrieval")
    void zeroBudgetShortCircuits() {
        String result = service.buildRelevantContext(1L, "如何配置数据库连接", 0);
        assertEquals("", result);
        Mockito.verifyNoInteractions(hybridRetriever);
    }

    // ---- buildWikiContext (system-prompt page listing) budget (issue #521) ----

    private void stubKbWithPages(int pageCount) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(7L);
        kb.setName("产品知识库");
        Mockito.when(kbService.listByAgentId(anyLong())).thenReturn(List.of(kb));

        List<WikiPageEntity> pages = new java.util.ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            WikiPageEntity p = new WikiPageEntity();
            p.setSlug("page-" + i);
            p.setTitle("知识库页面标题" + i);
            pages.add(p);
        }
        Mockito.when(pageService.listSummaries(anyLong())).thenReturn(pages);
    }

    @Test
    @DisplayName("null budget lists all pages (chars-only legacy behavior)")
    void wikiContextNullBudgetListsAll() {
        stubKbWithPages(50);
        String result = service.buildWikiContext(1L, null);
        assertTrue(result.contains("page-0"));
        assertTrue(result.contains("page-49"));
        assertFalse(result.contains("... and more"));
    }

    @Test
    @DisplayName("token budget truncates the page listing and appends the search hint")
    void wikiContextBudgetTruncates() {
        stubKbWithPages(50);
        // Each compact line ("- page-N: 知识库页面标题N\n") is ~10+ CJK tokens; a
        // small budget must cut the listing well before all 50 pages.
        String result = service.buildWikiContext(1L, 80);
        assertTrue(result.contains("page-0"));
        assertFalse(result.contains("page-49"));
        assertTrue(result.contains("... and more (use wiki_list_pages to see all)"));
    }

    @Test
    @DisplayName("zero or negative budget skips the wiki block entirely")
    void wikiContextZeroBudgetSkips() {
        String result = service.buildWikiContext(1L, 0);
        assertEquals("", result);
        Mockito.verifyNoInteractions(kbService);
        Mockito.verifyNoInteractions(pageService);
    }
}
