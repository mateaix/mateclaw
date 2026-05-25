package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.PageSearchResult;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

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
}
