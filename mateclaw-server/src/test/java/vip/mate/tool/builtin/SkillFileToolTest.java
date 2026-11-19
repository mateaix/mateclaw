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
    void compactOverviewIncludesAll79NamesWithinHardBudget() throws Exception {
        var runtime=mock(SkillRuntimeService.class);
        var resolver=mock(AgentWorkspaceResolver.class);
        when(resolver.resolve(any())).thenReturn(1L);
        var tool=new SkillFileTool(runtime,mock(SkillFileAccessPolicy.class),mock(SkillUsageService.class),resolver);
        when(runtime.getActiveSkills(any())).thenReturn(java.util.stream.IntStream.range(0,79)
            .mapToObj(i->skill("skill-"+i,"database",true)).toList());
        String output=tool.listAvailableSkills(null,null,null,null,null);
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(output);
        assertEquals("skill_catalog",json.path("kind").asText());
        assertEquals(79,json.path("matchingTotal").asInt());
        assertEquals(79,json.path("names").size());
        assertEquals(79,json.path("summaries").size(),"Every name needs a bounded summary, not just the first examples");
        for(var name:json.path("names")) assertFalse(json.path("summaries").path(name.asText()).asText().isBlank());
        assertFalse(json.path("hasMore").asBoolean());
        assertTrue(json.path("nextOffset").isNull());
        assertTrue(json.path("examples").size()<=20);
        assertTrue(output.length()<=8000,"The hard output budget must be independent of model behavior");
        var names=new java.util.HashSet<String>();json.path("names").forEach(n->names.add(n.asText()));
        for(int i=0;i<79;i++)assertTrue(names.contains("skill-"+i));
        String filtered=tool.listAvailableSkills("does-not-exist",null,null,null,null);
        var empty=new com.fasterxml.jackson.databind.ObjectMapper().readTree(filtered);
        assertEquals(0,empty.path("matchingTotal").asInt());
        assertEquals("does-not-exist",empty.path("filters").path("keyword").asText());
    }

    @Test
    void cursorPagesAreBoundedAndCoverLargeCatalogWithoutAlphabetSearch() throws Exception {
        var runtime=mock(SkillRuntimeService.class);
        var resolver=mock(AgentWorkspaceResolver.class);
        when(resolver.resolve(any())).thenReturn(1L);
        var tool=new SkillFileTool(runtime,mock(SkillFileAccessPolicy.class),mock(SkillUsageService.class),resolver);
        var catalog=java.util.stream.IntStream.range(0,450).mapToObj(i->{
            var entry=skill("long-name-"+i+"-"+"x".repeat(80),"database",true);
            entry.setDescription("very long description".repeat(1000));return entry;
        }).toList();
        when(runtime.getActiveSkills(any())).thenReturn(catalog);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var names=new java.util.HashSet<String>();int offset=0,pages=0;
        while(true) {
            String output=tool.listAvailableSkills(null,null,null,50,offset,null);
            assertTrue(output.length()<=8000);
            var page=mapper.readTree(output);
            assertEquals(450,page.path("matchingTotal").asInt());
            for(var name:page.path("names"))assertTrue(names.add(name.asText()),"No duplicate names across cursor pages");
            pages++;assertTrue(pages<=450);
            if(!page.path("hasMore").asBoolean())break;
            int next=page.path("nextOffset").asInt();assertTrue(next>offset);offset=next;
        }
        assertEquals(450,names.size());
        assertTrue(pages>1);
        var beyond=mapper.readTree(tool.listAvailableSkills(null,null,null,20,1000,null));
        assertTrue(beyond.path("names").isEmpty());assertFalse(beyond.path("hasMore").asBoolean());
    }

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
        assertTrue(result.contains("\"matchingTotal\":1"));
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

    @Test
    @DisplayName("SKILL.md just over the 8KB tool-result cap is still returned whole (no lossy cut)")
    void readSkillFileReturnsFullSkillMdJustOverToolResultCap() {
        // Regression: an 8261-char SKILL.md used to be hard-cut to 8000 chars
        // downstream with a "[TRUNCATED: ... middle omitted]" marker, and the
        // model fabricated the removed middle. Anything under the full-return
        // ceiling must arrive intact so every mandatory section is visible.
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ResolvedSkill skill = skill("meeting-skill", "database", true);
        String body = "# Contract\n" + "instruction line\n".repeat(500) + "\nFINAL_MANDATORY_SECTION";
        skill.setContent(body);
        when(runtimeService.findActiveSkill(eq("meeting-skill"), any())).thenReturn(skill);

        String content = tool.readSkillFile("meeting-skill", "SKILL.md", null, null, null);

        assertTrue(body.length() > 8_000, "fixture must exceed the 8KB cap to be a valid regression");
        assertEquals(body, content, "SKILL.md under the ceiling must be returned byte-for-byte");
        assertTrue(content.contains("FINAL_MANDATORY_SECTION"),
                "the trailing mandatory section must survive — losing it is what caused fabrication");
        assertFalse(content.contains("truncated"), "no truncation banner may be injected");
    }

    @Test
    @DisplayName("SKILL.md past the full-return ceiling degrades to resumable pagination, never a middle-cut")
    void readSkillFileOversizedSkillMdPaginatesResumably() {
        SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
        SkillFileAccessPolicy accessPolicy = mock(SkillFileAccessPolicy.class);
        SkillUsageService usageService = mock(SkillUsageService.class);
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        when(workspaceResolver.resolve(any())).thenReturn(1L);
        SkillFileTool tool = new SkillFileTool(runtimeService, accessPolicy, usageService, workspaceResolver);
        ResolvedSkill skill = skill("giant-skill", "database", true);
        skill.setContent("instruction line\n".repeat(4000)); // ~68KB, past the 32KB ceiling
        when(runtimeService.findActiveSkill(eq("giant-skill"), any())).thenReturn(skill);

        String content = tool.readSkillFile("giant-skill", "SKILL.md", null, null, null);

        assertTrue(content.startsWith("instruction line"), "page one must begin at the top of the contract");
        assertTrue(content.contains("startLine="),
                "must hand back a resumable continuation cursor so the model can finish the read");
        assertFalse(content.contains("middle omitted"),
                "degradation must stay resumable — never a lossy middle-cut");
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
