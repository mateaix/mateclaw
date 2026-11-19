package vip.mate.agent.graph.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import vip.mate.tool.ToolInputValidationException;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.mcp.runtime.McpProgressContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class NestedToolContextTest {
    @Test
    void nestedSuccessRestoresOuterAndCaller() {
        run(false, false);
    }

    @Test
    void nestedFailureRestoresOuterAndCaller() {
        run(true, false);
    }

    @Test
    void approvedNestedRestoresOuterAndCaller() {
        run(false, true);
    }

    @Test
    void approvedErrorRestoresOuterAndCaller() {
        run(true, true);
    }

    @Test
    void parallelCallbackErrorRemainsFatalAndMarksUnknown() {
        var fatal = new AssertionError("fatal boundary fixture");
        ToolCallback first = callback("first"), second = callback("second");
        when(first.call(anyString(), any(ToolContext.class))).thenThrow(fatal);
        when(second.call(anyString(), any(ToolContext.class))).thenReturn("second result");
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(first, second)),
                        (n, a) -> ToolGuardResult.allow(),
                        null,
                        null);
        var checkpoint = mock(ToolExecutionCheckpoint.class);
        executor.setExecutionCheckpoint(checkpoint);
        var failure =
                assertThrows(
                        AssertionError.class,
                        () ->
                                executor.execute(
                                        List.of(
                                                new AssistantMessage.ToolCall(
                                                        "first-id", "function", "first", "{}"),
                                                new AssistantMessage.ToolCall(
                                                        "second-id", "function", "second", "{}")),
                                        "parallel",
                                        "2",
                                        false,
                                        "user",
                                        null,
                                        ChatOrigin.web("parallel", "user", 3L, null),
                                        Set.of()));
        assertSame(fatal, failure);
        verify(checkpoint).markUnknownOutcome(any(ChatOrigin.class), same(fatal));
    }

    @Test
    void normalPublicationFailureMarksUncertaintyBeforeRetry() {
        var callback = callback("write_effect");
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("committed result");
        var tracker = mock(ChatStreamTracker.class);
        when(tracker.registerCancellationHook(anyString(), any(Runnable.class)))
                .thenReturn(() -> {});
        var failures = new AtomicInteger();
        doAnswer(
                        i -> {
                            if (i.getArgument(1, GraphEventPublisher.GraphEvent.class).type()
                                            .equals(GraphEventPublisher.EVENT_TOOL_COMPLETE)
                                    && failures.getAndIncrement() == 0)
                                throw new IllegalStateException("lost result publication");
                            return null;
                        })
                .when(tracker)
                .broadcastLiveToolEvent(anyString(), any(GraphEventPublisher.GraphEvent.class));
        var executor =
                new ToolExecutionExecutor(
                        AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                        (n, a) -> ToolGuardResult.allow(),
                        null,
                        tracker);
        var checkpoint = mock(ToolExecutionCheckpoint.class);
        executor.setExecutionCheckpoint(checkpoint);
        var unknown = new AtomicBoolean();
        doAnswer(
                        i -> {
                            unknown.set(true);
                            return null;
                        })
                .when(checkpoint)
                .markUnknownOutcome(any(), any());
        doAnswer(
                        i -> {
                            if (unknown.get())
                                throw new ToolExecutionCheckpointRejectedException(
                                        "unknown result blocks replay");
                            return null;
                        })
                .when(checkpoint)
                .beforeInvocation(any(), anyString(), anyString());
        executor.execute(
                List.of(
                        new AssistantMessage.ToolCall(
                                "write-id", "function", "write_effect", "{}")),
                "conv",
                "2",
                false,
                "user",
                null,
                ChatOrigin.web("conv", "user", 3L, null),
                Set.of());
        verify(checkpoint)
                .markUnknownOutcome(any(ChatOrigin.class), any(IllegalStateException.class));
        assertThrows(
                ToolExecutionCheckpointRejectedException.class,
                () ->
                        executor.execute(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "retry-id", "function", "write_effect", "{}")),
                                "conv",
                                "2",
                                false,
                                "user",
                                null,
                                ChatOrigin.web("conv", "user", 3L, null),
                                Set.of()));
        verify(callback, times(1)).call(anyString(), any(ToolContext.class));
    }

    @Test
    void approvedPublicationFailureMarksUncertaintyBeforeRetry() {
        var callback = callback("write_effect");
        when(callback.call(anyString(), any(ToolContext.class))).thenReturn("committed result");
        var executor = executor(callback);
        var checkpoint = mock(ToolExecutionCheckpoint.class);
        executor.setExecutionCheckpoint(checkpoint);
        var failures = new AtomicInteger();
        var events =
                new ArrayList<GraphEventPublisher.GraphEvent>() {
                    @Override
                    public boolean add(GraphEventPublisher.GraphEvent value) {
                        if (failures.getAndIncrement() == 0)
                            throw new IllegalStateException("lost result publication");
                        return super.add(value);
                    }
                };
        executor.executePreApproved(
                new AssistantMessage.ToolCall("write-id", "function", "write_effect", "{}"),
                "{}",
                events,
                "conv",
                null,
                new ArrayList<>(),
                ChatOrigin.web("conv", "user", 3L, null));
        verify(checkpoint)
                .markUnknownOutcome(any(ChatOrigin.class), any(IllegalStateException.class));
        verify(callback, times(1)).call(anyString(), any(ToolContext.class));
    }

    @Test
    void typedValidationRemainsCorrectable() {
        validation(false, false);
    }

    @Test
    void wrappedTypedValidationRemainsCorrectable() {
        validation(true, false);
    }

    @Test
    void approvedTypedValidationRemainsCorrectable() {
        validation(false, true);
    }

    private void validation(boolean wrapped, boolean approved) {
        var callback = callback("render_docx");
        var validation = new ToolInputValidationException("markdown is blank");
        when(callback.call(anyString(), any(ToolContext.class)))
                .thenThrow(wrapped ? new IllegalStateException("wrapper", validation) : validation);
        var executor = executor(callback);
        var checkpoint = mock(ToolExecutionCheckpoint.class);
        executor.setExecutionCheckpoint(checkpoint);
        var call = new AssistantMessage.ToolCall("validation-id", "function", "render_docx", "{}");
        var origin = ChatOrigin.web("conv", "user", 3L, null);
        var result =
                approved
                        ? executor.executePreApproved(
                                call,
                                "{}",
                                new ArrayList<>(),
                                "conv",
                                null,
                                new ArrayList<>(),
                                origin)
                        : executor.execute(
                                        List.of(call),
                                        "conv",
                                        "2",
                                        false,
                                        "user",
                                        null,
                                        origin,
                                        Set.of())
                                .responses()
                                .getFirst();
        assertEquals("Tool input validation failed: markdown is blank", result.responseData());
        verify(checkpoint, never()).markUnknownOutcome(any(), any());
    }

    @Test
    void progressCleanupFailureRestoresCallerAndMarksUnknown() {
        var caller = ChatOrigin.web("caller", "parent", 3L, "/parent");
        ToolExecutionContext.set("caller", "parent", "/parent");
        ToolExecutionContext.setOrigin(caller);
        try {
            var callback = callback("progress_tool");
            when(callback.call(anyString(), any(ToolContext.class))).thenReturn("committed result");
            var executor = executor(callback);
            var checkpoint = mock(ToolExecutionCheckpoint.class);
            executor.setExecutionCheckpoint(checkpoint);
            var progress = mock(McpProgressContext.class);
            doThrow(new IllegalStateException("progress cleanup failed"))
                    .when(progress)
                    .remove(anyString());
            executor.setProgressContext(progress);
            executor.execute(
                    List.of(
                            new AssistantMessage.ToolCall(
                                    "progress-id", "function", "progress_tool", "{}")),
                    "conv",
                    "2",
                    false,
                    "user",
                    null,
                    ChatOrigin.web("conv", "user", 3L, null),
                    Set.of());
            assertSame(caller, ToolExecutionContext.origin());
            assertEquals("caller", ToolExecutionContext.conversationId());
            assertEquals("parent", ToolExecutionContext.username());
            assertEquals("/parent", ToolExecutionContext.workspaceBasePath());
            verify(checkpoint)
                    .markUnknownOutcome(any(ChatOrigin.class), any(IllegalStateException.class));
            verify(callback, times(1)).call(anyString(), any(ToolContext.class));
        } finally {
            ToolExecutionContext.clear();
        }
    }

    private void run(boolean fail, boolean approved) {
        var caller = ChatOrigin.web("caller", "caller-user", 9L, null);
        ToolExecutionContext.set(null, "caller-user", null);
        ToolExecutionContext.setOrigin(caller);
        try {
            ToolCallback inner = callback("inner");
            when(inner.call(anyString(), any(ToolContext.class)))
                    .thenAnswer(
                            i -> {
                                assertEquals("inner-conv", ToolExecutionContext.conversationId());
                                if (fail) throw new IllegalStateException("known fixture failure");
                                return "inner-result";
                            });
            var innerExecutor = executor(inner);
            ToolCallback outer = callback("outer");
            when(outer.call(anyString(), any(ToolContext.class)))
                    .thenAnswer(
                            i -> {
                                var before = ToolExecutionContext.origin();
                                assertEquals("outer-conv", ToolExecutionContext.conversationId());
                                var call =
                                        new AssistantMessage.ToolCall(
                                                "inner-id", "function", "inner", "{}");
                                if (approved)
                                    innerExecutor.executePreApproved(
                                            call,
                                            "{}",
                                            new ArrayList<>(),
                                            "inner-conv",
                                            "/inner",
                                            new ArrayList<>(),
                                            ChatOrigin.web(
                                                    "inner-conv", "inner-user", 3L, "/inner"));
                                else
                                    innerExecutor.execute(
                                            List.of(call),
                                            "inner-conv",
                                            "2",
                                            false,
                                            "inner-user",
                                            "/inner",
                                            ChatOrigin.web(
                                                    "inner-conv", "inner-user", 3L, "/inner"),
                                            Set.of());
                                assertSame(before, ToolExecutionContext.origin());
                                assertEquals("outer-conv", ToolExecutionContext.conversationId());
                                assertEquals("outer-user", ToolExecutionContext.username());
                                assertEquals("/outer", ToolExecutionContext.workspaceBasePath());
                                return "outer-result";
                            });
            var result =
                    executor(outer)
                            .execute(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "outer-id", "function", "outer", "{}")),
                                    "outer-conv",
                                    "2",
                                    false,
                                    "outer-user",
                                    "/outer",
                                    ChatOrigin.web("outer-conv", "outer-user", 3L, "/outer"),
                                    Set.of());
            assertEquals("outer-result", result.responses().getFirst().responseData());
            assertSame(caller, ToolExecutionContext.origin());
            assertNull(ToolExecutionContext.conversationId());
            assertEquals("caller-user", ToolExecutionContext.username());
            assertNull(ToolExecutionContext.workspaceBasePath());
        } finally {
            ToolExecutionContext.clear();
        }
    }

    private ToolCallback callback(String name) {
        var c = mock(ToolCallback.class);
        when(c.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name(name)
                                .description(name)
                                .inputSchema("{}")
                                .build());
        when(c.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        return c;
    }

    private ToolExecutionExecutor executor(ToolCallback c) {
        return new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(c)),
                (n, a) -> ToolGuardResult.allow(),
                null,
                null);
    }
}
