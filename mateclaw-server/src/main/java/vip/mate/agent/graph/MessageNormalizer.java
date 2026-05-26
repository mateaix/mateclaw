package vip.mate.agent.graph;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-egress message-list normalizer.
 *
 * <p>Some OpenAI-compatible providers (notably LM Studio's built-in server,
 * and certain strict-mode vLLM / SGLang deployments) enforce that exactly
 * one {@link SystemMessage} must appear at index 0 of the messages array.
 * Multiple consecutive SystemMessages, or any SystemMessage following a
 * user / assistant / tool message, returns {@code 400 BAD_REQUEST:
 * "System message must be at the beginning."}.
 *
 * <p>Permissive providers (OpenAI, DashScope, Ollama, DeepSeek, Kimi, Doubao,
 * GLM) accept the relaxed shape, so the runtime historically composed
 * prompts with multiple SystemMessages sprinkled through the non-history
 * prefix (main system prompt + skill catalog + progress-ledger snapshot,
 * each as its own SystemMessage). To stay portable across both strict and
 * permissive backends, this normalizer collects every SystemMessage found
 * anywhere in the input list, concatenates their text with a blank-line
 * separator, and emits the result as a single SystemMessage at index 0.
 * The relative order of non-system messages (user / assistant /
 * tool_response) is preserved verbatim so {@code tool_call_id} pairings
 * are unaffected.
 *
 * <p>Blank / whitespace-only SystemMessages are dropped from the merge. If
 * every SystemMessage in the input is blank, the result is the same list
 * with all SystemMessages removed (no synthetic empty SystemMessage is
 * emitted). If the input contains zero SystemMessages, the input list
 * reference is returned unchanged.
 *
 * <p>The transformation is semantically equivalent on permissive providers
 * — the merged SystemMessage produces the same token sequence the model
 * would have seen across N separate SystemMessages — and converts the
 * strict-provider 400 into a success. It is also safe for non-OpenAI
 * protocols: the Spring AI Anthropic and Vertex / Gemini adapters already
 * extract SystemMessages out of the messages list into a top-level
 * {@code system} / {@code systemInstruction} request field, so they receive
 * an identical outbound payload whether handed one merged SystemMessage
 * or several.
 *
 * <p>A kill switch is exposed via the JVM system property
 * {@code mateclaw.llm.message-normalizer.enabled=false}, which makes
 * {@link #normalize} a no-op for emergency rollback without code changes.
 */
public final class MessageNormalizer {

    /** Separator inserted between merged SystemMessage segments. */
    static final String SEPARATOR = "\n\n";

    /**
     * Kill-switch property name. Set to {@code false} (case-insensitive) on
     * the JVM command line to disable normalization without a code change.
     */
    public static final String ENABLED_PROPERTY = "mateclaw.llm.message-normalizer.enabled";

    private static volatile boolean enabled = !"false".equalsIgnoreCase(
            System.getProperty(ENABLED_PROPERTY, "true"));

    private MessageNormalizer() {
    }

    /** Read the current kill-switch state. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Override the kill-switch at runtime (primarily for tests). Production
     * code should not need to call this — set the JVM property at startup
     * instead.
     */
    public static void setEnabledForTesting(boolean value) {
        enabled = value;
    }

    /**
     * Return a copy of {@code prompt} with every SystemMessage merged into a
     * single SystemMessage at index 0. Returns the input prompt reference
     * unchanged when no normalization is necessary (kill switch off, zero
     * SystemMessages, or already a single non-blank SystemMessage at index 0).
     */
    public static Prompt normalize(Prompt prompt) {
        if (prompt == null || !enabled) {
            return prompt;
        }
        List<Message> in = prompt.getInstructions();
        List<Message> out = normalize(in);
        if (out == in) {
            return prompt;
        }
        return new Prompt(out, prompt.getOptions());
    }

    /**
     * List-level normalization, used by {@link #normalize(Prompt)} and by
     * unit tests that want to assert on the raw message shape without
     * constructing a {@link Prompt}. Returns the input list reference
     * unchanged when no normalization is necessary.
     */
    public static List<Message> normalize(List<Message> messages) {
        if (!enabled || messages == null || messages.isEmpty()) {
            return messages;
        }

        int systemCount = 0;
        int firstSystemIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                if (firstSystemIdx < 0) firstSystemIdx = i;
                systemCount++;
            }
        }

        // Fast-path 1: no SystemMessages — nothing to do.
        if (systemCount == 0) {
            return messages;
        }
        // Fast-path 2: exactly one SystemMessage and it sits at index 0 with
        // non-blank text. Already canonical — skip the rebuild.
        if (systemCount == 1 && firstSystemIdx == 0) {
            SystemMessage sm = (SystemMessage) messages.get(0);
            String text = sm.getText();
            if (text != null && !text.isBlank()) {
                return messages;
            }
            // Single blank SystemMessage at [0] — fall through to the rebuild,
            // which will drop it.
        }

        StringBuilder merged = new StringBuilder();
        List<Message> rest = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (m instanceof SystemMessage sm) {
                String text = sm.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (merged.length() > 0) {
                    merged.append(SEPARATOR);
                }
                merged.append(text);
            } else {
                rest.add(m);
            }
        }

        if (merged.length() == 0) {
            // Every SystemMessage in the input was blank — return just the
            // non-system tail. No synthetic empty SystemMessage.
            return rest;
        }

        List<Message> out = new ArrayList<>(rest.size() + 1);
        out.add(new SystemMessage(merged.toString()));
        out.addAll(rest);
        return out;
    }
}
