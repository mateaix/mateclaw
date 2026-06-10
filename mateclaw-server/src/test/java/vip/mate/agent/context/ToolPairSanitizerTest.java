package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolPairSanitizer} is the single source of truth for the
 * tool_call ↔ tool_response pairing invariant. These tests exercise the
 * three public methods directly against constructed message lists — the
 * sanitizer is a pure-function utility, so there is no Spring context
 * involved.
 */
class ToolPairSanitizerTest {

    // ---- isPaired() --------------------------------------------------------

    @Test
    @DisplayName("isPaired: empty list is trivially paired")
    void isPaired_empty() {
        assertTrue(ToolPairSanitizer.isPaired(List.of()));
        assertTrue(ToolPairSanitizer.isPaired(null));
    }

    @Test
    @DisplayName("isPaired: matched call and response are paired")
    void isPaired_matched() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new UserMessage("u"));
        msgs.add(asst("c1"));
        msgs.add(resp("c1"));
        assertTrue(ToolPairSanitizer.isPaired(msgs));
    }

    @Test
    @DisplayName("isPaired: orphan response detected")
    void isPaired_orphanResponse() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("u"));
        msgs.add(resp("c1"));  // no preceding call
        assertFalse(ToolPairSanitizer.isPaired(msgs));
    }

    @Test
    @DisplayName("isPaired: orphan call detected")
    void isPaired_orphanCall() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(asst("c1"));  // no following response
        assertFalse(ToolPairSanitizer.isPaired(msgs));
    }

    @Test
    @DisplayName("isPaired: null/empty id rejected as unpaired")
    void isPaired_nullId() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(null, "function", "t", "{}")))
                .build());
        assertFalse(ToolPairSanitizer.isPaired(msgs),
                "an assistant tool_call with a null id can never be paired");

        msgs.clear();
        msgs.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("", "tool_x", "ok"))).build());
        assertFalse(ToolPairSanitizer.isPaired(msgs),
                "a tool_response with an empty id can never be paired");
    }

    // ---- pullBackToToolPairBoundary() --------------------------------------

    @Test
    @DisplayName("pullBack: boundary inside a pair is moved before the assistant")
    void pullBack_pairAtBoundary() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));     // 0
        msgs.add(new AssistantMessage("pre"));  // 1
        msgs.add(asst("c1"));                   // 2 — assistant tool_call
        msgs.add(resp("c1"));                   // 3 — its response
        msgs.add(new AssistantMessage("post"));  // 4

        // Proposed boundary at idx 3 would drop the assistant (idx 2) and
        // keep the response (idx 3) → orphan. Pull-back should move the
        // boundary back to idx 2 so the pair survives whole.
        int adjusted = ToolPairSanitizer.pullBackToToolPairBoundary(msgs, 1, 3);
        assertEquals(2, adjusted);
    }

    @Test
    @DisplayName("pullBack: boundary clear of any pair stays put")
    void pullBack_noPairOverlap() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new AssistantMessage("a"));
        msgs.add(new AssistantMessage("b"));
        msgs.add(new AssistantMessage("c"));
        int adjusted = ToolPairSanitizer.pullBackToToolPairBoundary(msgs, 1, 2);
        assertEquals(2, adjusted, "no tool pairs → boundary unchanged");
    }

    @Test
    @DisplayName("pullBack: multi-call assistant with split responses pulls back across all of them")
    void pullBack_multiCallAssistant() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(asstMulti("c1", "c2", "c3"));  // idx 1 — three calls
        msgs.add(resp("c1"));                    // idx 2
        msgs.add(resp("c2"));                    // idx 3
        msgs.add(resp("c3"));                    // idx 4

        // Boundary at idx 3 would keep c2 and c3 responses, orphaning them
        // because their assistant (idx 1) would be dropped.
        int adjusted = ToolPairSanitizer.pullBackToToolPairBoundary(msgs, 0, 3);
        assertEquals(1, adjusted,
                "boundary pulled to idx 1 so the multi-call assistant + all its responses survive");
    }

    // ---- removeOrphans() ---------------------------------------------------

    @Test
    @DisplayName("removeOrphans: orphan response with no matching call is removed")
    void removeOrphans_orphanResponse() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("u"));
        msgs.add(resp("c1"));  // orphan — no preceding call

        int removed = ToolPairSanitizer.removeOrphans(msgs);

        assertEquals(1, removed);
        assertEquals(1, msgs.size());
        assertTrue(ToolPairSanitizer.isPaired(msgs));
    }

    @Test
    @DisplayName("removeOrphans: assistant with NO matching responses removed")
    void removeOrphans_orphanAssistant() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("u"));
        msgs.add(asst("c1"));  // orphan — no following response

        int removed = ToolPairSanitizer.removeOrphans(msgs);

        assertEquals(1, removed);
        assertTrue(ToolPairSanitizer.isPaired(msgs));
    }

    @Test
    @DisplayName("removeOrphans: assistant with PARTIAL matches is kept (lenient policy)")
    void removeOrphans_partialMatchKept() {
        // Lenient: assistant has c1 (matched) and c2 (orphan). Keep the
        // whole assistant to preserve the matched pair; let strict provider
        // dedup-handle the extra call rather than risking dropping useful
        // history.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("u"));
        msgs.add(asstMulti("c1", "c2"));
        msgs.add(resp("c1"));

        int removed = ToolPairSanitizer.removeOrphans(msgs);

        assertEquals(0, removed,
                "an assistant with at least one matched call survives — partial-match lenient policy");
        assertEquals(3, msgs.size());
    }

    @Test
    @DisplayName("removeOrphans: iterative — removing P1 reveals P0, both cleared")
    void removeOrphans_iterativeConvergence() {
        // Build a list where removing an orphan assistant (P1) leaves a now-
        // dangling response (P0) that the next pass must also remove.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("u"));
        msgs.add(asst("c1"));  // P1 — no response
        msgs.add(resp("c2"));  // P0 — no call (independent of c1)

        int removed = ToolPairSanitizer.removeOrphans(msgs);

        assertEquals(2, removed);
        assertTrue(ToolPairSanitizer.isPaired(msgs));
        assertEquals(1, msgs.size());
    }

    @Test
    @DisplayName("removeOrphans: matched pair untouched")
    void removeOrphans_noOpOnCleanList() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new UserMessage("u"));
        msgs.add(asst("c1"));
        msgs.add(resp("c1"));
        msgs.add(new AssistantMessage("final"));

        int removed = ToolPairSanitizer.removeOrphans(msgs);

        assertEquals(0, removed);
        assertEquals(4, msgs.size());
        assertTrue(ToolPairSanitizer.isPaired(msgs));
    }

    // ---- helpers -----------------------------------------------------------

    private static AssistantMessage asst(String callId) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall(callId, "function", "tool_" + callId, "{}")))
                .build();
    }

    private static AssistantMessage asstMulti(String... callIds) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String id : callIds) {
            calls.add(new AssistantMessage.ToolCall(id, "function", "tool_" + id, "{}"));
        }
        return AssistantMessage.builder().content("").toolCalls(calls).build();
    }

    private static ToolResponseMessage resp(String callId) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, "tool_" + callId, "ok"))).build();
    }
}
