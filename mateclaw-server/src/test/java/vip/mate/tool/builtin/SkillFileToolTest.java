package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.context.AgentWorkspaceResolver;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.llm.routing.AgentBindingResolver;
import vip.mate.skill.runtime.SkillFileAccessPolicy;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.usage.SkillUsageService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        when(runtimeService.getActiveSkills(any())).thenReturn(List.of(
                skill("apple-notes", "database", true),
                skill("ckjia-shopping", "mcp", false),
                skill("claude-code", "acp", false)));

        String result = tool.listAvailableSkills("code", "acp", "ready", 1, null);

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
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ResolvedSkill skill = skill("browser-cdp", "database", true);
        skill.setContent("# Browser CDP\nUse devtools.");
        when(runtimeService.findActiveSkill(eq("browser-cdp"), any())).thenReturn(skill);

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
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ResolvedSkill skill = skill("large-skill", "database", true);
        skill.setContent("line\n".repeat(500));
        when(runtimeService.findActiveSkill(eq("large-skill"), any())).thenReturn(skill);

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
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ResolvedSkill skill = skill("huge-line-skill", "database", true);
        // 12 KB single line — well past MAX_OUTPUT_CHARS (8KB).
        String hugeLine = "x".repeat(12_000);
        skill.setContent(hugeLine + "\nsecond line\nthird line\n");
        when(runtimeService.findActiveSkill(eq("huge-line-skill"), any())).thenReturn(skill);

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
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ResolvedSkill skill = skill("large-skill", "database", true);
        // 500 lines * 5 chars = 2500 chars; 250 lines is also above DEFAULT_MAX_LINES (200).
        String body = "line\n".repeat(500);
        skill.setContent(body);
        when(runtimeService.findActiveSkill(eq("large-skill"), any())).thenReturn(skill);

        String content = tool.readSkillFile("large-skill", "SKILL.md", null, null, null);

        assertEquals(body, content,
                "Default-path SKILL.md must be returned verbatim, not paginated");
        assertFalse(content.contains("[Skill file truncated"),
                "No truncation banner should appear when caller did not opt into pagination");
    }

    @Test
    @DisplayName("listAvailableSkills hides skills the agent is not bound to")
    void listAvailableSkillsRespectsAgentBindings() {
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        AgentBindingResolver bindingResolver = mock(AgentBindingResolver.class);
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ReflectionTestUtils.setField(tool, "agentBindingResolver", bindingResolver);

        ResolvedSkill bound = skill("alpha-skill", "database", true);
        ResolvedSkill unbound = skill("beta-skill", "database", true);
        when(runtimeService.getActiveSkills(any())).thenReturn(List.of(bound, unbound));
        // Agent 42 is bound only to alpha-skill; beta-skill must not surface.
        when(bindingResolver.getBoundSkillIds(42L)).thenReturn(Set.of(bound.getId()));

        ToolContext ctx = ChatOrigin.EMPTY.withAgent(42L).toToolContext();
        String result = tool.listAvailableSkills(null, null, null, 20, ctx);

        assertTrue(result.contains("alpha-skill"));
        assertFalse(result.contains("beta-skill"));
    }

    @Test
    @DisplayName("readSkillFile denies a skill the agent is not bound to")
    void readSkillFileDeniesUnboundSkill() {
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        AgentBindingResolver bindingResolver = mock(AgentBindingResolver.class);
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ReflectionTestUtils.setField(tool, "agentBindingResolver", bindingResolver);

        ResolvedSkill beta = skill("beta-skill", "database", true);
        beta.setContent("# Beta\nsecret body");
        when(runtimeService.findActiveSkill(eq("beta-skill"), any())).thenReturn(beta);
        // Bound to some other skill id, never beta's.
        when(bindingResolver.getBoundSkillIds(42L)).thenReturn(Set.of(999L));

        ToolContext ctx = ChatOrigin.EMPTY.withAgent(42L).toToolContext();
        String result = tool.readSkillFile("beta-skill", "SKILL.md", null, null, ctx);

        assertTrue(result.contains("is not available for this agent"));
        assertFalse(result.contains("secret body"));
    }

    @Test
    @DisplayName("listSkillFiles denies a skill the agent is not bound to")
    void listSkillFilesDeniesUnboundSkill() {
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        AgentBindingResolver bindingResolver = mock(AgentBindingResolver.class);
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ReflectionTestUtils.setField(tool, "agentBindingResolver", bindingResolver);

        ResolvedSkill beta = skill("beta-skill", "database", true);
        when(runtimeService.findActiveSkill(eq("beta-skill"), any())).thenReturn(beta);
        when(bindingResolver.getBoundSkillIds(42L)).thenReturn(Set.of(999L));

        ToolContext ctx = ChatOrigin.EMPTY.withAgent(42L).toToolContext();
        String result = tool.listSkillFiles("beta-skill", ctx);

        assertTrue(result.contains("is not available for this agent"));
    }

    @Test
    @DisplayName("agents with no explicit bindings keep full skill access")
    void readSkillFileAllowsWhenAgentHasNoBindings() {
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        AgentBindingResolver bindingResolver = mock(AgentBindingResolver.class);
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ReflectionTestUtils.setField(tool, "agentBindingResolver", bindingResolver);

        ResolvedSkill beta = skill("beta-skill", "database", true);
        beta.setContent("# Beta\nvisible body");
        when(runtimeService.findActiveSkill(eq("beta-skill"), any())).thenReturn(beta);
        // null == no explicit binding restriction → inherit every enabled skill.
        when(bindingResolver.getBoundSkillIds(42L)).thenReturn(null);

        ToolContext ctx = ChatOrigin.EMPTY.withAgent(42L).toToolContext();
        String result = tool.readSkillFile("beta-skill", "SKILL.md", null, null, ctx);

        assertTrue(result.contains("visible body"));
        assertFalse(result.contains("is not available for this agent"));
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
