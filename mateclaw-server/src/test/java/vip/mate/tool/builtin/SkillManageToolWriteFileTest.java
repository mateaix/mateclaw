package vip.mate.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.SkillSecurityService;
import vip.mate.skill.runtime.SkillValidationResult;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@code write_file} action of {@link SkillManageTool}: writing
 * supporting files under a skill, and its guards (missing path, builtin,
 * unknown skill, unsafe path).
 */
class SkillManageToolWriteFileTest {

    private SkillService skillService;
    private SkillFileService skillFileService;
    private SkillSecurityService securityService;
    private SkillWorkspaceManager workspaceManager;
    private SkillManageTool tool;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        skillFileService = mock(SkillFileService.class);
        securityService = mock(SkillSecurityService.class);
        workspaceManager = mock(SkillWorkspaceManager.class);
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        tool = new SkillManageTool(skillService, skillFileService, securityService, workspaceManager, runtimeService);
    }

    private SkillEntity skill(String name, boolean builtin) {
        SkillEntity s = new SkillEntity();
        s.setId(42L);
        s.setName(name);
        s.setBuiltin(builtin);
        s.setSkillContent("---\nname: " + name + "\n---\n# x");
        return s;
    }

    private void scanPasses() {
        SkillValidationResult ok = mock(SkillValidationResult.class);
        when(ok.isBlocked()).thenReturn(false);
        when(ok.getWarnings()).thenReturn(List.of());
        when(securityService.scanContent(any(), any())).thenReturn(ok);
    }

    @Test
    @DisplayName("write_file writes a supporting file under the skill")
    void writesSupportingFile() {
        when(skillService.findByName("my-skill")).thenReturn(skill("my-skill", false));
        scanPasses();

        String result = tool.skill_manage("write_file", "my-skill", "echo hi",
                null, null, "scripts/run.sh", null);

        assertTrue(result.startsWith("File 'scripts/run.sh' written"), result);
        verify(workspaceManager, times(1)).writeWorkspaceFile("my-skill", "scripts/run.sh", "echo hi");
        // The canonical store row must be written too, not just the FS cache.
        verify(skillFileService, times(1)).upsertFile(42L, "scripts/run.sh", "echo hi");
    }

    @Test
    @DisplayName("write_file accepts templates/ paths")
    void writesTemplateFile() {
        when(skillService.findByName("my-skill")).thenReturn(skill("my-skill", false));
        scanPasses();

        String result = tool.skill_manage("write_file", "my-skill", "<html></html>",
                null, null, "templates/report.html", null);

        assertTrue(result.startsWith("File 'templates/report.html' written"), result);
        verify(workspaceManager, times(1)).writeWorkspaceFile("my-skill", "templates/report.html", "<html></html>");
        verify(skillFileService, times(1)).upsertFile(42L, "templates/report.html", "<html></html>");
    }

    @Test
    @DisplayName("write_file without filePath is rejected")
    void rejectsMissingPath() {
        String result = tool.skill_manage("write_file", "my-skill", "body",
                null, null, null, null);
        assertTrue(result.startsWith("Error"), result);
        verify(workspaceManager, never()).writeWorkspaceFile(any(), any(), any());
    }

    @Test
    @DisplayName("write_file into a builtin skill is rejected")
    void rejectsBuiltin() {
        when(skillService.findByName("core")).thenReturn(skill("core", true));
        String result = tool.skill_manage("write_file", "core", "body",
                null, null, "references/x.md", null);
        assertTrue(result.contains("builtin"), result);
        verify(workspaceManager, never()).writeWorkspaceFile(any(), any(), any());
    }

    @Test
    @DisplayName("write_file for an unknown skill is rejected")
    void rejectsUnknownSkill() {
        when(skillService.findByName("ghost")).thenReturn(null);
        String result = tool.skill_manage("write_file", "ghost", "body",
                null, null, "references/x.md", null);
        assertTrue(result.contains("not found"), result);
        verify(workspaceManager, never()).writeWorkspaceFile(any(), any(), any());
    }

    @Test
    @DisplayName("write_file surfaces an unsafe-path rejection from the workspace manager")
    void surfacesUnsafePath() {
        when(skillService.findByName("my-skill")).thenReturn(skill("my-skill", false));
        scanPasses();
        doThrow(new IllegalArgumentException("Unsafe file path rejected: ../etc/passwd"))
                .when(workspaceManager).writeWorkspaceFile(eq("my-skill"), any(), any());

        String result = tool.skill_manage("write_file", "my-skill", "body",
                null, null, "../etc/passwd", null);
        assertTrue(result.startsWith("Error"), result);
    }
}
