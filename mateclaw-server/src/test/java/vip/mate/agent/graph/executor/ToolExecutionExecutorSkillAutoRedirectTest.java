package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Auto-redirect: when the LLM mistakenly calls a skill name as if it were
 * a tool, the executor should transparently invoke {@code readSkillFile}
 * on its behalf and return the SKILL.md content as the tool result.
 *
 * <p>Why: smaller models (qwen-turbo et al.) often can't act on a
 * "this is a Skill, not a Tool — go read X first" textual hint. They
 * generate a polite "let me get that" reply and end the turn without
 * any further tool call, leaving the user stuck. With auto-redirect
 * the model receives runnable instructions on its very first attempt.
 *
 * <p>The hint-only path is still preserved for the case where
 * {@code readSkillFile} isn't bound to the agent (covered by
 * {@link ToolExecutionExecutorSkillHintTest}).
 */
class ToolExecutionExecutorSkillAutoRedirectTest {

    private static final String SKILL_MD =
            "---\nname: tencent-meeting-mcp\n---\n\n# Quick start\nrunSkillScript scripts/setup.sh\n";

    private ToolExecutionExecutor newExecutor(ToolCallback... callbacks) {
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(callbacks));
        ToolGuard alwaysAllow = (n, a) -> ToolGuardResult.allow();
        return new ToolExecutionExecutor(toolSet, alwaysAllow, null, null);
    }

    private SkillRuntimeService skillRuntimeWith(String... activeNames) {
        SkillRuntimeService svc = mock(SkillRuntimeService.class);
        List<ResolvedSkill> skills = java.util.Arrays.stream(activeNames).map(name -> {
            ResolvedSkill s = mock(ResolvedSkill.class);
            when(s.getName()).thenReturn(name);
            return s;
        }).toList();
        when(svc.getActiveSkills()).thenReturn(skills);
        return svc;
    }

    /** Captures the args that the auto-redirected readSkillFile receives. */
    private static class CapturingReadSkillFile {
        final AtomicReference<String> lastArgs = new AtomicReference<>();
        final ToolCallback callback;

        CapturingReadSkillFile(String returnContent) {
            ToolDefinition def = ToolDefinition.builder()
                    .name("readSkillFile")
                    .description("test stub")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            ToolMetadata md = ToolMetadata.builder().returnDirect(false).build();
            callback = new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return def; }
                @Override public ToolMetadata getToolMetadata() { return md; }
                @Override public String call(String arguments) {
                    lastArgs.set(arguments);
                    return returnContent;
                }
                @Override public String call(String arguments, ToolContext ctx) {
                    return call(arguments);
                }
            };
        }
    }

    @Test
    @DisplayName("skill-as-tool call gets auto-redirected to readSkillFile when the tool is bound")
    void skillCallAutoRedirects() {
        CapturingReadSkillFile rsf = new CapturingReadSkillFile(SKILL_MD);
        ToolExecutionExecutor executor = newExecutor(rsf.callback);
        executor.setSkillRuntimeService(skillRuntimeWith("tencent-meeting-mcp"));

        String llmArgs = "{\"action\":\"create\",\"subject\":\"AI讨论会\"}";
        var result = executor.execute(
                List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "tencent-meeting-mcp", llmArgs)),
                "conv", "agent", false, "user", null);

        assertEquals(1, result.responses().size());
        String response = result.responses().get(0).responseData();

        // (1) readSkillFile was invoked with the skill's name and SKILL.md
        String forwarded = rsf.lastArgs.get();
        assertNotNull(forwarded, "readSkillFile must have been invoked transparently");
        assertTrue(forwarded.contains("\"skillName\":\"tencent-meeting-mcp\""), forwarded);
        assertTrue(forwarded.contains("\"filePath\":\"SKILL.md\""), forwarded);

        // (2) Response carries the SKILL.md content
        assertTrue(response.contains("# Quick start"),
                "Response should embed SKILL.md content: " + response);
        assertTrue(response.contains("runSkillScript scripts/setup.sh"),
                "Response should embed the runnable example from SKILL.md");

        // (3) Response carries the [auto-redirect] nudge so the LLM understands
        //     why it didn't get a function-call result of the shape it expected
        assertTrue(response.contains("[auto-redirect]"),
                "Response should declare the auto-redirect: " + response);
        assertTrue(response.contains("runSkillScript"),
                "Response should tell the LLM what to call next");

        // (4) Original payload is echoed back so the LLM doesn't have to re-derive
        //     args before calling runSkillScript
        assertTrue(response.contains("AI讨论会"),
                "Original LLM args should be echoed in the redirect: " + response);
    }

    @Test
    @DisplayName("skill-as-tool call falls through to hint when readSkillFile is NOT bound to this agent")
    void skillCallWithoutReadSkillFileFallsThroughToHint() {
        // Empty tool set — readSkillFile not registered for this agent
        ToolExecutionExecutor executor = newExecutor();
        executor.setSkillRuntimeService(skillRuntimeWith("tencent-meeting-mcp"));

        var result = executor.execute(
                List.of(new AssistantMessage.ToolCall(
                        "call_2", "function", "tencent-meeting-mcp", "{}")),
                "conv", "agent", false, "user", null);

        String response = result.responses().get(0).responseData();
        assertTrue(response.contains("Skill, not a Tool"),
                "Without readSkillFile, executor must fall back to the textual hint: " + response);
        assertFalse(response.contains("[auto-redirect]"),
                "No redirect should have happened: " + response);
    }

    @Test
    @DisplayName("non-skill unknown tool name still produces the bare 'Tool not found' message")
    void unknownToolKeepsBareError() {
        CapturingReadSkillFile rsf = new CapturingReadSkillFile(SKILL_MD);
        ToolExecutionExecutor executor = newExecutor(rsf.callback);
        executor.setSkillRuntimeService(skillRuntimeWith("tencent-meeting-mcp"));

        var result = executor.execute(
                List.of(new AssistantMessage.ToolCall(
                        "call_3", "function", "made_up_tool", "{}")),
                "conv", "agent", false, "user", null);

        String response = result.responses().get(0).responseData();
        assertEquals("Tool not found: made_up_tool", response);
        assertNull(rsf.lastArgs.get(),
                "readSkillFile must NOT be invoked for non-skill names");
    }

    @Test
    @DisplayName("skill name with special chars in the LLM args is JSON-escaped before forwarding")
    void specialCharsInArgsAreEscaped() {
        CapturingReadSkillFile rsf = new CapturingReadSkillFile(SKILL_MD);
        ToolExecutionExecutor executor = newExecutor(rsf.callback);
        // Skill name with double quotes / backslash to verify the inline JSON
        // we build for the readSkillFile call escapes them properly.
        executor.setSkillRuntimeService(skillRuntimeWith("weird\"name\\skill"));

        var result = executor.execute(
                List.of(new AssistantMessage.ToolCall(
                        "call_4", "function", "weird\"name\\skill", "{}")),
                "conv", "agent", false, "user", null);

        // If escaping were broken, readSkillFile would have rejected the
        // malformed JSON and returned an error. The response carrying SKILL_MD
        // proves the forwarded args parsed cleanly.
        assertTrue(result.responses().get(0).responseData().contains("# Quick start"));
        String forwarded = rsf.lastArgs.get();
        assertTrue(forwarded.contains("weird\\\"name\\\\skill"),
                "Forwarded args should JSON-escape quotes and backslashes: " + forwarded);
    }
}
