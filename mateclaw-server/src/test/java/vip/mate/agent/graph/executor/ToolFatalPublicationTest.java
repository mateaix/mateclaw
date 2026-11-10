package vip.mate.agent.graph.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class ToolFatalPublicationTest {
    @Test
    void ordinaryFatalPublicationMarksUnknown() {
        publication(false, false);
    }

    @Test
    void approvedFatalPublicationMarksUnknown() {
        publication(true, false);
    }

    @Test
    void ordinaryFatalPublicationRetainsFatalWhenCheckpointRejects() {
        publication(false, true);
    }

    @Test
    void approvedFatalPublicationRetainsFatalWhenCheckpointRejects() {
        publication(true, true);
    }

    private void publication(boolean approved, boolean rejectsCheckpoint) {
        var fatal = new AssertionError("fatal result publication");
        var rejected = new ToolExecutionCheckpointRejectedException("expired publication owner");
        var effects = new AtomicInteger();
        var unknown = new AtomicInteger();
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("write_effect")
                                .description("Write")
                                .inputSchema("{}")
                                .build());
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenAnswer(
                        invocation -> {
                            effects.incrementAndGet();
                            return "committed result";
                        });
        var tracker = mock(ChatStreamTracker.class);
        when(tracker.registerCancellationHook(anyString(), any(Runnable.class)))
                .thenReturn(() -> {});
        doAnswer(
                        invocation -> {
                            GraphEventPublisher.GraphEvent event = invocation.getArgument(1);
                            if (GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(
                                    event.type())) throw fatal;
                            return null;
                        })
                .when(tracker)
                .broadcastLiveToolEvent(anyString(), any());
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (name, args) -> ToolGuardResult.allow(),
                        null,
                        tracker);
        executor.setExecutionCheckpoint(
                new ToolExecutionCheckpoint() {
                    public void beforeInvocation(ChatOrigin origin, String key, String id) {}

                    public void markUnknownOutcome(ChatOrigin origin, Throwable failure) {
                        assertSame(fatal, failure);
                        unknown.incrementAndGet();
                        if (rejectsCheckpoint) throw rejected;
                    }
                });
        var call = new AssistantMessage.ToolCall("original-id", "function", "write_effect", "{}");
        var events =
                new ArrayList<GraphEventPublisher.GraphEvent>() {
                    @Override
                    public boolean add(GraphEventPublisher.GraphEvent event) {
                        if (GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(event.type()))
                            throw fatal;
                        return super.add(event);
                    }
                };
        assertSame(
                fatal,
                assertThrows(
                        AssertionError.class,
                        () -> {
                            if (approved)
                                executor.executePreApproved(call, "{}", events, "conv", null);
                            else
                                executor.execute(
                                        List.of(call),
                                        "conv",
                                        "2",
                                        false,
                                        "alice",
                                        null,
                                        null,
                                        Set.of());
                        }));
        assertEquals(1, effects.get());
        assertEquals(1, unknown.get());
        if (rejectsCheckpoint) assertSame(rejected, fatal.getSuppressed()[0]);
        else assertEquals(0, fatal.getSuppressed().length);
    }
}
