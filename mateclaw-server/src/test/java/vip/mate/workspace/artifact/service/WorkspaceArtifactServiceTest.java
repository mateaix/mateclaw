package vip.mate.workspace.artifact.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.artifact.model.WorkspaceArtifactEntity;
import vip.mate.workspace.artifact.repository.WorkspaceArtifactMapper;
import vip.mate.workspace.artifact.vo.ArtifactPageVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkspaceArtifactService} — the issue #514 catalog
 * read/write. The mapper is mocked so these run without a DB; the queries
 * themselves are exercised by the webchat E2E suite.
 */
class WorkspaceArtifactServiceTest {

    private final WorkspaceArtifactMapper mapper = mock(WorkspaceArtifactMapper.class);
    private final WorkspaceArtifactService service = new WorkspaceArtifactService(mapper);

    // -------- classifyType --------

    @Test
    @DisplayName("classifyType maps docx/pdf/pptx → document")
    void classifyDocuments() {
        assertEquals("document", WorkspaceArtifactService.classifyType("报告.docx", null));
        assertEquals("document", WorkspaceArtifactService.classifyType("report.pdf", null));
        assertEquals("document", WorkspaceArtifactService.classifyType("deck.pptx", null));
    }

    @Test
    @DisplayName("classifyType maps xlsx/csv → data")
    void classifyData() {
        assertEquals("data", WorkspaceArtifactService.classifyType("持仓明细.xlsx", null));
        assertEquals("data", WorkspaceArtifactService.classifyType("data.csv", null));
    }

    @Test
    @DisplayName("classifyType maps png/jpg/svg → image (by extension)")
    void classifyImagesByExt() {
        assertEquals("image", WorkspaceArtifactService.classifyType("chart.png", null));
        assertEquals("image", WorkspaceArtifactService.classifyType("photo.jpg", null));
        assertEquals("image", WorkspaceArtifactService.classifyType("logo.svg", null));
    }

    @Test
    @DisplayName("classifyType falls back to mime when extension is unknown")
    void classifyByMimeFallback() {
        assertEquals("image", WorkspaceArtifactService.classifyType("blob", "image/png"));
        assertEquals("data", WorkspaceArtifactService.classifyType("blob", "text/csv"));
        assertEquals("document", WorkspaceArtifactService.classifyType("blob", "application/pdf"));
    }

    @Test
    @DisplayName("classifyType returns 'other' for unrecognised inputs")
    void classifyOther() {
        assertEquals("other", WorkspaceArtifactService.classifyType("unknown.xyz", null));
        assertEquals("other", WorkspaceArtifactService.classifyType(null, null));
        assertEquals("other", WorkspaceArtifactService.classifyType("file", "application/octet-stream"));
    }

    // -------- register --------

    @Test
    @DisplayName("register inserts the entity and returns its id")
    void registerInserts() {
        WorkspaceArtifactEntity entity = newEntity("report.docx", WorkspaceArtifactService.SOURCE_AGENT);
        entity.setId(42L);
        when(mapper.insert(any(WorkspaceArtifactEntity.class))).thenReturn(1);

        Long id = service.register(entity);

        assertEquals(42L, id);
        verify(mapper).insert(entity);
    }

    @Test
    @DisplayName("register swallows persistence failure and returns null (best-effort)")
    void registerSwallowsFailure() {
        WorkspaceArtifactEntity entity = newEntity("report.docx", WorkspaceArtifactService.SOURCE_AGENT);
        when(mapper.insert(any(WorkspaceArtifactEntity.class))).thenThrow(new RuntimeException("DB down"));

        Long id = service.register(entity);

        assertNull(id, "a failed catalog write must not propagate");
    }

    // -------- list --------

    @Test
    @DisplayName("list applies filters and maps to VO")
    @SuppressWarnings("unchecked")
    void listWithFilters() {
        WorkspaceArtifactEntity row = newEntity("持仓.xlsx", WorkspaceArtifactService.SOURCE_AGENT);
        row.setToolCallId("tc_007");
        row.setArtifactType("data");
        Page<WorkspaceArtifactEntity> mpPage = new Page<>(1, 50);
        mpPage.setRecords(List.of(row));
        mpPage.setTotal(1);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mpPage);

        ArtifactPageVO result = service.list(10L, 1L, "conv-1", "agent", "data", 1, 50);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getItems().size());
        assertEquals("持仓.xlsx", result.getItems().get(0).getName());
        assertEquals("agent", result.getItems().get(0).getSource());
        assertEquals("data", result.getItems().get(0).getType());
        assertEquals("tc_007", result.getItems().get(0).getToolCallId());
        assertFalse(result.isHasMore());
        verify(mapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("list clamps page/size to safe bounds")
    @SuppressWarnings("unchecked")
    void listClampsSize() {
        Page<WorkspaceArtifactEntity> mpPage = new Page<>(1, 200);
        mpPage.setRecords(List.of());
        mpPage.setTotal(0);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mpPage);

        // size=9999 should be clamped to 200; page=0 to 1.
        ArtifactPageVO result = service.list(10L, 1L, null, null, null, 0, 9999);

        assertEquals(1, result.getPage());
        assertEquals(200, result.getSize());
    }

    @Test
    @DisplayName("hasMore is true when total exceeds the current page window")
    @SuppressWarnings("unchecked")
    void hasMoreComputation() {
        Page<WorkspaceArtifactEntity> mpPage = new Page<>(1, 50);
        mpPage.setRecords(List.of());
        mpPage.setTotal(100);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mpPage);

        ArtifactPageVO result = service.list(10L, 1L, null, null, null, 1, 50);

        assertTrue(result.isHasMore(), "page 1 of 50 with 100 total must report hasMore");
    }

    private static WorkspaceArtifactEntity newEntity(String name, String source) {
        WorkspaceArtifactEntity e = new WorkspaceArtifactEntity();
        e.setAgentId(10L);
        e.setWorkspaceId(1L);
        e.setSource(source);
        e.setName(name);
        e.setStorageKind(WorkspaceArtifactService.STORAGE_GENERATED_CACHE);
        e.setStorageRef("uuid-1");
        e.setDownloadUrl("/api/v1/files/generated/uuid-1");
        return e;
    }
}
