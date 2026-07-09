package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.PageSearchResult;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Tests for the token-budgeted knowledge-base relevance injection in
 * {@link WikiContextService}.
 */
class WikiContextServiceBudgetTest {

    private WikiKnowledgeBaseService kbService;
    private HybridRetriever hybridRetriever;
    private WikiProperties properties;
    private WikiContextService service;

    @BeforeEach
    void setUp() {
        kbService = Mockito.mock(WikiKnowledgeBaseService.class);
        hybridRetriever = Mockito.mock(HybridRetriever.class);
        WikiPageService pageService = Mockito.mock(WikiPageService.class);
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
}
