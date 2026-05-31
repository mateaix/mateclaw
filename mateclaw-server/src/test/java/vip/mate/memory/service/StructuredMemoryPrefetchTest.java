package vip.mate.memory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the structured-memory split between always-on system prompt injection
 * (stable types) and query-conditioned prefetch (growing/specific types), plus
 * the relevance scoring that lets a natural-language question surface the right
 * stored fact instead of letting it lose salience in an always-on dump.
 */
class StructuredMemoryPrefetchTest {

    private static final long AGENT_ID = 1000000001L;

    private StructuredMemoryService newService(String projectMd, String userMd) {
        WorkspaceFileService files = mock(WorkspaceFileService.class);
        when(files.getFile(eq(AGENT_ID), anyString())).thenReturn(null);
        if (projectMd != null) {
            when(files.getFile(AGENT_ID, "structured/project.md")).thenReturn(fileWith(projectMd));
        }
        if (userMd != null) {
            when(files.getFile(AGENT_ID, "structured/user.md")).thenReturn(fileWith(userMd));
        }
        return new StructuredMemoryService(files, mock(ApplicationEventPublisher.class));
    }

    private WorkspaceFileEntity fileWith(String content) {
        WorkspaceFileEntity e = new WorkspaceFileEntity();
        e.setContent(content);
        return e;
    }

    @Test
    @DisplayName("system prompt block excludes growing project entries")
    void systemPromptBlockExcludesProject() {
        StructuredMemoryService svc = newService(
                "## project_codename\n用户的项目代号叫\"天枢\"。\n> Source: agent | Updated: 2026-05-29",
                "## reply_style\n偏好简洁直接的回答风格。\n> Source: agent | Updated: 2026-05-29");

        String block = svc.buildMemoryBlock(AGENT_ID);

        // Stable user profile stays in the system prompt...
        assertTrue(block.contains("reply_style"), "stable user entry should be in system prompt");
        // ...but specific project facts must not be dumped always-on.
        assertFalse(block.contains("天枢"), "project codename must not be in system prompt block");
    }

    @Test
    @DisplayName("prefetch surfaces the project codename for a Chinese question about it")
    void prefetchSurfacesCodename() {
        StructuredMemoryService svc = newService(
                "## project_codename\n用户的项目代号叫\"天枢\"。\n> Source: agent | Updated: 2026-05-29\n\n"
                        + "## project_tech_stack\nRust + Postgres\n> Source: agent | Updated: 2026-05-29",
                null);

        String block = svc.buildPrefetchBlock(AGENT_ID,
                "我之前告诉过你我的项目代号,你还记得吗?");

        assertTrue(block.contains("天枢"), "codename should be recalled by a codename question");
    }

    @Test
    @DisplayName("prefetch surfaces tech stack via cross-language alias (技术栈 -> tech_stack)")
    void prefetchSurfacesTechStackViaAlias() {
        StructuredMemoryService svc = newService(
                "## project_tech_stack\nRust + Postgres\n> Source: agent | Updated: 2026-05-29",
                null);

        String block = svc.buildPrefetchBlock(AGENT_ID, "我的技术栈是什么?");

        assertNotNull(block);
        assertTrue(block.contains("Rust") && block.contains("Postgres"),
                "tech stack should be recalled even though the key is English and the question is Chinese");
    }

    @Test
    @DisplayName("prefetch orders conflicting entries newest-first and annotates the update date")
    void prefetchOrdersByRecency() {
        StructuredMemoryService svc = newService(
                "## project_old_codename\n旧项目代号叫\"天枢\"。\n> Source: agent | Updated: 2026-05-01\n\n"
                        + "## project_new_codename\n新项目代号叫\"云梯计划\"。\n> Source: agent | Updated: 2026-05-29",
                null);

        String block = svc.buildPrefetchBlock(AGENT_ID, "我的项目代号是什么?");

        // Both surface, but the most recently updated one ranks first...
        int newIdx = block.indexOf("云梯计划");
        int oldIdx = block.indexOf("天枢");
        assertTrue(newIdx >= 0 && oldIdx >= 0, "both conflicting entries should be recalled");
        assertTrue(newIdx < oldIdx, "the most recently updated entry should rank first");
        // ...and the update date is exposed so the model can resolve the conflict.
        assertTrue(block.contains("updated 2026-05-29"), "recency hint should be present");
    }

    @Test
    @DisplayName("prefetch marks the block when the user's own project is recalled (project type)")
    void prefetchMarksProjectRecall() {
        StructuredMemoryService svc = newService(
                "## project_codename\n用户的项目代号叫\"天枢\"。\n> Source: agent | Updated: 2026-05-29",
                null);

        String block = svc.buildPrefetchBlock(AGENT_ID, "我的项目代号是什么?");

        assertTrue(block.contains(StructuredMemoryService.PROJECT_RECALLED_MARKER),
                "a project-type recall should carry the marker so wiki injection can be suppressed");
    }

    @Test
    @DisplayName("prefetch does NOT mark the block for reference-only recall")
    void prefetchNoMarkerForReferenceOnly() {
        // Only a reference-type file is present; the project file is absent.
        WorkspaceFileService files = mock(WorkspaceFileService.class);
        when(files.getFile(eq(AGENT_ID), anyString())).thenReturn(null);
        WorkspaceFileEntity ref = new WorkspaceFileEntity();
        ref.setContent("## api_endpoint\n参考:订单查询接口 /api/orders。\n> Source: agent | Updated: 2026-05-29");
        when(files.getFile(AGENT_ID, "structured/reference.md")).thenReturn(ref);
        StructuredMemoryService svc = new StructuredMemoryService(files, mock(ApplicationEventPublisher.class));

        String block = svc.buildPrefetchBlock(AGENT_ID, "订单查询接口参考是什么?");

        assertFalse(block.contains(StructuredMemoryService.PROJECT_RECALLED_MARKER),
                "reference-only recall must not claim a project so wiki context stays available");
    }

    @Test
    @DisplayName("prefetch returns empty for an unrelated question")
    void prefetchEmptyForUnrelatedQuery() {
        StructuredMemoryService svc = newService(
                "## project_codename\n用户的项目代号叫\"天枢\"。\n> Source: agent | Updated: 2026-05-29",
                null);

        assertEquals("", svc.buildPrefetchBlock(AGENT_ID, "今天天气怎么样?"));
        assertEquals("", svc.buildPrefetchBlock(AGENT_ID, ""));
        assertEquals("", svc.buildPrefetchBlock(AGENT_ID, null));
    }
}
