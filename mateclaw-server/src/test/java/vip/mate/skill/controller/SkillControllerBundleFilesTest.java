package vip.mate.skill.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.common.result.R;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillFileEntity;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.SkillFileSyncer;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bundle-file admin endpoints: list / read / write / delete with the
 * path envelope and builtin/virtual guards.
 */
class SkillControllerBundleFilesTest {

    private SkillService skillService;
    private SkillRuntimeService runtimeService;
    private SkillWorkspaceManager workspaceManager;
    private SkillFileSyncer fileSyncer;
    private SkillFileService fileService;
    private SkillController controller;

    private static final long SID = 1_900_000_001_000_000_902L;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        runtimeService = mock(SkillRuntimeService.class);
        workspaceManager = mock(SkillWorkspaceManager.class);
        fileSyncer = mock(SkillFileSyncer.class);
        fileService = mock(SkillFileService.class);
        controller = new SkillController(
                skillService, runtimeService, null, workspaceManager, null, fileSyncer,
                null, null, null, null, null, null, null, null, null, null, null,
                fileService);
    }

    private SkillEntity skill(boolean builtin) {
        SkillEntity s = new SkillEntity();
        s.setId(SID);
        s.setName("demo-skill");
        s.setBuiltin(builtin);
        return s;
    }

    private SkillFileEntity row(String path, String content) {
        SkillFileEntity e = new SkillFileEntity();
        e.setId(1L);
        e.setSkillId(SID);
        e.setFilePath(path);
        e.setContent(content);
        e.setContentSize(content.getBytes().length);
        e.setSha256("h");
        return e;
    }

    // ==================== path envelope ====================

    @Test
    @DisplayName("normalizeBundlePath accepts the three buckets and rejects escapes")
    void pathEnvelope() {
        assertThat(SkillController.normalizeBundlePath("scripts/run.py")).isEqualTo("scripts/run.py");
        assertThat(SkillController.normalizeBundlePath("references/a/b.md")).isEqualTo("references/a/b.md");
        assertThat(SkillController.normalizeBundlePath("templates/report.html")).isEqualTo("templates/report.html");
        assertThat(SkillController.normalizeBundlePath("templates\\r.html")).isEqualTo("templates/r.html");

        assertThat(SkillController.normalizeBundlePath(null)).isNull();
        assertThat(SkillController.normalizeBundlePath("  ")).isNull();
        assertThat(SkillController.normalizeBundlePath("SKILL.md")).isNull();
        assertThat(SkillController.normalizeBundlePath("scripts/../etc/passwd")).isNull();
        assertThat(SkillController.normalizeBundlePath("/scripts/run.py")).isNull();
        assertThat(SkillController.normalizeBundlePath("scripts/")).isNull();
        assertThat(SkillController.normalizeBundlePath("scripts//x.py")).isNull();
        assertThat(SkillController.normalizeBundlePath("outputs/x.txt")).isNull();
    }

    // ==================== list ====================

    @Test
    @DisplayName("list returns rows without content and self-heals an empty store")
    void listSelfHeals() {
        when(skillService.getSkill(SID)).thenReturn(skill(false));
        when(fileService.listBySkillId(SID))
                .thenReturn(List.of())
                .thenReturn(List.of(row("scripts/run.py", "print()")));

        R<List<Map<String, Object>>> resp = controller.listBundleFiles(SID, null);

        verify(fileSyncer).syncOne(any(SkillEntity.class));
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0)).containsEntry("path", "scripts/run.py");
        assertThat(resp.getData().get(0)).doesNotContainKey("content");
    }

    @Test
    @DisplayName("list on a virtual MCP skill id returns empty without a DB lookup")
    void listVirtualIsEmpty() {
        R<List<Map<String, Object>>> resp =
                controller.listBundleFiles(McpSkillBridge.VIRTUAL_ID_BASE + 7L, null);
        assertThat(resp.getData()).isEmpty();
        verify(skillService, never()).getSkill(any());
    }

    // ==================== write ====================

    @Test
    @DisplayName("put writes the canonical row, materializes the cache, and rescans")
    void putHappyPath() {
        when(skillService.getSkill(SID)).thenReturn(skill(false));
        when(fileService.upsertFile(SID, "templates/report.html", "<html/>"))
                .thenReturn(row("templates/report.html", "<html/>"));

        R<Map<String, Object>> resp = controller.putBundleFileContent(SID,
                Map.of("path", "templates/report.html", "content", "<html/>"), null);

        assertThat(resp.getData()).containsEntry("path", "templates/report.html");
        verify(fileService).upsertFile(SID, "templates/report.html", "<html/>");
        verify(workspaceManager).writeWorkspaceFile("demo-skill", "templates/report.html", "<html/>");
        verify(runtimeService).rescanSingle(any(SkillEntity.class));
    }

    @Test
    @DisplayName("put on a builtin skill is refused")
    void putBuiltinRefused() {
        when(skillService.getSkill(SID)).thenReturn(skill(true));

        R<Map<String, Object>> resp = controller.putBundleFileContent(SID,
                Map.of("path", "scripts/x.py", "content", "x"), null);

        assertThat(resp.getMsg()).contains("read-only");
        verify(fileService, never()).upsertFile(any(), any(), any());
    }

    @Test
    @DisplayName("put with an out-of-bucket path is refused")
    void putBadPathRefused() {
        when(skillService.getSkill(SID)).thenReturn(skill(false));

        R<Map<String, Object>> resp = controller.putBundleFileContent(SID,
                Map.of("path", "secrets/creds.txt", "content", "x"), null);

        assertThat(resp.getMsg()).contains("Invalid file path");
        verify(fileService, never()).upsertFile(any(), any(), any());
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete removes the row and the workspace cache file")
    void deleteHappyPath() {
        when(skillService.getSkill(SID)).thenReturn(skill(false));
        when(fileService.deleteFile(SID, "scripts/run.py")).thenReturn(true);

        R<Map<String, Object>> resp = controller.deleteBundleFile(SID, "scripts/run.py", null);

        assertThat(resp.getData()).containsEntry("removed", true);
        verify(workspaceManager).deleteWorkspaceFile("demo-skill", "scripts/run.py");
        verify(runtimeService).rescanSingle(any(SkillEntity.class));
    }

    @Test
    @DisplayName("delete on a builtin skill is refused")
    void deleteBuiltinRefused() {
        when(skillService.getSkill(SID)).thenReturn(skill(true));

        R<Map<String, Object>> resp = controller.deleteBundleFile(SID, "scripts/run.py", null);

        assertThat(resp.getMsg()).contains("read-only");
        verify(fileService, never()).deleteFile(any(), any());
    }
}
