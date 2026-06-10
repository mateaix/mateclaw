package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AnthropicChatModelBuilder#isClaudeFable} must classify the Claude
 * Fable reasoning line as a modern model so it inherits the strict 4.7+ API
 * contract: temperature / top_p / top_k must be unset (any non-default value
 * returns HTTP 400) and the "xhigh" adaptive thinking tier is available.
 *
 * <p>{@link AnthropicChatModelBuilder#isClaude47OrLater} unifies the gating
 * logic across the 4.7 / 4.8 generations and the Fable family.</p>
 */
class AnthropicChatModelBuilderFableTest {

    @Test
    @DisplayName("isClaudeFable detects the direct-API and OpenRouter ids")
    void detect_directAndOpenRouter() {
        assertTrue(AnthropicChatModelBuilder.isClaudeFable("claude-fable-5"));
        assertTrue(AnthropicChatModelBuilder.isClaudeFable("anthropic/claude-fable-5"));
        // Case-insensitive
        assertTrue(AnthropicChatModelBuilder.isClaudeFable("Claude-Fable-5"));
    }

    @Test
    @DisplayName("isClaudeFable tolerates date-stamped and future revisions")
    void detect_futureRevisions() {
        assertTrue(AnthropicChatModelBuilder.isClaudeFable("claude-fable-5-20260609"));
        assertTrue(AnthropicChatModelBuilder.isClaudeFable("claude-fable-6"));
    }

    @Test
    @DisplayName("isClaudeFable ignores other Claude families and unrelated names")
    void detect_negatives() {
        assertFalse(AnthropicChatModelBuilder.isClaudeFable("claude-opus-4-8"));
        assertFalse(AnthropicChatModelBuilder.isClaudeFable("claude-sonnet-4-6"));
        // The "claude-fable" token guard prevents a stray "fable" elsewhere from matching.
        assertFalse(AnthropicChatModelBuilder.isClaudeFable("some-fable-model"));
    }

    @Test
    @DisplayName("isClaudeFable null-safe")
    void detect_nullSafe() {
        assertFalse(AnthropicChatModelBuilder.isClaudeFable(null));
        assertFalse(AnthropicChatModelBuilder.isClaudeFable(""));
    }

    @Test
    @DisplayName("isClaude47OrLater routes Fable onto the sampling-forbidden contract")
    void claude47OrLater_includesFable() {
        assertTrue(AnthropicChatModelBuilder.isClaude47OrLater("claude-fable-5"));
        assertTrue(AnthropicChatModelBuilder.isClaude47OrLater("anthropic/claude-fable-5"));
        // Sanity: existing generations still classify modern, legacy still falls through.
        assertTrue(AnthropicChatModelBuilder.isClaude47OrLater("claude-opus-4-8"));
        assertFalse(AnthropicChatModelBuilder.isClaude47OrLater("claude-opus-4-6"));
    }
}
