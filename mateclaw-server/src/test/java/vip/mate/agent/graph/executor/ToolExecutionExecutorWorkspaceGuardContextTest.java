package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;
import vip.mate.tool.guard.service.ToolGuardService;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionExecutorWorkspaceGuardContextTest {

    @Test
    @DisplayName("guard evaluation receives state workspaceBasePath when origin has no base path (#617)")
    void guardUsesWorkspaceBasePathArgumentWhenOriginIsBlank(@TempDir Path workspaceRoot) {
        ToolGuardService guardService = mock(ToolGuardService.class);
        when(guardService.evaluate(any(ToolInvocationContext.class), eq(true)))
                .thenReturn(GuardEvaluation.allow("write_file"));
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(stub("write_file"))),
                guardService,
                null,
                null);

        executor.execute(
                List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "write_file",
                        "{\"filePath\":\"deck.md\",\"content\":\"# Deck\"}")),
                "conv-617",
                "agent-617",
                false,
                "alice",
                workspaceRoot.toString(),
                ChatOrigin.web("conv-617", "alice", 1L, null));

        ArgumentCaptor<ToolInvocationContext> captor = ArgumentCaptor.forClass(ToolInvocationContext.class);
        verify(guardService).evaluate(captor.capture(), eq(true));
        assertThat(captor.getValue().workspaceBasePath()).isEqualTo(workspaceRoot.toString());
    }

    private static ToolCallback stub(String name) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description("test tool " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        ToolMetadata md = ToolMetadata.builder().returnDirect(false).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public ToolMetadata getToolMetadata() { return md; }
            @Override public String call(String arguments) { return "ok"; }
            @Override public String call(String arguments, ToolContext toolContext) { return "ok"; }
        };
    }
}
