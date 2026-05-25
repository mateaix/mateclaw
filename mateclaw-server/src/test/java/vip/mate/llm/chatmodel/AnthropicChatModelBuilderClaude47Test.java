package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AnthropicChatModelBuilder#isClaude47} must correctly classify the
 * Claude 4.7 model variants we'll see in production.
 *
 * <p>Claude 4.7 forbids temperature / top_p / top_k entirely — the builder
 * relies on this detector to skip those fields rather than letting Anthropic 400.
 */
class AnthropicChatModelBuilderClaude47Test {

    @Test
    @DisplayName("isClaude47 detects hyphenated direct-API model names")
    void detect_hyphenated() {
        assertTrue(AnthropicChatModelBuilder.isClaude47("claude-opus-4-7"));
        assertTrue(AnthropicChatModelBuilder.isClaude47("claude-sonnet-4-7"));
        assertTrue(AnthropicChatModelBuilder.isClaude47("claude-haiku-4-7"));
    }

    @Test
    @DisplayName("isClaude47 detects dotted variants (e.g. OpenRouter / mixed dialects)")
    void detect_dotted() {
        assertTrue(AnthropicChatModelBuilder.isClaude47("claude-opus-4.7"));
        assertTrue(AnthropicChatModelBuilder.isClaude47("claude.sonnet.4.7"));
    }

    @Test
    @DisplayName("isClaude47 detects OpenRouter-style prefixed model ids")
    void detect_openrouterPrefix() {
        assertTrue(AnthropicChatModelBuilder.isClaude47("anthropic/claude-opus-4-7"));
        assertTrue(AnthropicChatModelBuilder.isClaude47("anthropic/claude-sonnet-4-7"));
        assertTrue(AnthropicChatModelBuilder.isClaude47("anthropic/claude-opus-4.7"));
    }

    @Test
    @DisplayName("isClaude47 ignores 4.5 / 4.6 / 4.0 / 3.x and unrelated names")
    void detect_negatives() {
        assertFalse(AnthropicChatModelBuilder.isClaude47("claude-opus-4-6"));
        assertFalse(AnthropicChatModelBuilder.isClaude47("claude-sonnet-4-5"));
        assertFalse(AnthropicChatModelBuilder.isClaude47("claude-3-7-sonnet"),
                "3.7 must not match 4.7");
        assertFalse(AnthropicChatModelBuilder.isClaude47("claude-3-5-sonnet"));
        // The "claude" prefix guard prevents non-Anthropic models from spuriously
        // matching even if they contain "4-7" / "4.7" substrings.
        assertFalse(AnthropicChatModelBuilder.isClaude47("gpt-4-7"),
                "Non-Claude models must NOT match — claude prefix guard active");
        assertFalse(AnthropicChatModelBuilder.isClaude47("nemotron-4-7-instruct"));
    }

    @Test
    @DisplayName("isClaude47 null-safe")
    void detect_nullSafe() {
        assertFalse(AnthropicChatModelBuilder.isClaude47(null));
        assertFalse(AnthropicChatModelBuilder.isClaude47(""));
    }

    @Test
    @DisplayName("Note: claude-3-7-sonnet correctly distinguished from claude-4-7-*")
    void detect_3_7_vs_4_7() {
        // Both contain "-7" but only the second contains "4-7" as a substring.
        assertFalse(AnthropicChatModelBuilder.isClaude47("claude-3-7-sonnet-20250219"));
        assertTrue(AnthropicChatModelBuilder.isClaude47("claude-opus-4-7-20260415"),
                "Date-stamped 4-7 variants must still match");
    }
}
