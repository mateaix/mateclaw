package vip.mate.agent.graph.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.NodeStreamingChatHelper.ErrorType;
import vip.mate.agent.graph.NodeStreamingChatHelper.StreamResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ReasoningNode#isEmptyCompletion} — the predicate that decides
 * whether a model turn is a blank no-op worth re-prompting (vs a real answer, a
 * tool call, or a failure handled by another branch). A blank turn must NOT be
 * accepted as a final answer; that is what made a long multi-step task quit
 * mid-way.
 */
class ReasoningNodeEmptyCompletionTest {

    private static StreamResult turn(String text, String thinking, boolean hasToolCalls) {
        return new StreamResult(text, thinking, null, List.of(), hasToolCalls, 0, 0);
    }

    @Test
    @DisplayName("No tool call + blank text + blank thinking → empty (re-prompt).")
    void blankTurnIsEmpty() {
        assertTrue(ReasoningNode.isEmptyCompletion(turn("", "", false)));
        assertTrue(ReasoningNode.isEmptyCompletion(turn("   ", "  ", false)));
        assertTrue(ReasoningNode.isEmptyCompletion(turn(null, null, false)));
    }

    @Test
    @DisplayName("Any content or thinking → not empty.")
    void contentOrThinkingNotEmpty() {
        assertFalse(ReasoningNode.isEmptyCompletion(turn("here is the answer", "", false)));
        assertFalse(ReasoningNode.isEmptyCompletion(turn("", "let me reason", false)));
    }

    @Test
    @DisplayName("A tool call is real progress → not empty.")
    void toolCallNotEmpty() {
        assertFalse(ReasoningNode.isEmptyCompletion(turn("", "", true)));
    }

    @Test
    @DisplayName("null result → not empty (nothing to re-prompt).")
    void nullNotEmpty() {
        assertFalse(ReasoningNode.isEmptyCompletion(null));
    }

    @Test
    @DisplayName("Fatal / prompt-too-long / partial belong to other branches, not 'empty'.")
    void otherFailuresNotEmpty() {
        StreamResult fatal = new StreamResult("", "", null, List.of(), false, 0, 0,
                false, "upstream boom", ErrorType.SERVER_ERROR);
        assertFalse(ReasoningNode.isEmptyCompletion(fatal));

        StreamResult promptTooLong = new StreamResult("", "", null, List.of(), false, 0, 0,
                false, null, ErrorType.PROMPT_TOO_LONG);
        assertFalse(ReasoningNode.isEmptyCompletion(promptTooLong));

        StreamResult partial = new StreamResult("", "", null, List.of(), false, 0, 0,
                true, null, ErrorType.NONE);
        assertFalse(ReasoningNode.isEmptyCompletion(partial));
    }
}
