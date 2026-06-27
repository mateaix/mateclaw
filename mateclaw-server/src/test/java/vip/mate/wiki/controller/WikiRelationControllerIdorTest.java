package vip.mate.wiki.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.dto.PageSearchResult;
import vip.mate.wiki.job.WikiProcessingJobService;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.repository.WikiProcessingJobMapper;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiEmbeddingService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiRawMaterialService;
import vip.mate.wiki.service.WikiRelationService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the IDOR guard added for ISSUE #438: {@link WikiRelationController}
 * endpoints must reject requests whose target KB belongs to a different
 * workspace than the caller's {@code X-Workspace-Id} header.
 *
 * <p>These are unit-level checks of the {@code verifyKBWorkspace} /
 * {@code verifyRawWorkspace} / {@code verifyChunkWorkspace} helpers — the
 * same cross-check pattern that closed the WebChat approval IDOR (#415).
 * The {@code @RequireWorkspaceRole} annotation layer is validated separately
 * via the interceptor; here we assert the resource-ownership guard.
 */
class WikiRelationControllerIdorTest {

    private WikiKnowledgeBaseService kbService;
    private WikiRawMaterialService rawService;
    private WikiChunkMapper chunkMapper;
    private WikiProcessingJobMapper jobMapper;
    private HybridRetriever hybridRetriever;
    private WikiRelationController controller;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        rawService = mock(WikiRawMaterialService.class);
        chunkMapper = mock(WikiChunkMapper.class);
        jobMapper = mock(WikiProcessingJobMapper.class);
        hybridRetriever = mock(HybridRetriever.class);
        when(hybridRetriever.search(anyLong(), anyString(), anyString(), anyInt()))
                .thenReturn(List.<PageSearchResult>of());
        controller = new WikiRelationController(
                mock(WikiRelationService.class),
                mock(WikiProcessingJobService.class),
                jobMapper,
                mock(WikiPageService.class),
                mock(WikiPageCitationMapper.class),
                hybridRetriever,
                mock(ApplicationEventPublisher.class),
                new ObjectMapper(),
                mock(WikiEmbeddingService.class),
                kbService,
                rawService,
                chunkMapper);
    }

    // ---------------- kbId endpoints ----------------

    @Test
    @DisplayName("search-preview in the caller's workspace succeeds")
    void searchPreviewSameWorkspaceAllowed() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 1L));

        assertThatCode(() ->
                controller.searchPreview(10L, Map.of("query", "x"), 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("search-preview on another workspace's KB → 403")
    void searchPreviewCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L)); // KB belongs to ws 2

        assertThatThrownBy(() ->
                controller.searchPreview(10L, Map.of("query", "x"), 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("工作区");
    }

    @Test
    @DisplayName("stats on another workspace's KB → 403")
    void statsCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.kbStats(10L, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("enrich (member-level write) on another workspace's KB → 403")
    void enrichCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.enrichPage(10L, "some-slug", 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("repair on another workspace's KB → 403")
    void repairCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.repairPage(10L, "some-slug", 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("unknown kbId → 404 (does not leak existence via workspace mismatch)")
    void unknownKbReturns404() {
        when(kbService.getById(999L)).thenReturn(null);

        assertThatThrownBy(() -> controller.kbStats(999L, 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    // ---------------- getJobs rawId cross-KB filter ----------------

    @Test
    @DisplayName("getJobs: rawId belonging to the same KB is returned")
    void getJobsRawIdSameKbReturned() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 1L));
        WikiProcessingJobEntity job = new WikiProcessingJobEntity();
        job.setId(1L);
        job.setKbId(10L);          // same KB as the path → allowed
        when(jobMapper.findLatestByRawId(50L)).thenReturn(Optional.of(job));

        List<?> result = controller.getJobs(10L, 50L, 1L);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("getJobs: rawId pointing at another KB's job is filtered out")
    void getJobsRawIdCrossKbFiltered() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 1L));  // caller's KB
        WikiProcessingJobEntity foreignJob = new WikiProcessingJobEntity();
        foreignJob.setId(2L);
        foreignJob.setKbId(99L);     // job belongs to a different KB → dropped
        when(jobMapper.findLatestByRawId(50L)).thenReturn(Optional.of(foreignJob));

        List<?> result = controller.getJobs(10L, 50L, 1L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null X-Workspace-Id header falls back to default ws=1")
    void missingHeaderFallsBackToDefaultWorkspace() {
        // KB in default workspace (id=1), no header → should pass the guard.
        when(kbService.getById(10L)).thenReturn(kb(10L, 1L));

        assertThatCode(() ->
                controller.searchPreview(10L, Map.of("query", "x"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("KB with null workspaceId is not rejected (legacy / shared KBs)")
    void nullWorkspaceKbAllowed() {
        WikiKnowledgeBaseEntity legacy = new WikiKnowledgeBaseEntity();
        legacy.setId(10L);
        legacy.setWorkspaceId(null); // pre-workspace KB
        when(kbService.getById(10L)).thenReturn(legacy);

        assertThatCode(() ->
                controller.searchPreview(10L, Map.of("query", "x"), 99L))
                .doesNotThrowAnyException();
    }

    // ---------------- rawId / chunkId endpoints (resolve owning KB) ----------------

    @Test
    @DisplayName("pagesByRawId resolves KB and rejects cross-workspace")
    void pagesByRawIdCrossWorkspaceRejected() {
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setId(50L);
        raw.setKbId(10L);
        when(rawService.getById(50L)).thenReturn(raw);
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L)); // KB in ws 2

        assertThatThrownBy(() -> controller.pagesByRawId(50L, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("pagesByChunkId resolves KB and rejects cross-workspace")
    void pagesByChunkIdCrossWorkspaceRejected() {
        WikiChunkEntity chunk = new WikiChunkEntity();
        chunk.setId(60L);
        chunk.setKbId(10L);
        when(chunkMapper.selectById(60L)).thenReturn(chunk);
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L)); // KB in ws 2

        assertThatThrownBy(() -> controller.pagesByChunkId(60L, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("unknown rawId → 404")
    void unknownRawReturns404() {
        when(rawService.getById(999L)).thenReturn(null);

        assertThatThrownBy(() -> controller.pagesByRawId(999L, 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("unknown chunkId → 404")
    void unknownChunkReturns404() {
        when(chunkMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> controller.pagesByChunkId(999L, 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    // ---------------- helpers ----------------

    private static WikiKnowledgeBaseEntity kb(long id, long workspaceId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        return entity;
    }
}
