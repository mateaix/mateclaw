package vip.mate.agent.graph.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionExecutorCancellationTest {

    @Test
    @DisplayName("Stop interrupts an in-flight synchronous tool instead of only disposing the outer stream")
    void stopInterruptsActiveToolThread() throws Exception {
        String conversationId = "cancel-tool-conversation";
        ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
        tracker.register(conversationId);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ToolCallback blockingTool = blockingTool(entered, interrupted);
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(blockingTool));
        ToolGuard alwaysAllow = (name, arguments) -> ToolGuardResult.allow();
        ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, alwaysAllow, null, tracker);
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "blocking_tool", "{}");

        CompletableFuture<?> execution = CompletableFuture.runAsync(() ->
                executor.execute(List.of(call), conversationId, "agent-1", false));

        assertTrue(entered.await(2, TimeUnit.SECONDS), "tool callback should have started");
        assertTrue(tracker.requestStop(conversationId), "active run should accept Stop");
        assertTrue(interrupted.await(2, TimeUnit.SECONDS), "Stop must interrupt the tool thread");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> execution.get(2, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, error.getCause());
    }

    private static ToolCallback blockingTool(CountDownLatch entered, CountDownLatch interrupted) {
        ToolDefinition definition = ToolDefinition.builder()
                .name("blocking_tool")
                .description("blocks until interrupted")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }
            @Override public String call(String arguments) { return runBlocking(); }
            @Override public String call(String arguments, ToolContext toolContext) { return runBlocking(); }

            private String runBlocking() {
                entered.countDown();
                try {
                    Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                    return "unexpected completion";
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    return "cancelled";
                }
            }
        };
    }
}
