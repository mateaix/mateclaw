package vip.mate.agent.graph.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.graph.NodeStreamingChatHelper.ErrorType;
import vip.mate.agent.graph.NodeStreamingChatHelper.StreamResult;
import vip.mate.agent.graph.node.ReasoningNode.ContinuationIntent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ReasoningNode#classifyContinuation} and
 * {@link ReasoningNode#lastTurnIsToolResponse} — the logic that decides whether
 * a no-tool-call turn is a real final answer or an empty/thinking-only stop that
 * must be nudged to continue. The headline case: an interleaved-thinking model
 * that returns reasoning but no content after a successful tool call (e.g. a
 * send-file tool succeeds but the download URL is never written) must be
 * re-prompted in-run, not accepted as a final empty answer.
 */
class ReasoningNodeContinuationIntentTest {

    private static StreamResult turn(String text, String thinking, boolean hasToolCalls) {
        return new StreamResult(text, thinking, null, List.of(), hasToolCalls, 0, 0);
    }

    @Test
    @DisplayName("Visible content → FINAL (real answer).")
    void contentIsFinal() {
        assertEquals(ContinuationIntent.FINAL, ReasoningNode.classifyContinuation(turn("here it is", "", false)));
        // Content present even alongside thinking is still a real answer.
        assertEquals(ContinuationIntent.FINAL,
                ReasoningNode.classifyContinuation(turn("here it is", "let me reason", false)));
    }

    @Test
    @DisplayName("Tool call → FINAL (owned by the tool-call branch, not the nudge loop).")
    void toolCallIsFinal() {
        assertEquals(ContinuationIntent.FINAL, ReasoningNode.classifyContinuation(turn("", "", true)));
    }

    @Test
    @DisplayName("Reasoning but no content and no tool call → THINKING_ONLY (nudge to answer).")
    void thinkingOnlyNeedsNudge() {
        assertEquals(ContinuationIntent.THINKING_ONLY,
                ReasoningNode.classifyContinuation(turn("", "I have the file, the task is done", false)));
        assertEquals(ContinuationIntent.THINKING_ONLY,
                ReasoningNode.classifyContinuation(turn("   ", "reasoning here", false)));
    }

    @Test
    @DisplayName("No content, no thinking, no tool call → BLANK (nudge to continue).")
    void blankNeedsNudge() {
        assertEquals(ContinuationIntent.BLANK, ReasoningNode.classifyContinuation(turn("", "", false)));
        assertEquals(ContinuationIntent.BLANK, ReasoningNode.classifyContinuation(turn(null, null, false)));
    }

    @Test
    @DisplayName("null / fatal / prompt-too-long / partial → FINAL (handled by other branches).")
    void otherStatesAreFinal() {
        assertEquals(ContinuationIntent.FINAL, ReasoningNode.classifyContinuation(null));

        StreamResult fatal = new StreamResult("", "", null, List.of(), false, 0, 0,
                false, "upstream boom", ErrorType.SERVER_ERROR);
        assertEquals(ContinuationIntent.FINAL, ReasoningNode.classifyContinuation(fatal));

        StreamResult promptTooLong = new StreamResult("", "", null, List.of(), false, 0, 0,
                false, null, ErrorType.PROMPT_TOO_LONG);
        assertEquals(ContinuationIntent.FINAL, ReasoningNode.classifyContinuation(promptTooLong));

        StreamResult partial = new StreamResult("", "", null, List.of(), false, 0, 0,
                true, null, ErrorType.NONE);
        assertEquals(ContinuationIntent.FINAL, ReasoningNode.classifyContinuation(partial));
    }

    @Test
    @DisplayName("isEmptyCompletion stays true only for BLANK (backward compatible).")
    void isEmptyCompletionIsBlankOnly() {
        assertTrue(ReasoningNode.isEmptyCompletion(turn("", "", false)));
        assertFalse(ReasoningNode.isEmptyCompletion(turn("", "thinking", false)));
        assertFalse(ReasoningNode.isEmptyCompletion(turn("answer", "", false)));
        assertFalse(ReasoningNode.isEmptyCompletion(turn("", "", true)));
    }

    @Test
    @DisplayName("Newest turn being a tool response is detected → picks the answer-anchored nudge.")
    void detectsTrailingToolResponse() {
        List<Message> afterTool = List.of(
                new UserMessage("send me the file"),
                new AssistantMessage("calling send_file"),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("1", "send_file", "{\"url\":\"https://x/y\"}")))
                        .build());
        assertTrue(ReasoningNode.lastTurnIsToolResponse(afterTool));
    }

    @Test
    @DisplayName("A user/assistant turn after the tool result → not a tool-anchored stop.")
    void noTrailingToolResponse() {
        assertFalse(ReasoningNode.lastTurnIsToolResponse(List.of(
                new UserMessage("hello"))));
        assertFalse(ReasoningNode.lastTurnIsToolResponse(List.of(
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("1", "send_file", "ok"))).build(),
                new UserMessage("follow up"))));
        assertFalse(ReasoningNode.lastTurnIsToolResponse(List.of()));
        assertFalse(ReasoningNode.lastTurnIsToolResponse(null));
    }
}
