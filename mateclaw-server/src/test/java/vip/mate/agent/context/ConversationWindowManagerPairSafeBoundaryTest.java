package vip.mate.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.config.ConversationWindowProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pair-safe boundary enforcement for {@link ConversationWindowManager}.
 *
 * <p>The compactor must never produce a prompt where an
 * {@link AssistantMessage} carrying {@code tool_calls} is separated from
 * the {@link ToolResponseMessage}s that close those calls. Provider APIs
 * 400 on the broken sequence, which is strictly worse than letting the
 * context cross the budget by one extra turn.
 *
 * <p>Conventions used by these tests:
 * <ul>
 *   <li>{@code asst(id1, id2, ...)} — assistant message carrying tool_calls</li>
 *   <li>{@code resp(id, ...)} — tool response message closing the listed ids</li>
 *   <li>"split" means the candidate cut falls between an assistant and one
 *       of its responses; the algorithm must move the cut backward until no
 *       split remains, or signal skip-compaction by returning {@code headEnd}.</li>
 * </ul>
 */
class ConversationWindowManagerPairSafeBoundaryTest {

    @Test
    void cleanCutBetweenTurnsIsUnchanged() {
        ConversationWindowManager mgr = newManager(0);

        // [0] user, [1] assistant(call-1), [2] response(call-1),
        // [3] user, [4] assistant(call-2), [5] response(call-2)
        List<Message> messages = List.of(
                new UserMessage("q1"),
                asst("call-1"),
                resp("call-1"),
                new UserMessage("q2"),
                asst("call-2"),
                resp("call-2")
        );

        // tailStart=3 — cuts cleanly between two fully-closed turns.
        int cut = mgr.enforcePairSafeBoundary(messages, 0, 3);

        assertEquals(3, cut, "cut between completed turns must not move");
    }

    @Test
    void cutLandingOnResponseMovesBackToOwningAssistant() {
        ConversationWindowManager mgr = newManager(0);

        // [0] user, [1] assistant(call-1), [2] response(call-1), [3] user, [4] assistant(call-2), [5] response(call-2)
        List<Message> messages = List.of(
                new UserMessage("q1"),
                asst("call-1"),
                resp("call-1"),
                new UserMessage("q2"),
                asst("call-2"),
                resp("call-2")
        );

        // tailStart=2 — splits call-1 (assistant in prefix, response in tail).
        int cut = mgr.enforcePairSafeBoundary(messages, 0, 2);

        assertEquals(1, cut,
                "cut must move to the assistant that issued call-1 so the pair lands in the tail together");
    }

    @Test
    void cutSplittingAssistantWithMultipleToolCallsMovesEntireGroup() {
        ConversationWindowManager mgr = newManager(0);

        // One assistant with TWO tool_calls; responses arrive in two separate
        // ToolResponseMessages. Cutting between the responses must drag the
        // assistant + both response messages into the tail together.
        List<Message> messages = List.of(
                new UserMessage("q"),
                asst("call-1", "call-2"),
                resp("call-1"),
                resp("call-2"),
                new UserMessage("next")
        );

        int cut = mgr.enforcePairSafeBoundary(messages, 0, 3); // between the two responses

        assertEquals(1, cut,
                "splitting a multi-call assistant must move cut to the assistant index");
    }

    @Test
    void cutSplittingMultiResponseMessagesForOneAssistantMovesBack() {
        ConversationWindowManager mgr = newManager(0);

        // assistant(call-1, call-2), single ToolResponseMessage closing both.
        List<Message> messages = List.of(
                new UserMessage("q"),
                asst("call-1", "call-2"),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "tool_a", "x"),
                        new ToolResponseMessage.ToolResponse("call-2", "tool_b", "y")
                )).build(),
                new UserMessage("next")
        );

        // cut=2 → response message is in tail, assistant in prefix → split.
        int cut = mgr.enforcePairSafeBoundary(messages, 0, 2);

        assertEquals(1, cut);
    }

    @Test
    void chainedPairSplitsConvergeAfterMultiplePasses() {
        ConversationWindowManager mgr = newManager(0);

        // Three consecutive call/response cycles. Cutting in the middle
        // exposes a split, and moving the cut back exposes another.
        List<Message> messages = List.of(
                asst("call-1"),                   // 0
                resp("call-1"),                   // 1
                asst("call-2"),                   // 2
                resp("call-2"),                   // 3
                asst("call-3"),                   // 4
                resp("call-3")                    // 5
        );

        // cut=3 splits call-2 (assistant at 2, response at 3) → first pass moves to 2.
        // After moving to 2, no more splits (call-1 is fully in prefix, call-3 fully in tail).
        int cut = mgr.enforcePairSafeBoundary(messages, 0, 3);
        assertEquals(2, cut);

        // cut=5 splits call-3 → moves to 4. cut=4, still good (no split). Convergence.
        cut = mgr.enforcePairSafeBoundary(messages, 0, 5);
        assertEquals(4, cut);
    }

    @Test
    void collapseToHeadEndSignalsSkip() {
        ConversationWindowManager mgr = newManager(0);

        // Single assistant + response pair. Cutting anywhere splits it,
        // so the safe boundary lands at headEnd → caller should skip compaction.
        List<Message> messages = List.of(
                asst("call-1"),
                resp("call-1")
        );

        int cut = mgr.enforcePairSafeBoundary(messages, 0, 1);

        assertEquals(0, cut, "single unsafe pair must collapse to headEnd to signal skip");
    }

    @Test
    void minPrefixThresholdSkipsTinyCompactions() {
        // minPrefix=3 — after pair safety, if prefix < 3 messages, skip.
        ConversationWindowManager mgr = newManager(3);

        List<Message> messages = List.of(
                new UserMessage("q1"),
                asst("call-1"),
                resp("call-1"),
                new UserMessage("q2")
        );

        // cut=3 would compress messages[0..3] = 3 items, meeting min.
        // cut=1 would compress just messages[0..1] = 1 item, below min → skip.
        int cut1 = mgr.enforcePairSafeBoundary(messages, 0, 3);
        assertEquals(3, cut1, "3-message prefix meets the minimum");

        int cut2 = mgr.enforcePairSafeBoundary(messages, 0, 1);
        assertEquals(0, cut2, "1-message prefix is below the configured minimum → skip compaction");
    }

    @Test
    void orphanResponseInTailDoesNotMoveBoundary() {
        ConversationWindowManager mgr = newManager(0);

        // call-orphan has no preceding assistant — pure data anomaly. Algorithm
        // should not try to "fix" it by moving the cut; it just leaves the
        // boundary where it was and logs a warn.
        List<Message> messages = List.of(
                new UserMessage("q1"),
                asst("call-1"),
                resp("call-1"),
                new UserMessage("q2"),
                resp("call-orphan")
        );

        int cut = mgr.enforcePairSafeBoundary(messages, 0, 3);

        assertEquals(3, cut, "orphan response must not pull the boundary");
    }

    @Test
    void tailStartAtOrBeyondMessagesSizeIsUnchanged() {
        ConversationWindowManager mgr = newManager(0);

        List<Message> messages = List.of(
                new UserMessage("a"),
                new UserMessage("b")
        );

        assertEquals(2, mgr.enforcePairSafeBoundary(messages, 0, 2),
                "boundary at end of list passes through");
        assertTrue(mgr.enforcePairSafeBoundary(messages, 0, 5) >= 0,
                "out-of-range boundary stays sane");
    }

    // ------------------------------------------------------------------ helpers

    private static ConversationWindowManager newManager(int minPrefix) {
        ConversationWindowProperties props = new ConversationWindowProperties();
        props.setPairSafeMinPrefixToCompact(minPrefix);
        return new ConversationWindowManager(props, null, null);
    }

    private static AssistantMessage asst(String... callIds) {
        java.util.List<AssistantMessage.ToolCall> calls = new java.util.ArrayList<>();
        for (String id : callIds) {
            calls.add(new AssistantMessage.ToolCall(id, "function", "tool_" + id, "{}"));
        }
        return AssistantMessage.builder().content("").toolCalls(calls).build();
    }

    private static ToolResponseMessage resp(String callId) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(callId, "tool_" + callId, "ok")
        )).build();
    }
}
