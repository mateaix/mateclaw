package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tool that requires human approval cannot be resolved in a non-interactive
 * (scheduled-job) run — a pending request would hang the turn until it times out
 * with no answer. The executor must deny such a tool immediately for a cron-origin
 * invocation while still gating it normally for an interactive (web) origin.
 */
class ToolExecutionExecutorNonInteractiveApprovalTest {

    private ToolExecutionExecutor executorRequiringApproval(ToolCallback cb) {
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(cb));
        ToolGuard needsApproval = (name, args) ->
                ToolGuardResult.needsApproval("shell command execution requires approval", "shell_tool_default");
        return new ToolExecutionExecutor(toolSet, needsApproval, null, null);
    }

    @Test
    @DisplayName("cron origin denies an approval-required tool instead of creating an unresolvable pending")
    void cronOriginDeniesApprovalRequiredTool() {
        ToolExecutionExecutor executor = executorRequiringApproval(
                stub("execute_shell_command", args -> "ran"));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "c1", "function", "execute_shell_command", "{\"command\":\"ls\"}");

        ChatOrigin cron = ChatOrigin.cron("conv_cron", null, null, null, null);
        ToolExecutionExecutor.ToolExecutionResult result =
                executor.execute(List.of(call), "conv_cron", "agent_x", false, "system", null, cron);

        assertFalse(result.awaitingApproval(),
                "non-interactive origin must not create a pending approval");
        ToolResponseMessage.ToolResponse resp = result.responses().get(0);
        assertTrue(resp.responseData().contains("[审批不可用]"),
                "cron-origin approval-required tool should be denied with guidance, got: " + resp.responseData());
    }

    private static ToolCallback stub(String name, java.util.function.Function<String, String> handler) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description("test tool " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        ToolMetadata md = ToolMetadata.builder().returnDirect(false).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public ToolMetadata getToolMetadata() { return md; }
            @Override public String call(String arguments) { return handler.apply(arguments); }
            @Override public String call(String arguments, ToolContext toolContext) {
                return handler.apply(arguments);
            }
        };
    }
}
