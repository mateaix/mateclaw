package vip.mate.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the routing decision for {@code STREAMED_CONTENT} deltas emitted by
 * {@link StateGraphReActAgent} during the structured-stream loop.
 *
 * <p>Background — issue #120 follow-up: the original fix routed every per-iteration
 * {@code STREAMED_CONTENT} via {@link AgentService.StreamDelta#segmentOnly}, which
 * keeps the persisted {@code mate_message.content} clean of mid-loop "我来…" preamble.
 * That broke the evidence-insufficient terminal turn though
 * ({@code ReasoningNode.java:617}): there {@code FINAL_ANSWER} is just a short
 * "[证据不足]" warning while {@code STREAMED_CONTENT} carries the actual answer body
 * the user/UI need to see. Single-segment renderers (copy / TTS / history reload)
 * read {@code content}, not segments, so {@code segmentOnly} for that case would
 * shrink the visible message to the warning alone.
 *
 * <p>The helper under test embodies the corrected contract.
 */
class StateGraphReActAgentStreamedContentDeltaTest {

    @Test
    @DisplayName("intermediate iteration (no FINAL_ANSWER yet) → segmentOnly — narration stays out of content")
    void intermediateIteration_routedToSegmentsOnly() {
        AgentService.StreamDelta d = StateGraphReActAgent.streamedContentDelta(
                /* isFinalAnswerTurn */ false,
                "I'll search for X.");

        assertTrue(d.persistenceOnly(),
                "segmentOnly implies persistenceOnly — no re-broadcast (NodeStreamingChatHelper already pushed it)");
        assertTrue(d.segmentOnly(),
                "intermediate narration MUST set segmentOnly so content.append is skipped");
        // Sanity: the content payload survives the wrap.
        org.junit.jupiter.api.Assertions.assertEquals("I'll search for X.", d.content());
    }

    @Test
    @DisplayName("evidence-insufficient terminal turn (FINAL_ANSWER set) → persistOnly — answer body persists to content")
    void evidenceInsufficientFinalTurn_routedToPersistOnly() {
        // Regression: STREAMED_CONTENT here is the rejected answer body; FINAL_ANSWER
        // is only the "[证据不足]" warning. Persisting the streamed body keeps
        // mate_message.content readable through single-segment renderers.
        AgentService.StreamDelta d = StateGraphReActAgent.streamedContentDelta(
                /* isFinalAnswerTurn */ true,
                "The answer is 42. References: [1] [2] [3].");

        assertTrue(d.persistenceOnly(),
                "persistOnly suppresses re-broadcast — content was already streamed live");
        assertFalse(d.segmentOnly(),
                "persistOnly variant MUST NOT set segmentOnly — content.append needs to run");
        org.junit.jupiter.api.Assertions.assertEquals(
                "The answer is 42. References: [1] [2] [3].", d.content());
    }

    @Test
    @DisplayName("both flavors leave thinking null — STREAMED_CONTENT routing only carries text content")
    void thinkingFieldNeverSet() {
        org.junit.jupiter.api.Assertions.assertNull(
                StateGraphReActAgent.streamedContentDelta(false, "x").thinking());
        org.junit.jupiter.api.Assertions.assertNull(
                StateGraphReActAgent.streamedContentDelta(true, "x").thinking());
    }
}
