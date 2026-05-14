package vip.mate.agent;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Head-side pair repair on the recent-message pagination cut.
 *
 * <p>{@code listRecentMessages(conversationId, windowSize)} returns the last N
 * rows verbatim. The first row of that page can be a {@link ToolResponseMessage}
 * whose owning {@link AssistantMessage} (carrying the matching tool_call_id)
 * sat one row earlier — i.e. outside the page. Sending such a sequence to any
 * OpenAI-compatible provider returns 400 because every tool response must be
 * preceded by an assistant message issuing that tool_call_id.
 *
 * <p>{@link BaseAgent#stripHeadOrphanToolResponses} drops leading
 * {@code ToolResponseMessage}s whose response ids are unmatched by every
 * AssistantMessage still in scope. {@link SystemMessage}s (boundary rows,
 * system prompts) at the head are skipped over, not removed.
 */
class BaseAgentHeadOrphanRepairTest {

    @Test
    void orphanToolResponseAtHeadIsDropped() {
        // Window starts with a TOOL response (orphan: no AssistantMessage in this list issued call-X).
        List<Message> messages = new ArrayList<>(List.of(
                toolResponse("call-X"),
                new UserMessage("next user turn"),
                assistantWithToolCalls("call-Y"),
                toolResponse("call-Y")
        ));

        int dropped = BaseAgent.stripHeadOrphanToolResponses(messages, "test");

        assertEquals(1, dropped, "leading orphan should be dropped");
        assertInstanceOf(UserMessage.class, messages.getFirst(),
                "head is now the user turn, not the orphan tool response");
    }

    @Test
    void multipleConsecutiveOrphansAtHeadAllDropped() {
        // A single AssistantMessage outside the window may have produced
        // several tool calls whose responses landed in two separate
        // ToolResponseMessages. Both should be removed.
        List<Message> messages = new ArrayList<>(List.of(
                toolResponse("call-A"),
                toolResponse("call-B"),
                new UserMessage("here we go"),
                assistantWithToolCalls("call-C"),
                toolResponse("call-C")
        ));

        int dropped = BaseAgent.stripHeadOrphanToolResponses(messages, "test");

        assertEquals(2, dropped);
        assertInstanceOf(UserMessage.class, messages.getFirst());
    }

    @Test
    void systemBoundaryAtHeadIsSkippedAndOrphanBehindItIsDropped() {
        // After findLatestCompressionBoundary prepends a SystemMessage, the
        // orphan tool response now sits at index 1. The repair must skip the
        // system row and still drop the orphan.
        SystemMessage boundary = new SystemMessage("[compression boundary placeholder]");
        List<Message> messages = new ArrayList<>(List.of(
                boundary,
                toolResponse("call-X"),
                new UserMessage("after orphan"),
                assistantWithToolCalls("call-Y"),
                toolResponse("call-Y")
        ));

        int dropped = BaseAgent.stripHeadOrphanToolResponses(messages, "test");

        assertEquals(1, dropped);
        assertSame(boundary, messages.getFirst(),
                "the system boundary stays in place");
        assertInstanceOf(UserMessage.class, messages.get(1),
                "the orphan that sat behind the boundary is gone");
    }

    @Test
    void matchedHeadToolResponseIsKept() {
        // The window happens to start with both the AssistantMessage and its
        // tool response — perfectly aligned, nothing to drop.
        List<Message> messages = new ArrayList<>(List.of(
                assistantWithToolCalls("call-A"),
                toolResponse("call-A"),
                new UserMessage("next")
        ));
        List<Message> snapshot = new ArrayList<>(messages);

        int dropped = BaseAgent.stripHeadOrphanToolResponses(messages, "test");

        assertEquals(0, dropped);
        assertEquals(snapshot, messages, "no drops, list unchanged");
    }

    @Test
    void laterAssistantWithSameIdDoesNotRedeemHeadOrphan() {
        // The classic order-sensitivity trap: a ToolResponseMessage sits at
        // the head, and a LATER AssistantMessage happens to carry the same
        // tool_call_id. The provider's contract is "tool_call must precede
        // tool_response", not "tool_call exists somewhere in the prompt".
        // The leading response is therefore still orphan and must be dropped.
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("[boundary]"),
                toolResponse("call-X"),
                new UserMessage("hi"),
                assistantWithToolCalls("call-X"),     // same id, but AFTER the response
                toolResponse("call-X")
        ));

        int dropped = BaseAgent.stripHeadOrphanToolResponses(messages, "test");

        assertEquals(1, dropped,
                "the leading response is orphan regardless of whether a later assistant "
                        + "happens to carry the same id — provider validity is order-sensitive");
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertInstanceOf(UserMessage.class, messages.get(1),
                "the orphan that sat between the boundary and the user turn is gone");
    }

    @Test
    void partialOrphanInLeadingResponseIsDropped() {
        // A ToolResponseMessage with two responses — one whose id has no
        // preceding assistant, one whose id has none either (since we
        // haven't walked any assistants yet). Provider order-validity
        // doesn't allow partial pairs; dropping wholesale is the safer
        // call. We lose matched-response content but never emit a request
        // the provider would 400.
        ToolResponseMessage mixed = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-orphan", "tool_x", "x"),
                new ToolResponseMessage.ToolResponse("call-known", "tool_y", "y")
        )).build();
        List<Message> messages = new ArrayList<>(List.of(
                mixed,
                assistantWithToolCalls("call-known"),
                toolResponse("call-known")
        ));

        int dropped = BaseAgent.stripHeadOrphanToolResponses(messages, "test");

        assertEquals(1, dropped,
                "no preceding assistant has been walked yet, so even a partially-matched "
                        + "leading response is dropped wholesale");
        assertInstanceOf(AssistantMessage.class, messages.getFirst(),
                "the mixed head is gone; the assistant that would have owned call-known is now first");
    }

    @Test
    void emptyListIsNoOp() {
        List<Message> messages = new ArrayList<>();
        assertEquals(0, BaseAgent.stripHeadOrphanToolResponses(messages, "test"));
        assertTrue(messages.isEmpty());
    }

    @Test
    void purelyUserAssistantHistoryUntouched() {
        // No tool responses at all — repair is a no-op.
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("hi"),
                new AssistantMessage("hello"),
                new UserMessage("how are you?"),
                new AssistantMessage("good")
        ));
        List<Message> snapshot = new ArrayList<>(messages);

        int dropped = BaseAgent.stripHeadOrphanToolResponses(messages, "test");

        assertEquals(0, dropped);
        assertEquals(snapshot, messages);
    }

    @Test
    void stopsAtFirstNonOrphanNonSystem() {
        // Once we hit a non-system, non-orphan message, repair stops — we do
        // NOT keep walking and look for orphans deeper in the history.
        // Deeper orphans imply an upstream bug; this guard is only here to
        // protect the pagination cut.
        List<Message> messages = new ArrayList<>(List.of(
                toolResponse("call-A"),         // orphan at head — will be dropped
                new UserMessage("user"),         // stops the scan
                toolResponse("call-B"),          // orphan but we do NOT touch it
                new AssistantMessage("late")
        ));

        int dropped = BaseAgent.stripHeadOrphanToolResponses(messages, "test");

        assertEquals(1, dropped);
        assertInstanceOf(UserMessage.class, messages.getFirst());
        assertFalse(messages.stream().noneMatch(m -> m instanceof ToolResponseMessage),
                "the deeper orphan stays in place — it surfaces as an upstream bug elsewhere");
    }

    // ------------------------------------------------------------------ helpers

    private static AssistantMessage assistantWithToolCalls(String... callIds) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String id : callIds) {
            calls.add(new AssistantMessage.ToolCall(id, "function", "tool_" + id, "{}"));
        }
        return AssistantMessage.builder().content("").toolCalls(calls).build();
    }

    private static ToolResponseMessage toolResponse(String callId) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, "tool_" + callId, "ok")
        )).build();
    }
}
