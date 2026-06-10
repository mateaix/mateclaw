package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AnthropicChatModelBuilder#isClaude48} must correctly classify the
 * Claude 4.8 model variants we'll see in production — including the
 * higher-priced {@code -fast} sibling.
 *
 * <p>Claude 4.8 inherits 4.7's strict API contract: temperature / top_p /
 * top_k must be unset, and the "xhigh" thinking tier is available. The
 * builder uses {@link AnthropicChatModelBuilder#isClaude47OrLater} to share
 * the gating logic across both generations.</p>
 */
class AnthropicChatModelBuilderClaude48Test {

    @Test
    @DisplayName("isClaude48 detects hyphenated direct-API model names")
    void detect_hyphenated() {
        assertTrue(AnthropicChatModelBuilder.isClaude48("claude-opus-4-8"));
        assertTrue(AnthropicChatModelBuilder.isClaude48("claude-opus-4-8-fast"));
    }

    @Test
    @DisplayName("isClaude48 detects dotted variants (e.g. OpenRouter / mixed dialects)")
    void detect_dotted() {
        assertTrue(AnthropicChatModelBuilder.isClaude48("claude-opus-4.8"));
        assertTrue(AnthropicChatModelBuilder.isClaude48("claude-opus-4.8-fast"));
    }

    @Test
    @DisplayName("isClaude48 detects OpenRouter-style prefixed model ids")
    void detect_openrouterPrefix() {
        assertTrue(AnthropicChatModelBuilder.isClaude48("anthropic/claude-opus-4-8"));
        assertTrue(AnthropicChatModelBuilder.isClaude48("anthropic/claude-opus-4-8-fast"));
        assertTrue(AnthropicChatModelBuilder.isClaude48("anthropic/claude-opus-4.8"));
        assertTrue(AnthropicChatModelBuilder.isClaude48("anthropic/claude-opus-4.8-fast"));
    }

    @Test
    @DisplayName("isClaude48 ignores 4.5 / 4.6 / 4.7 / 3.x and unrelated names")
    void detect_negatives() {
        assertFalse(AnthropicChatModelBuilder.isClaude48("claude-opus-4-7"));
        assertFalse(AnthropicChatModelBuilder.isClaude48("claude-opus-4-6"));
        assertFalse(AnthropicChatModelBuilder.isClaude48("claude-sonnet-4-5"));
        assertFalse(AnthropicChatModelBuilder.isClaude48("claude-3-7-sonnet"));
        // The "claude" prefix guard prevents non-Anthropic models from spuriously
        // matching even if they contain "4-8" / "4.8" substrings.
        assertFalse(AnthropicChatModelBuilder.isClaude48("gpt-4-8"),
                "Non-Claude models must NOT match — claude prefix guard active");
        assertFalse(AnthropicChatModelBuilder.isClaude48("nemotron-4-8-instruct"));
    }

    @Test
    @DisplayName("isClaude48 null-safe")
    void detect_nullSafe() {
        assertFalse(AnthropicChatModelBuilder.isClaude48(null));
        assertFalse(AnthropicChatModelBuilder.isClaude48(""));
    }

    @Test
    @DisplayName("isClaude48 tolerates date-stamped variants")
    void detect_dateStamped() {
        assertTrue(AnthropicChatModelBuilder.isClaude48("claude-opus-4-8-20260601"));
        assertTrue(AnthropicChatModelBuilder.isClaude48("claude-opus-4-8-fast-20260601"));
    }

    @Test
    @DisplayName("isClaude47OrLater unifies the 4.7 + 4.8 sampling-forbidden contract")
    void claude47OrLater_unifiesGenerations() {
        // 4.7 still matches via the legacy detector
        assertTrue(AnthropicChatModelBuilder.isClaude47OrLater("claude-opus-4-7"));
        assertTrue(AnthropicChatModelBuilder.isClaude47OrLater("anthropic/claude-opus-4.7"));
        // 4.8 (regular + -fast) matches via the new detector
        assertTrue(AnthropicChatModelBuilder.isClaude47OrLater("claude-opus-4-8"));
        assertTrue(AnthropicChatModelBuilder.isClaude47OrLater("claude-opus-4-8-fast"));
        assertTrue(AnthropicChatModelBuilder.isClaude47OrLater("anthropic/claude-opus-4.8-fast"));
        // Older Claude generations fall through
        assertFalse(AnthropicChatModelBuilder.isClaude47OrLater("claude-opus-4-6"));
        assertFalse(AnthropicChatModelBuilder.isClaude47OrLater("claude-sonnet-4-5"));
        assertFalse(AnthropicChatModelBuilder.isClaude47OrLater("claude-3-7-sonnet"));
        // Null-safe
        assertFalse(AnthropicChatModelBuilder.isClaude47OrLater(null));
    }
}
