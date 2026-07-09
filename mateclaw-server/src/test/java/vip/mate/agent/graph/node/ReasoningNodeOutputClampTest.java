package vip.mate.agent.graph.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.PrefixBudgetPlan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Window-aware output-cap clamp: strict local servers (vLLM) statically
 * reject {@code max_tokens >= max_model_len}, so the cap sent to the
 * provider must shrink when the known context window is smaller than the
 * configured / default output cap.
 */
class ReasoningNodeOutputClampTest {

    private static ReasoningNode nodeWithWindow(Integer windowTokens) {
        @SuppressWarnings("deprecation")
        ReasoningNode node = new ReasoningNode(null,
                AgentToolSet.fromCallbacks(List.of(), List.of()), null);
        if (windowTokens != null) {
            node.setPrefixBudgetPlan(new PrefixBudgetPlan(
                    true, windowTokens, PrefixBudgetPlan.Profile.COMPACT,
                    0, 0, 0, 0, 0, 0, windowTokens / 4));
        }
        return node;
    }

    @Test
    @DisplayName("default 16384 cap on an 8k window clamps to half the window")
    void defaultCapClampsOnSmallWindow() {
        // Deprecated ctor leaves maxOutputTokens at the 16384 default.
        assertEquals(4096, nodeWithWindow(8192).effectiveMaxOutputTokens());
    }

    @Test
    @DisplayName("large window leaves the cap untouched")
    void largeWindowKeepsCap() {
        assertEquals(16384, nodeWithWindow(128000).effectiveMaxOutputTokens());
    }

    @Test
    @DisplayName("no budget plan (tests / legacy graphs) keeps previous behavior")
    void noPlanKeepsCap() {
        assertEquals(16384, nodeWithWindow(null).effectiveMaxOutputTokens());
    }

    @Test
    @DisplayName("tiny window clamps no lower than the 512-token floor")
    void tinyWindowRespectsFloor() {
        assertEquals(512, nodeWithWindow(600).effectiveMaxOutputTokens());
    }
}
