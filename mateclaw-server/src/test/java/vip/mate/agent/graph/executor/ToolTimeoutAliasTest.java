package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.config.ToolTimeoutProperties;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolTimeoutAliasTest {
    @Test
    void parallelAliasUsesTheSameConfiguredDeadlineAsTheCanonicalCallback() {
        ToolCallback callback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("sendToSubagent").description("continue child")
                        .inputSchema("{}").build();
            }
            public String call(String arguments) {
                try { Thread.sleep(1300); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return "interrupted"; }
                return "child completed";
            }
        };
        var timeouts = new ToolTimeoutProperties();
        timeouts.setDefaultTimeoutSeconds(1);
        timeouts.setPerTool(Map.of("sendToSubagent", 3));
        var executor = new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                null, null, null, timeouts);
        var results = executor.execute(List.of(
                new AssistantMessage.ToolCall("one", "function", "send_to_subagent", "{}"),
                new AssistantMessage.ToolCall("two", "function", "send_to_subagent", "{}")), "conv", "1", false);
        assertEquals(List.of("child completed", "child completed"),
                results.responses().stream().map(r -> r.responseData()).toList());
    }
}
