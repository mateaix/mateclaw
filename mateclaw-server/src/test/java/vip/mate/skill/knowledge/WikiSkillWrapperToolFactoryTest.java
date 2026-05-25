package vip.mate.skill.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * RFC-090 §14.4 — locks in wrapper factory contract for type=knowledge.
 *
 * <ol>
 *   <li>wrapperNames produces the canonical {@code kb_<slug>_*} triple</li>
 *   <li>resolveKbId tries numeric id first, then name match</li>
 *   <li>buildWrappers returns 3 callbacks (search / read / list) with
 *       a captured kbId — the LLM never sees the kbId in the schema</li>
 *   <li>The search wrapper delegates to {@code HybridRetriever.search}
 *       and trackReference fires per result</li>
 *   <li>read wrapper truncates content via maxChars</li>
 *   <li>list wrapper hides system pages (RFC-051 PR-2 parity)</li>
 * </ol>
 */
class WikiSkillWrapperToolFactoryTest {

    private WikiKnowledgeBaseService kbService;
    private WikiPageService pageService;
    private HybridRetriever retriever;
    private WikiSkillWrapperToolFactory factory;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        pageService = mock(WikiPageService.class);
        retriever = mock(HybridRetriever.class);
        factory = new WikiSkillWrapperToolFactory(
                kbService, pageService, retriever, new ObjectMapper());
    }

    @Test
    @DisplayName("wrapperNames returns search/read/list triple with sanitized slug")
    void wrapperNamesShape() {
        SkillManifest m = SkillManifest.builder()
                .name("TCM-Classics")
                .knowledge(SkillManifest.KnowledgeBinding.builder().bindKb("ignored").build())
                .build();
        List<String> names = factory.wrapperNames(m);
        assertEquals(List.of("kb_tcm_classics_search", "kb_tcm_classics_read", "kb_tcm_classics_list"), names);
    }

    @Test
    @DisplayName("resolveKbId tries numeric id parse first")
    void resolveKbIdNumeric() {
        assertEquals(42L, factory.resolveKbId("42"));
        verifyNoInteractions(kbService);
    }

    @Test
    @DisplayName("resolveKbId falls back to name match (case-insensitive)")
    void resolveKbIdByName() {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(7L);
        kb.setName("TCM Classics");
        when(kbService.listAll()).thenReturn(List.of(kb));
        assertEquals(7L, factory.resolveKbId("tcm classics"));
    }

    @Test
    @DisplayName("resolveKbId returns null for missing slug + missing name")
    void resolveKbIdMissing() {
        when(kbService.listAll()).thenReturn(List.of());
        assertNull(factory.resolveKbId("nope"));
    }

    @Test
    @DisplayName("buildWrappers returns empty when manifest has no knowledge binding")
    void buildWrappersNoBinding() {
        SkillManifest m = SkillManifest.builder().name("foo").build();
        assertTrue(factory.buildWrappers(m, 1L).isEmpty());
    }

    @Test
    @DisplayName("buildWrappers returns 3 callbacks: search / read / list")
    void buildWrappersThreeCallbacks() {
        SkillManifest m = SkillManifest.builder()
                .name("tcm")
                .knowledge(SkillManifest.KnowledgeBinding.builder().bindKb("tcm").build())
                .build();
        List<ToolCallback> wrappers = factory.buildWrappers(m, 99L);
        assertEquals(3, wrappers.size());
        assertEquals("kb_tcm_search", wrappers.get(0).getToolDefinition().name());
        assertEquals("kb_tcm_read", wrappers.get(1).getToolDefinition().name());
        assertEquals("kb_tcm_list", wrappers.get(2).getToolDefinition().name());
    }

    @Test
    @DisplayName("search wrapper passes captured kbId to HybridRetriever and tracks references")
    void searchDelegatesAndTracks() {
        SkillManifest m = SkillManifest.builder()
                .name("tcm")
                .knowledge(SkillManifest.KnowledgeBinding.builder().bindKb("tcm").build())
                .build();
        when(retriever.search(eq(99L), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(vip.mate.wiki.dto.PageSearchResult.of(
                        "shanghan-lun", "伤寒论", "summary", "snippet", List.of(), "matched", 0.9)));
        ToolCallback search = factory.buildWrappers(m, 99L).get(0);
        String out = search.call("{\"query\":\"小柴胡\",\"mode\":\"hybrid\",\"topK\":3}");
        assertTrue(out.contains("\"kbId\":99"));
        assertTrue(out.contains("shanghan-lun"));
        verify(retriever).search(99L, "小柴胡", "hybrid", 3);
        verify(pageService).trackReference(99L, "shanghan-lun");
    }

    @Test
    @DisplayName("search wrapper rejects empty query with JSON error")
    void searchRejectsEmptyQuery() {
        SkillManifest m = SkillManifest.builder()
                .name("tcm")
                .knowledge(SkillManifest.KnowledgeBinding.builder().bindKb("tcm").build())
                .build();
        ToolCallback search = factory.buildWrappers(m, 99L).get(0);
        String out = search.call("{}");
        assertTrue(out.contains("\"error\""));
        verifyNoInteractions(retriever);
    }

    @Test
    @DisplayName("read wrapper truncates content to maxChars")
    void readTruncatesContent() {
        WikiPageEntity page = new WikiPageEntity();
        page.setSlug("a");
        page.setTitle("A");
        page.setVersion(2);
        // Build a long content; the wrapper should chop to maxChars + "...(truncated)" suffix.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) body.append("line ").append(i).append('\n');
        page.setContent(body.toString());
        when(pageService.getBySlug(99L, "a")).thenReturn(page);

        SkillManifest m = SkillManifest.builder()
                .name("tcm")
                .knowledge(SkillManifest.KnowledgeBinding.builder().bindKb("tcm").build())
                .build();
        ToolCallback read = factory.buildWrappers(m, 99L).get(1);
        String out = read.call("{\"slug\":\"a\",\"maxChars\":40}");
        // Truncation suffix is "...(truncated)" appended to the content
        // body, then JSON-escaped. Look for the inline marker rather
        // than a top-level field — wrapper doesn't surface a flag.
        assertTrue(out.contains("(truncated)"),
                "expected truncation marker in content; got: " + out);
        verify(pageService).trackReference(99L, "a");
    }

    @Test
    @DisplayName("list wrapper filters out system pages")
    void listFiltersSystemPages() {
        WikiPageEntity normal = new WikiPageEntity();
        normal.setSlug("a"); normal.setTitle("A"); normal.setSummary("aa");
        normal.setPageType("page");
        WikiPageEntity system = new WikiPageEntity();
        system.setSlug("overview"); system.setTitle("Overview"); system.setSummary("ov");
        system.setPageType("system");
        when(pageService.listSummaries(99L)).thenReturn(List.of(normal, system));

        SkillManifest m = SkillManifest.builder()
                .name("tcm")
                .knowledge(SkillManifest.KnowledgeBinding.builder().bindKb("tcm").build())
                .build();
        ToolCallback list = factory.buildWrappers(m, 99L).get(2);
        String out = list.call("{}");
        assertTrue(out.contains("\"a\""));
        assertFalse(out.contains("\"overview\""), "system pages should be filtered out");
    }
}
