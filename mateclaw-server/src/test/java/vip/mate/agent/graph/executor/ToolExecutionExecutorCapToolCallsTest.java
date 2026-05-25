package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-response tool_calls cap that protects the executor
 * against runaway batch sizes from misbehaving models
 * (StreamLake / kat-coder-pro-v1 emit 50+ in one shot).
 *
 * <p>This is a pure unit test on the package-private static helper; no
 * Spring context, no mocks. The behavior under cap matters most for two
 * cases: (1) the LLM must still receive paired tool responses for every
 * dropped tool_call (some providers reject otherwise), and (2) the order
 * of executed calls must remain stable so the agent's logic isn't
 * reshuffled by the cap.
 */
class ToolExecutionExecutorCapToolCallsTest {

    private static AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    private static List<AssistantMessage.ToolCall> sequentialCalls(int n) {
        List<AssistantMessage.ToolCall> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(call("call_" + i, "tool_" + i));
        }
        return out;
    }

    // ── Pass-through cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("null input returns empty list, no truncation")
    void nullInputPassesThrough() {
        ToolExecutionExecutor.CappedToolCalls capped =
                ToolExecutionExecutor.capToolCalls(null, 16);

        assertNotNull(capped);
        assertNotNull(capped.effective());
        assertTrue(capped.effective().isEmpty());
        assertTrue(capped.truncatedResponses().isEmpty());
        assertFalse(capped.wasTruncated());
    }

    @Test
    @DisplayName("empty input is returned untouched")
    void emptyInputPassesThrough() {
        List<AssistantMessage.ToolCall> input = List.of();
        ToolExecutionExecutor.CappedToolCalls capped =
                ToolExecutionExecutor.capToolCalls(input, 16);

        assertSame(input, capped.effective(), "no copy when within cap");
        assertTrue(capped.truncatedResponses().isEmpty());
        assertFalse(capped.wasTruncated());
    }

    @Test
    @DisplayName("size at cap is returned untouched (boundary)")
    void atCapPassesThrough() {
        List<AssistantMessage.ToolCall> input = sequentialCalls(16);
        ToolExecutionExecutor.CappedToolCalls capped =
                ToolExecutionExecutor.capToolCalls(input, 16);

        assertSame(input, capped.effective(),
                "at-cap input must not be sublist'd — surprising allocation");
        assertTrue(capped.truncatedResponses().isEmpty());
        assertFalse(capped.wasTruncated());
    }

    @Test
    @DisplayName("size below cap is returned untouched")
    void belowCapPassesThrough() {
        List<AssistantMessage.ToolCall> input = sequentialCalls(5);
        ToolExecutionExecutor.CappedToolCalls capped =
                ToolExecutionExecutor.capToolCalls(input, 16);

        assertSame(input, capped.effective());
        assertFalse(capped.wasTruncated());
    }

    // ── Truncation cases ───────────────────────────────────────────────────────

    @Test
    @DisplayName("over-cap input is trimmed; first N kept in original order")
    void overCapTrimmed() {
        List<AssistantMessage.ToolCall> input = sequentialCalls(20);
        ToolExecutionExecutor.CappedToolCalls capped =
                ToolExecutionExecutor.capToolCalls(input, 16);

        assertTrue(capped.wasTruncated());
        assertEquals(16, capped.effective().size());
        // Order preservation matters — the agent's reasoning may depend on
        // the LLM's chosen sequence (e.g. read-then-write); reshuffling the
        // first-N is silently breaking.
        for (int i = 0; i < 16; i++) {
            assertEquals("call_" + i, capped.effective().get(i).id());
        }
    }

    @Test
    @DisplayName("each dropped tool_call gets a synthetic ToolResponseMessage with matching id")
    void droppedCallsGetTruncatedResponses() {
        List<AssistantMessage.ToolCall> input = sequentialCalls(20);
        ToolExecutionExecutor.CappedToolCalls capped =
                ToolExecutionExecutor.capToolCalls(input, 16);

        // 4 dropped calls (indices 16..19) → 4 synthetic responses.
        assertEquals(4, capped.truncatedResponses().size());

        for (int i = 0; i < 4; i++) {
            ToolResponseMessage.ToolResponse resp = capped.truncatedResponses().get(i);
            assertEquals("call_" + (16 + i), resp.id(),
                    "synthetic response must reuse the dropped tool_call's id "
                            + "or providers will reject the next turn");
            assertEquals("tool_" + (16 + i), resp.name());
            assertTrue(resp.responseData().contains("[truncated]"),
                    "response body must signal truncation so the LLM can reissue");
        }
    }

    @Test
    @DisplayName("synthetic response body mentions both requested and cap counts")
    void truncatedResponseBodyExplainsCounts() {
        List<AssistantMessage.ToolCall> input = sequentialCalls(50);
        ToolExecutionExecutor.CappedToolCalls capped =
                ToolExecutionExecutor.capToolCalls(input, 16);

        String body = capped.truncatedResponses().get(0).responseData();
        assertTrue(body.contains("50"), "body should mention requested count: " + body);
        assertTrue(body.contains("16"), "body should mention cap value: " + body);
    }

    @Test
    @DisplayName("custom cap value honored — same logic at any threshold")
    void customCapHonored() {
        List<AssistantMessage.ToolCall> input = sequentialCalls(10);
        ToolExecutionExecutor.CappedToolCalls capped =
                ToolExecutionExecutor.capToolCalls(input, 3);

        assertTrue(capped.wasTruncated());
        assertEquals(3, capped.effective().size());
        assertEquals(7, capped.truncatedResponses().size());
    }
}
