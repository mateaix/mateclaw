package vip.mate.wiki.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.BatchGroupRequest;
import vip.mate.wiki.dto.SourceGroupRequest;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiSourceGroupEntity;
import vip.mate.wiki.pipeline.WikiPipelineDefinitionService;
import vip.mate.wiki.profile.WikiPageTypeProfileService;
import vip.mate.wiki.repository.WikiPipelineRunMapper;
import vip.mate.wiki.repository.WikiPipelineStepRunMapper;
import vip.mate.wiki.service.WikiDirectoryScanService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiLintJobService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiPageTypePermissionService;
import vip.mate.wiki.service.WikiProcessingService;
import vip.mate.wiki.service.WikiRawMaterialService;
import vip.mate.wiki.service.WikiSourceGroupScanService;
import vip.mate.wiki.service.WikiSourceGroupService;
import vip.mate.wiki.service.WikiSourcePathValidator;
import vip.mate.wiki.service.WikiSourceWatcherService;
import vip.mate.wiki.sse.WikiProgressBus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IDOR guard for the source-group endpoints added on {@link WikiController}:
 * every endpoint must reject requests whose target KB belongs to a different
 * workspace than the caller's {@code X-Workspace-Id} header, matching the
 * pattern established by {@link WikiEntityControllerIdorTest}.
 */
class WikiSourceGroupControllerIdorTest {

    private WikiKnowledgeBaseService kbService;
    private WikiController controller;

    @BeforeEach
    void setUp() {
        kbService = mock(WikiKnowledgeBaseService.class);
        controller = new WikiController(
                kbService,
                mock(WikiRawMaterialService.class),
                mock(WikiPageService.class),
                mock(WikiProcessingService.class),
                mock(WikiDirectoryScanService.class),
                mock(WikiLintJobService.class),
                mock(WikiProperties.class),
                mock(WikiProgressBus.class),
                mock(AuditEventService.class),
                mock(WikiPageTypeProfileService.class),
                mock(WikiPageTypePermissionService.class),
                mock(WikiSourcePathValidator.class),
                mock(WikiSourceGroupService.class),
                mock(WikiSourceGroupScanService.class),
                mock(WikiSourceWatcherService.class),
                mock(WikiPipelineDefinitionService.class),
                mock(WikiPipelineRunMapper.class),
                mock(WikiPipelineStepRunMapper.class),
                mock(ObjectMapper.class));
    }

    @Test
    @DisplayName("createSourceGroup on another workspace's KB → 403")
    void createSourceGroupCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));
        SourceGroupRequest req = new SourceGroupRequest("docs", "/data/docs", null, null, true);

        assertThatThrownBy(() -> controller.createSourceGroup(10L, req, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("updateSourceGroup on another workspace's KB → 403")
    void updateSourceGroupCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));
        SourceGroupRequest req = new SourceGroupRequest("docs", "/data/docs", null, null, true);

        assertThatThrownBy(() -> controller.updateSourceGroup(10L, 5L, req, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("deleteSourceGroup on another workspace's KB → 403")
    void deleteSourceGroupCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.deleteSourceGroup(10L, 5L, null, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("scanSourceGroup on another workspace's KB → 403")
    void scanSourceGroupCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.scanSourceGroup(10L, 5L, "incremental", 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("updateRawGroup on another workspace's KB → 403")
    void updateRawGroupCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.updateRawGroup(10L, 7L, Map.of("groupId", 5L), 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("updateRawGroupBatch on another workspace's KB → 403")
    void updateRawGroupBatchCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));
        BatchGroupRequest req = new BatchGroupRequest(List.of(7L, 8L), 5L);

        assertThatThrownBy(() -> controller.updateRawGroupBatch(10L, req, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("unknown kbId on createSourceGroup → 404")
    void unknownKbReturns404() {
        when(kbService.getById(999L)).thenReturn(null);
        SourceGroupRequest req = new SourceGroupRequest("docs", "/data/docs", null, null, true);

        assertThatThrownBy(() -> controller.createSourceGroup(999L, req, 1L))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("listSourceGroups on another workspace's KB → 403")
    void listSourceGroupsCrossWorkspaceRejected() {
        when(kbService.getById(10L)).thenReturn(kb(10L, 2L));

        assertThatThrownBy(() -> controller.listSourceGroups(10L, 1L))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    @DisplayName("deleteSourceGroup with reassignTo pointing to another KB's group → 404")
    void deleteSourceGroupCrossKbReassignToRejected() {
        // KB 1 belongs to workspace 1, so the workspace check passes.
        when(kbService.getById(1L)).thenReturn(kb(1L, 1L));
        WikiSourceGroupService groupService = mock(WikiSourceGroupService.class);

        // The source group exists in KB 1.
        WikiSourceGroupEntity srcGroup = group(5L, 1L);
        when(groupService.getById(5L)).thenReturn(srcGroup);

        // But the reassignTo target group belongs to KB 2 (same workspace, different KB).
        WikiSourceGroupEntity targetGroup = group(6L, 2L);
        when(groupService.getById(6L)).thenReturn(targetGroup);

        // We need a controller with the real groupService mock for this test.
        WikiController testController = buildControllerWithGroupService(groupService);

        var result = testController.deleteSourceGroup(1L, 5L, 6L, 1L);
        assertEquals(404, result.getCode(),
                "reassignTo pointing to another KB's group must be rejected with 404");
    }

    // ---------------- helpers ----------------

    private WikiController buildControllerWithGroupService(WikiSourceGroupService groupService) {
        return new WikiController(
                kbService,
                mock(WikiRawMaterialService.class),
                mock(WikiPageService.class),
                mock(WikiProcessingService.class),
                mock(WikiDirectoryScanService.class),
                mock(WikiLintJobService.class),
                mock(WikiProperties.class),
                mock(WikiProgressBus.class),
                mock(AuditEventService.class),
                mock(WikiPageTypeProfileService.class),
                mock(WikiPageTypePermissionService.class),
                mock(WikiSourcePathValidator.class),
                groupService,
                mock(WikiSourceGroupScanService.class),
                mock(WikiSourceWatcherService.class),
                mock(WikiPipelineDefinitionService.class),
                mock(WikiPipelineRunMapper.class),
                mock(WikiPipelineStepRunMapper.class),
                mock(ObjectMapper.class));
    }

    private static WikiSourceGroupEntity group(long id, long kbId) {
        WikiSourceGroupEntity g = new WikiSourceGroupEntity();
        g.setId(id);
        g.setKbId(kbId);
        return g;
    }

    // ---------------- helpers ----------------

    private static WikiKnowledgeBaseEntity kb(long id, long workspaceId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        return entity;
    }
}
