package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.agent.AgentToolSet;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Issue #46: when the LLM mis-calls a skill name as a tool, the executor
 * should return a precise hint explaining that the name is a Skill (not a
 * Tool) and how to invoke it via {@code readSkillFile} — instead of the
 * dead-end "Tool not found" string that gave the model nothing to act on.
 */
class ToolExecutionExecutorSkillHintTest {

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

    @Test
    @DisplayName("issue#46: tool name matching an active skill yields skill-aware hint")
    void unknownToolMatchingSkill_returnsHint() {
        ToolExecutionExecutor executor = newExecutor(); // empty tool set
        executor.setSkillRuntimeService(skillRuntimeWith("RedisOps", "browser_cdp"));

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(new AssistantMessage.ToolCall("call_1", "function", "RedisOps", "{}")),
                "conv", "agent", false, "user", null);

        assertEquals(1, result.responses().size());
        String response = result.responses().get(0).responseData();
        assertTrue(response.contains("Skill, not a Tool"),
                "Response should declare the name is a Skill: " + response);
        assertTrue(response.contains("readSkillFile(skillName=\"RedisOps\""),
                "Response should suggest the concrete invocation: " + response);
        assertFalse(response.equals("Tool not found: RedisOps"),
                "Response should NOT fall back to the bare error string");
    }

    @Test
    @DisplayName("issue#46: case-insensitive skill match — LLMs sometimes alter casing")
    void unknownToolCaseInsensitiveSkillMatch_returnsHint() {
        ToolExecutionExecutor executor = newExecutor();
        executor.setSkillRuntimeService(skillRuntimeWith("RedisOps"));

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(new AssistantMessage.ToolCall("call_2", "function", "redisops", "{}")),
                "conv", "agent", false, "user", null);

        String response = result.responses().get(0).responseData();
        assertTrue(response.contains("Skill, not a Tool"),
                "Lowercase 'redisops' should still match active skill 'RedisOps': " + response);
    }

    @Test
    @DisplayName("issue#46: tool name not matching any skill keeps the bare 'Tool not found' message")
    void unknownToolWithNoSkillMatch_keepsBareError() {
        ToolExecutionExecutor executor = newExecutor();
        executor.setSkillRuntimeService(skillRuntimeWith("RedisOps", "browser_cdp"));

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(new AssistantMessage.ToolCall("call_3", "function", "totally_made_up_tool", "{}")),
                "conv", "agent", false, "user", null);

        String response = result.responses().get(0).responseData();
        assertEquals("Tool not found: totally_made_up_tool", response,
                "When the name doesn't match any skill, the executor must fall back to the bare error");
    }

    @Test
    @DisplayName("issue#46: when skillRuntimeService is unset (legacy/test path), behavior is unchanged")
    void unknownToolWithoutSkillRuntime_keepsBareError() {
        ToolExecutionExecutor executor = newExecutor();
        // intentionally do NOT call setSkillRuntimeService

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(new AssistantMessage.ToolCall("call_4", "function", "RedisOps", "{}")),
                "conv", "agent", false, "user", null);

        String response = result.responses().get(0).responseData();
        assertEquals("Tool not found: RedisOps", response,
                "Without a wired SkillRuntimeService, the executor must keep the legacy bare error");
    }

    @Test
    @DisplayName("issue#46: pre-approved replay path also gets the skill-aware hint")
    void preApprovedReplayUnknownTool_returnsHint() {
        ToolExecutionExecutor executor = newExecutor();
        executor.setSkillRuntimeService(skillRuntimeWith("RedisOps"));

        java.util.List<vip.mate.agent.GraphEventPublisher.GraphEvent> events = new java.util.ArrayList<>();
        var response = executor.executePreApproved(
                new AssistantMessage.ToolCall("call_5", "function", "RedisOps", "{}"),
                "{}", events, "conv", null);

        assertTrue(response.responseData().contains("Skill, not a Tool"),
                "Pre-approved replay should also produce the skill-aware hint: " + response.responseData());
    }
}
