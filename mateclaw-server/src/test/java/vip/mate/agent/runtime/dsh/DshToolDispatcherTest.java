package vip.mate.agent.runtime.dsh;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DshToolDispatcherTest {
    @Test
    void allowedCallRunsHostCallback() {
        ToolCallback callback = callback("read");
        when(callback.call("{\"path\":\"a.txt\"}")).thenReturn("content");
        DshToolDispatcher dispatcher = dispatcher(callback,
                new DshToolPolicy(Path.of("/workspace"), "read-only", SetOf.none(),
                        SetOf.of("read"), SetOf.none(), SetOf.none()));

        DshToolDispatchResult result = dispatcher.dispatch(
                "read", "{\"path\":\"a.txt\"}", Path.of("/workspace/a.txt"));

        assertEquals(DshToolDecision.ALLOW, result.decision());
        assertEquals("content", result.output());
    }

    @Test
    void deniedAndApprovalCallsDoNotRunCallback() {
        ToolCallback callback = callback("edit");
        DshToolDispatcher dispatcher = dispatcher(callback,
                new DshToolPolicy(Path.of("/workspace"), "read-only", SetOf.none(),
                        SetOf.none(), SetOf.of("edit"), SetOf.none()));

        assertEquals(DshToolDecision.DENY,
                dispatcher.dispatch("edit", "{}", Path.of("/workspace/a.txt")).decision());
        assertEquals(DshToolDecision.DENY,
                dispatcher.dispatch("edit", "{}", Path.of("/tmp/a.txt")).decision());
    }

    private static DshToolDispatcher dispatcher(ToolCallback callback, DshToolPolicy policy) {
        return new DshToolDispatcher(List.of(callback), policy, new DshToolPolicyEvaluator());
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema("{}").build());
        return callback;
    }

    private static final class SetOf {
        static java.util.Set<String> none() { return java.util.Set.of(); }
        static java.util.Set<String> of(String value) { return java.util.Set.of(value); }
    }
}
