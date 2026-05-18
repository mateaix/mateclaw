package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the per-request payload patches DeepSeek V4 requires.
 *
 * <p>Two independent invariants are tested separately because they're each
 * easy to silently break:
 * <ul>
 *   <li>{@code extraBody.thinking} + {@code reasoning_effort} on the options.</li>
 *   <li>{@code reasoning_content} on prior assistant tool-call messages
 *       (ensure-when-enabled / strip-when-disabled).</li>
 * </ul>
 */
class DeepSeekV4ThinkingDecoratorTest {

    private DeepSeekV4ThinkingDecorator decorator;

    @BeforeEach
    void setUp() {
        decorator = new DeepSeekV4ThinkingDecorator(new NoopChatModel());
    }

    @AfterEach
    void clearHolder() {
        ThinkingLevelHolder.clear();
    }

    /* =================================================================== */
    /* Options patching                                                     */
    /* =================================================================== */

    @Test
    @DisplayName("thinking=high → extraBody.thinking={type:enabled} + reasoning_effort=high")
    void thinkingHigh_injectsEnabledAndHighEffort() {
        ThinkingLevelHolder.set("high");
        OpenAiChatOptions opts = OpenAiChatOptions.builder().model("deepseek-v4-flash").build();
        Prompt result = decorator.transform(new Prompt(List.of(new UserMessage("hi")), opts));

        OpenAiChatOptions out = (OpenAiChatOptions) result.getOptions();
        assertEquals("high", out.getReasoningEffort());
        assertNotNull(out.getExtraBody());
        Object thinking = out.getExtraBody().get("thinking");
        assertEquals(Map.of("type", "enabled"), thinking,
                "thinking field must be the exact {type: enabled} shape DeepSeek expects");
    }

    @Test
    @DisplayName("thinking=off → extraBody.thinking={type:disabled} + reasoning_effort cleared")
    void thinkingOff_clearsEffortAndDisablesThinking() {
        // Critical: when thinking is disabled, BOTH fields must change. Leaving
        // a stale reasoning_effort while flipping thinking off causes DeepSeek
        // to 400 with "thinking and reasoning_effort cannot coexist when disabled".
        ThinkingLevelHolder.set("off");
        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .model("deepseek-v4-pro")
                .reasoningEffort("medium")  // pre-set, must be cleared
                .build();
        Prompt result = decorator.transform(new Prompt(List.of(new UserMessage("hi")), opts));

        OpenAiChatOptions out = (OpenAiChatOptions) result.getOptions();
        assertNull(out.getReasoningEffort(), "reasoning_effort must be cleared when thinking is off");
        assertEquals(Map.of("type", "disabled"), out.getExtraBody().get(DeepSeekV4ThinkingDecorator.THINKING_FIELD));
    }

    @Test
    @DisplayName("Existing extraBody entries are preserved when patching")
    void extraBody_preservesExistingEntries() {
        // Defends against a copy-and-replace bug where the patch overwrites the
        // whole map. Other extra-body fields (e.g. provider-specific knobs) must
        // survive — losing them silently would break unrelated features.
        ThinkingLevelHolder.set("low");
        Map<String, Object> seed = new HashMap<>();
        seed.put("custom_knob", 42);
        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .model("deepseek-v4-flash").build();
        opts.setExtraBody(seed);

        Prompt result = decorator.transform(new Prompt(List.of(new UserMessage("hi")), opts));
        OpenAiChatOptions out = (OpenAiChatOptions) result.getOptions();
        assertEquals(42, out.getExtraBody().get("custom_knob"));
        assertNotNull(out.getExtraBody().get(DeepSeekV4ThinkingDecorator.THINKING_FIELD));
    }

    @Test
    @DisplayName("mapEffort: low/medium/high passthrough; max collapses to high; unknown → medium")
    void mapEffort_levels() {
        // "max" folds into "high" because DeepSeek doesn't expose a max tier.
        // Pin both ends of the rule.
        assertEquals("low", DeepSeekV4ThinkingDecorator.mapEffort("low"));
        assertEquals("medium", DeepSeekV4ThinkingDecorator.mapEffort("medium"));
        assertEquals("high", DeepSeekV4ThinkingDecorator.mapEffort("high"));
        assertEquals("high", DeepSeekV4ThinkingDecorator.mapEffort("max"));
        assertEquals("medium", DeepSeekV4ThinkingDecorator.mapEffort("xhigh"));
        assertEquals("medium", DeepSeekV4ThinkingDecorator.mapEffort(null));
    }

    /* =================================================================== */
    /* Message patching                                                     */
    /* =================================================================== */

    @Test
    @DisplayName("enabled + tool-call history → reasoning_content key ensured (empty string)")
    void messages_enabled_ensuresReasoningContent() {
        // V4 replay contract: every prior assistant tool-call message must have
        // a reasoning_content (empty allowed). Missing it returns an obscure 400
        // about "reasoning_content required for thinking-enabled tool replay".
        AssistantMessage am = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call_1", "function", "search", "{}")))
                .build();
        List<Message> patched = DeepSeekV4ThinkingDecorator.patchMessages(List.of(am), true);

        AssistantMessage out = (AssistantMessage) patched.get(0);
        assertTrue(out.getMetadata().containsKey(DeepSeekV4ThinkingDecorator.REASONING_CONTENT_KEY));
        assertEquals("", out.getMetadata().get(DeepSeekV4ThinkingDecorator.REASONING_CONTENT_KEY));
        // Tool calls must pass through unchanged — losing the call ID would
        // break the next turn's tool_result correlation.
        assertEquals("call_1", out.getToolCalls().get(0).id());
    }

    @Test
    @DisplayName("enabled + already-has reasoning_content → no rewrite (fast path)")
    void messages_enabled_noRewriteWhenAlreadyPresent() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(DeepSeekV4ThinkingDecorator.REASONING_CONTENT_KEY, "prev thinking");
        AssistantMessage am = AssistantMessage.builder()
                .content("answer")
                .properties(meta)
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call_2", "function", "search", "{}")))
                .build();
        List<Message> patched = DeepSeekV4ThinkingDecorator.patchMessages(List.of(am), true);

        // Identity equality — fast path returns the same instance to avoid pointless allocation.
        assertTrue(patched.get(0) == am, "no-op rewrite should return the same instance");
    }

    @Test
    @DisplayName("disabled → reasoning_content stripped from prior messages")
    void messages_disabled_stripsReasoningContent() {
        // DeepSeek echoes prior reasoning_content back into the response when
        // thinking is disabled, polluting the user-visible answer. Stripping is
        // not optional.
        Map<String, Object> meta = new HashMap<>();
        meta.put(DeepSeekV4ThinkingDecorator.REASONING_CONTENT_KEY, "old thinking");
        meta.put("other_meta", "preserved");
        AssistantMessage am = AssistantMessage.builder()
                .content("answer")
                .properties(meta)
                .build();

        List<Message> patched = DeepSeekV4ThinkingDecorator.patchMessages(List.of(am), false);
        AssistantMessage out = (AssistantMessage) patched.get(0);
        assertFalse(out.getMetadata().containsKey(DeepSeekV4ThinkingDecorator.REASONING_CONTENT_KEY),
                "reasoning_content must be removed");
        assertEquals("preserved", out.getMetadata().get("other_meta"),
                "Other metadata keys must survive the strip");
    }

    @Test
    @DisplayName("disabled + no reasoning_content → no-op pass-through")
    void messages_disabled_noOpWhenAbsent() {
        AssistantMessage am = new AssistantMessage("plain answer");
        List<Message> patched = DeepSeekV4ThinkingDecorator.patchMessages(List.of(am), false);
        assertTrue(patched.get(0) == am, "no-op rewrite should return the same instance");
    }

    @Test
    @DisplayName("Non-assistant messages pass through untouched")
    void messages_userPassesThrough() {
        // patchMessages must only touch AssistantMessage. UserMessage / ToolMessage
        // / SystemMessage carry meaning the decorator has no business modifying.
        UserMessage user = new UserMessage("question");
        List<Message> patched = DeepSeekV4ThinkingDecorator.patchMessages(List.of(user), true);
        assertTrue(patched.get(0) == user);
    }

    /* =================================================================== */
    /* End-to-end delegate                                                  */
    /* =================================================================== */

    @Test
    @DisplayName("call() delegates the patched prompt to the underlying ChatModel")
    void call_delegatesPatched() {
        // Sanity: the prompt that reaches the underlying model carries the
        // patched options/messages, not the originals.
        AtomicReference<Prompt> captured = new AtomicReference<>();
        DeepSeekV4ThinkingDecorator d = new DeepSeekV4ThinkingDecorator(new NoopChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                captured.set(prompt);
                return null;
            }
        });
        ThinkingLevelHolder.set("medium");
        OpenAiChatOptions opts = OpenAiChatOptions.builder().model("deepseek-v4-flash").build();
        d.call(new Prompt(List.of(new UserMessage("hi")), opts));

        assertNotNull(captured.get());
        OpenAiChatOptions sentOpts = (OpenAiChatOptions) captured.get().getOptions();
        assertEquals("medium", sentOpts.getReasoningEffort());
        assertEquals(Map.of("type", "enabled"),
                sentOpts.getExtraBody().get(DeepSeekV4ThinkingDecorator.THINKING_FIELD));
    }

    /* ---------- Test double ---------- */

    private static class NoopChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
    }
}
