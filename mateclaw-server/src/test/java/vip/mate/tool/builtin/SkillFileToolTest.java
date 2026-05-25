package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.runtime.SkillFileAccessPolicy;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.usage.SkillUsageService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillFileToolTest {

    @Test
    @DisplayName("listAvailableSkills applies keyword, source, status, and limit")
    void listAvailableSkillsFiltersAndLimitsRuntimeCatalog() {
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService);
        when(runtimeService.getActiveSkills()).thenReturn(List.of(
                skill("apple-notes", "database", true),
                skill("ckjia-shopping", "mcp", false),
                skill("claude-code", "acp", false)));

        String result = tool.listAvailableSkills("code", "acp", "ready", 1);

        assertTrue(result.contains("claude-code"));
        assertFalse(result.contains("ckjia-shopping"));
        assertTrue(result.contains("Showing: 1 of 1"));
    }

    @Test
    @DisplayName("readSkillFile records SKILL.md usage")
    void readSkillFileRecordsUsage() {
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService);
        ResolvedSkill skill = skill("browser-cdp", "database", true);
        skill.setContent("# Browser CDP\nUse devtools.");
        when(runtimeService.findActiveSkill("browser-cdp")).thenReturn(skill);

        String content = tool.readSkillFile("browser-cdp", "SKILL.md", null, null, null);

        assertTrue(content.contains("Browser CDP"));
        verify(usageService).recordLoaded(
                org.mockito.ArgumentMatchers.eq(skill),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("SKILL.md"),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("readSkillFile paginates large SKILL.md only when caller explicitly asks")
    void readSkillFilePaginatesLargeContent() {
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService);
        ResolvedSkill skill = skill("large-skill", "database", true);
        skill.setContent("line\n".repeat(500));
        when(runtimeService.findActiveSkill("large-skill")).thenReturn(skill);

        String content = tool.readSkillFile("large-skill", "SKILL.md", 10, 20, null);

        assertTrue(content.startsWith("line\n"));
        assertTrue(content.contains("shownLines=10-29"));
        assertTrue(content.contains("startLine=30"));
    }

    @Test
    @DisplayName("oversized single line is head-truncated and lineIndex advances (no infinite loop)")
    void readSkillFileAdvancesPastOversizedSingleLine() {
        // P2 regression: if the first requested line is itself longer than
        // MAX_OUTPUT_CHARS (8KB), the old loop hit `if (out.length() +
        // rendered > cap) break;` with emitted=0 and the banner reported
        // `shownLines=1-0, startLine=1` — the model would re-call with the
        // same start line and never advance. Big JSON / minified scripts /
        // base64 fixtures all triggered this.
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService);
        ResolvedSkill skill = skill("huge-line-skill", "database", true);
        // 12 KB single line — well past MAX_OUTPUT_CHARS (8KB).
        String hugeLine = "x".repeat(12_000);
        skill.setContent(hugeLine + "\nsecond line\nthird line\n");
        when(runtimeService.findActiveSkill("huge-line-skill")).thenReturn(skill);

        String content = tool.readSkillFile("huge-line-skill", "SKILL.md", 1, 5, null);

        // The head of the long line must appear in the output (head-truncated)
        assertTrue(content.startsWith("xxxx"),
                "Head of the oversized line must be visible to the model");
        // The truncation banner must point to the NEXT line, not the same one
        assertTrue(content.contains("startLine=2"),
                "Continuation pointer must advance past the over-long line, not stay at startLine=1");
        // Note marker must explain the partial-line situation
        assertTrue(content.contains("exceeds per-call budget"),
                "Banner should disclose that line content was head-truncated");
    }

    @Test
    @DisplayName("readSkillFile returns full SKILL.md when caller did not request pagination")
    void readSkillFileReturnsFullSkillMdByDefault() {
        // Regression: pagination by default would let the model see only the
        // first ~200 lines / 8KB of SKILL.md and silently miss later mandatory
        // sections. SKILL.md is the skill contract and must arrive whole when
        // the caller did not opt into pagination (startLine == null && maxLines
        // == null). Reference / script files keep being paginated because they
        // can be arbitrarily large supplementary material.
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService);
        ResolvedSkill skill = skill("large-skill", "database", true);
        // 500 lines * 5 chars = 2500 chars; 250 lines is also above DEFAULT_MAX_LINES (200).
        String body = "line\n".repeat(500);
        skill.setContent(body);
        when(runtimeService.findActiveSkill("large-skill")).thenReturn(skill);

        String content = tool.readSkillFile("large-skill", "SKILL.md", null, null, null);

        assertEquals(body, content,
                "Default-path SKILL.md must be returned verbatim, not paginated");
        assertFalse(content.contains("[Skill file truncated"),
                "No truncation banner should appear when caller did not opt into pagination");
    }

    private static ResolvedSkill skill(String name, String source, boolean builtin) {
        return ResolvedSkill.builder()
                .id((long) name.hashCode())
                .name(name)
                .description("Description for " + name)
                .source(source)
                .builtin(builtin)
                .enabled(true)
                .runtimeAvailable(true)
                .dependencyReady(true)
                .securityBlocked(false)
                .build();
    }
}
