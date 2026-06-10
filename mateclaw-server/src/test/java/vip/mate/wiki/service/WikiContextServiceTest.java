package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.PageSearchResult;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-turn relevant-wiki injection guards in
 * {@link WikiContextService#buildRelevantContext(Long, String)}.
 *
 * <p>Covers two gates:
 * <ul>
 *   <li>Continuation / too-short query gate — never reaches the retriever
 *   so a "继续" reply doesn't pull whichever pages dominate the index.</li>
 *   <li>Relative-score floor — strips tail hits that score far below the
 *   top hit so a single strong match isn't accompanied by 4 weak ones.</li>
 * </ul>
 */
class WikiContextServiceTest {

    private WikiKnowledgeBaseService kbService;
    private WikiPageService pageService;
    private HybridRetriever hybridRetriever;
    private WikiProperties properties;
    private WikiContextService service;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        pageService = mock(WikiPageService.class);
        hybridRetriever = mock(HybridRetriever.class);
        properties = new WikiProperties();

        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(42L);
        when(kbService.resolvePrimaryKb(any())).thenReturn(kb);

        service = new WikiContextService(kbService, pageService, hybridRetriever, properties);
    }

    @Test
    @DisplayName("'继续' is treated as continuation — retriever never called, no context emitted")
    void skipsContinuationToken() {
        String result = service.buildRelevantContext(1L, "继续");

        assertThat(result).isEmpty();
        verify(hybridRetriever, never()).search(any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Sub-min-length queries are skipped (default min=3)")
    void skipsTooShortQuery() {
        assertThat(service.buildRelevantContext(1L, "嗯")).isEmpty();
        assertThat(service.buildRelevantContext(1L, "OK")).isEmpty();
        verify(hybridRetriever, never()).search(any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Continuation tokens are matched case-insensitively")
    void continuationTokensAreCaseInsensitive() {
        assertThat(service.buildRelevantContext(1L, "Continue")).isEmpty();
        assertThat(service.buildRelevantContext(1L, "GO ON")).isEmpty();
        verify(hybridRetriever, never()).search(any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Genuine queries still reach the retriever")
    void substantiveQueryHitsRetriever() {
        when(hybridRetriever.search(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(hit("postgres-migration", 0.04, "Postgres migration runbook")));

        String result = service.buildRelevantContext(1L,
                "请用多智能体并行评估是否要把现有 H2 文件存储替换为 Postgres + pgvector");

        assertThat(result).contains("[[postgres-migration]]");
        verify(hybridRetriever, times(1)).search(any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Tail hits below top*ratio are dropped from the prompt")
    void dropsTailHitsBelowRelativeScoreFloor() {
        // top=0.04, ratio=0.5 → floor=0.02. The 0.005 hit must be removed.
        when(hybridRetriever.search(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        hit("strong-match", 0.04, "Strong match"),
                        hit("medium-match", 0.022, "Medium match"),
                        hit("weak-match", 0.005, "Weak tail noise")));

        String result = service.buildRelevantContext(1L, "this is a real question about pgvector tuning");

        assertThat(result)
                .contains("[[strong-match]]")
                .contains("[[medium-match]]")
                .doesNotContain("[[weak-match]]");
    }

    @Test
    @DisplayName("Setting min-relative-score to 0 disables the floor")
    void zeroRatioDisablesScoreFloor() {
        properties.setRelevantContextMinRelativeScore(0d);
        when(hybridRetriever.search(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        hit("strong-match", 0.04, "Strong match"),
                        hit("very-weak", 0.0001, "Almost noise")));

        String result = service.buildRelevantContext(1L, "this is a real question about pgvector tuning");

        assertThat(result).contains("[[very-weak]]");
    }

    private static PageSearchResult hit(String slug, double score, String snippet) {
        return PageSearchResult.of(slug, slug, snippet, snippet,
                List.of("keyword"), null, score);
    }

    // ==================== buildWikiContext heading + hint format ====================
    //
    // These tests lock in the unambiguous heading layout: heading text after
    // `### ` MUST equal the KB name verbatim and nothing more, so the LLM
    // can safely copy it into the `kbName` tool argument. The previous form
    // "### {name} — {description} ({N} pages)" let the LLM paste the entire
    // row and break findByName's exact-match lookup. The multi-KB hint must
    // also call out kbId as the disambiguator for duplicate names.

    private static WikiKnowledgeBaseEntity kbWithName(long id, String name, String description, Long agentId) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(id);
        kb.setName(name);
        kb.setDescription(description);
        kb.setAgentId(agentId);
        kb.setPageCount(0);
        return kb;
    }

    private static WikiPageEntity simplePage(String slug, String title, String summary) {
        WikiPageEntity p = new WikiPageEntity();
        p.setSlug(slug);
        p.setTitle(title);
        p.setSummary(summary);
        p.setPageType("user");
        return p;
    }

    @Test
    @DisplayName("buildWikiContext heading is JUST the KB name — no description, no page count")
    void buildWikiContextHeadingIsBareName() {
        WikiKnowledgeBaseEntity kb = kbWithName(100L, "QA-Bug-Test KB",
                "A KB created via UI E2E test to surface wiki bugs", null);
        when(kbService.listByAgentId(1L)).thenReturn(List.of(kb));
        when(pageService.listSummaries(100L)).thenReturn(List.of(
                simplePage("mateclaw", "MateClaw", "Entry page")));

        String out = service.buildWikiContext(1L);

        // Heading line is exact — pasting this into kbName must work without trim/strip.
        assertThat(out).contains("### QA-Bug-Test KB\n");
        // Description and page count live on the next line, not in the heading.
        assertThat(out).doesNotContain("### QA-Bug-Test KB —");
        assertThat(out).doesNotContain("### QA-Bug-Test KB (");
        assertThat(out).contains("1 pages — A KB created via UI E2E test");
    }

    @Test
    @DisplayName("buildWikiContext multi-KB hint mentions kbName + kbId + wiki_list_kbs")
    void buildWikiContextMultiKbHint() {
        when(kbService.listByAgentId(1L)).thenReturn(List.of(
                kbWithName(100L, "Alpha", null, null),
                kbWithName(200L, "Beta", null, null)));
        when(pageService.listSummaries(100L)).thenReturn(List.of(simplePage("a", "A", null)));
        when(pageService.listSummaries(200L)).thenReturn(List.of(simplePage("b", "B", null)));

        String out = service.buildWikiContext(1L);

        // Hint must point the LLM at the right argument and at the
        // disambiguator for duplicate names.
        assertThat(out)
                .contains("kbName")
                .contains("kbId")
                .contains("wiki_list_kbs")
                .contains("EXACT text after `### `");
    }

    @Test
    @DisplayName("buildWikiContext single-KB output omits the multi-KB hint")
    void buildWikiContextSingleKbSkipsHint() {
        WikiKnowledgeBaseEntity kb = kbWithName(100L, "Solo", null, null);
        when(kbService.listByAgentId(1L)).thenReturn(List.of(kb));
        when(pageService.listSummaries(100L)).thenReturn(List.of(simplePage("a", "A", null)));

        String out = service.buildWikiContext(1L);

        // The "multiple knowledge bases" hint is wasted prompt budget when
        // there's only one KB; it must stay off.
        assertThat(out).doesNotContain("Multiple knowledge bases visible");
    }
}
