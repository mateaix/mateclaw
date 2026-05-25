package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillLoadToolTest {

    private static ResolvedSkill skill(String name) {
        return ResolvedSkill.builder().id((long) name.hashCode()).name(name).build();
    }

    @Test
    @DisplayName("blank skillName returns a friendly required-arg error and does not read")
    void blankSkillNameRejected() {
        SkillRuntimeService runtime = mock(SkillRuntimeService.class);
        SkillFileTool fileTool = mock(SkillFileTool.class);
        SkillLoadTool tool = new SkillLoadTool(runtime, fileTool);

        String out = tool.loadSkill("  ", null, null);

        assertTrue(out.startsWith("Error:"));
        assertTrue(out.contains("required"));
        verify(fileTool, never()).readSkillFile(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("unknown skill returns not-found error with a listAvailableSkills hint")
    void unknownSkillReturnsError() {
        SkillRuntimeService runtime = mock(SkillRuntimeService.class);
        SkillFileTool fileTool = mock(SkillFileTool.class);
        when(runtime.findActiveSkill("nope")).thenReturn(null);
        SkillLoadTool tool = new SkillLoadTool(runtime, fileTool);

        String out = tool.loadSkill("nope", null, null);

        assertTrue(out.contains("not found"));
        assertTrue(out.contains("listAvailableSkills"));
        verify(fileTool, never()).readSkillFile(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("known skill with no filePath loads SKILL.md via the shared reader")
    void loadsSkillMdByDefault() {
        SkillRuntimeService runtime = mock(SkillRuntimeService.class);
        SkillFileTool fileTool = mock(SkillFileTool.class);
        when(runtime.findActiveSkill("foo")).thenReturn(skill("foo"));
        when(fileTool.readSkillFile(eq("foo"), eq("SKILL.md"), isNull(), isNull(), any()))
                .thenReturn("SKILL CONTENT");
        SkillLoadTool tool = new SkillLoadTool(runtime, fileTool);

        String out = tool.loadSkill("foo", null, null);

        assertEquals("SKILL CONTENT", out);
        verify(fileTool).readSkillFile(eq("foo"), eq("SKILL.md"), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("explicit filePath is forwarded to the shared reader")
    void loadsExplicitSubFile() {
        SkillRuntimeService runtime = mock(SkillRuntimeService.class);
        SkillFileTool fileTool = mock(SkillFileTool.class);
        when(runtime.findActiveSkill("foo")).thenReturn(skill("foo"));
        when(fileTool.readSkillFile(eq("foo"), eq("references/api.md"), isNull(), isNull(), any()))
                .thenReturn("REF CONTENT");
        SkillLoadTool tool = new SkillLoadTool(runtime, fileTool);

        String out = tool.loadSkill("foo", "references/api.md", null);

        assertEquals("REF CONTENT", out);
        verify(fileTool).readSkillFile(eq("foo"), eq("references/api.md"), isNull(), isNull(), any());
    }
}
