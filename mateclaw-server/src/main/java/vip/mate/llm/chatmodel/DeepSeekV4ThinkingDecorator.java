package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek V4 thinking-mode payload patcher applied to every
 * {@code deepseek-v4-flash} / {@code deepseek-v4-pro} request.
 *
 * <p>DeepSeek V4 extends OpenAI's chat-completions wire format with two
 * non-standard request fields that the base Spring AI {@link OpenAiChatOptions}
 * has no first-class support for:
 *
 * <ul>
 *   <li>{@code thinking: {"type": "enabled" | "disabled"}} — toggles V4's
 *       step-by-step reasoning channel.</li>
 *   <li>{@code reasoning_effort: "low" | "medium" | "high"} — only meaningful
 *       when {@code thinking.type == "enabled"}.</li>
 * </ul>
 *
 * <p>It also has a strict replay contract: when thinking is enabled and the
 * conversation contains prior assistant tool-calls, every such tool-call
 * message must carry a {@code reasoning_content} string (empty allowed) or
 * the API rejects with an obscure 400. When thinking is disabled, any prior
 * {@code reasoning_content} must be stripped or DeepSeek echoes the old
 * thinking back into the response.
 *
 * <h2>Pipeline (per request)</h2>
 * <ol>
 *   <li>Read {@link ThinkingLevelHolder} for the current request's thinking
 *       level (set by the agent service before the call).</li>
 *   <li>Clone {@link OpenAiChatOptions} and patch its {@code extraBody} +
 *       {@code reasoningEffort} fields. Spring AI sends {@code extraBody}
 *       verbatim in the JSON body, so the {@code thinking} key lands where
 *       DeepSeek expects it.</li>
 *   <li>Walk message history: when disabled, strip {@code reasoning_content}
 *       from {@link AssistantMessage} metadata; when enabled, ensure each
 *       tool-call message has a (possibly empty) {@code reasoning_content}
 *       entry to satisfy V4's replay contract.</li>
 *   <li>Delegate to the wrapped {@link ChatModel}.</li>
 * </ol>
 */
@Slf4j
public class DeepSeekV4ThinkingDecorator implements ChatModel {

    /** Metadata key under which we stash {@code reasoning_content} on AssistantMessage. */
    static final String REASONING_CONTENT_KEY = "reasoning_content";

    /** Request-body field DeepSeek V4 reads to toggle thinking mode. */
    static final String THINKING_FIELD = "thinking";

    private final ChatModel delegate;

    public DeepSeekV4ThinkingDecorator(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(transform(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(transform(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /* ---------------------------------------------------------------- */
    /* Outbound transform                                                */
    /* ---------------------------------------------------------------- */

    /** Build a new Prompt with thinking + reasoning_content patched. Package-private for tests. */
    Prompt transform(Prompt original) {
        if (original == null) {
            return null;
        }
        boolean thinkingEnabled = isThinkingEnabled();
        ChatOptions patchedOptions = patchOptions(original.getOptions(), thinkingEnabled);
        List<Message> patchedMessages = patchMessages(original.getInstructions(), thinkingEnabled);
        return new Prompt(patchedMessages, patchedOptions);
    }

    private static boolean isThinkingEnabled() {
        String level = ThinkingLevelHolder.get();
        // null/empty → fall back to enabled (V4's default behavior is reasoning-on);
        // explicit "off" → disabled.
        return level == null || level.isBlank() || !"off".equalsIgnoreCase(level);
    }

    /**
     * Map MateClaw's thinking levels (off/low/medium/high/max) to DeepSeek's
     * accepted reasoning_effort values: max collapses into high since DeepSeek
     * does not expose a "max" tier on V4.
     */
    static String mapEffort(String level) {
        if (level == null || level.isBlank()) return "medium";
        return switch (level.toLowerCase()) {
            case "low" -> "low";
            case "medium" -> "medium";
            case "high", "max" -> "high";
            default -> "medium";
        };
    }

    /**
     * Clone {@link OpenAiChatOptions} and inject extraBody.thinking + reasoning_effort.
     * Returns the input unchanged for non-OpenAI options (defensive — should
     * never happen for V4, but skips ahead-of-binding work in tests that pass
     * vanilla {@link ChatOptions}).
     */
    private static ChatOptions patchOptions(ChatOptions original, boolean enabled) {
        if (!(original instanceof OpenAiChatOptions oai)) {
            return original;
        }
        OpenAiChatOptions copy = OpenAiChatOptions.fromOptions(oai);
        Map<String, Object> extra = copy.getExtraBody();
        Map<String, Object> patched = (extra == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);

        if (enabled) {
            patched.put(THINKING_FIELD, Map.of("type", "enabled"));
            // reasoning_effort is a first-class OpenAiChatOptions field → set via setter.
            String level = ThinkingLevelHolder.get();
            copy.setReasoningEffort(mapEffort(level));
        } else {
            patched.put(THINKING_FIELD, Map.of("type", "disabled"));
            // Drop reasoning_effort — DeepSeek 400s if both are present with thinking disabled.
            copy.setReasoningEffort(null);
        }
        copy.setExtraBody(patched);
        return copy;
    }

    /**
     * Walk message history and patch reasoning_content per V4's contract:
     * <ul>
     *   <li><b>enabled</b>: every assistant tool-call message must carry a
     *       (possibly empty) {@code reasoning_content} entry in its metadata.</li>
     *   <li><b>disabled</b>: strip any {@code reasoning_content} from prior
     *       messages so DeepSeek doesn't echo stale reasoning back.</li>
     * </ul>
     */
    static List<Message> patchMessages(List<Message> source, boolean enabled) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        List<Message> out = new ArrayList<>(source.size());
        for (Message msg : source) {
            if (msg.getMessageType() == MessageType.ASSISTANT && msg instanceof AssistantMessage am) {
                out.add(rewriteAssistant(am, enabled));
            } else {
                out.add(msg);
            }
        }
        return out;
    }

    private static AssistantMessage rewriteAssistant(AssistantMessage am, boolean enabled) {
        Map<String, Object> meta = am.getMetadata();
        boolean hasTools = am.hasToolCalls();
        boolean hasReasoning = meta != null && meta.containsKey(REASONING_CONTENT_KEY);

        // Fast path: no rewrite needed.
        if (enabled && (!hasTools || hasReasoning)) {
            return am;
        }
        if (!enabled && !hasReasoning) {
            return am;
        }

        Map<String, Object> newMeta = (meta == null) ? new HashMap<>() : new HashMap<>(meta);
        if (enabled) {
            // Tool-call messages need reasoning_content present (empty OK) for replay.
            newMeta.putIfAbsent(REASONING_CONTENT_KEY, "");
        } else {
            // Drop reasoning_content entirely — DeepSeek mirrors back stale thinking otherwise.
            newMeta.remove(REASONING_CONTENT_KEY);
        }
        return AssistantMessage.builder()
                .content(am.getText())
                .properties(newMeta)
                .toolCalls(am.getToolCalls())
                .media(am.getMedia())
                .build();
    }
}
