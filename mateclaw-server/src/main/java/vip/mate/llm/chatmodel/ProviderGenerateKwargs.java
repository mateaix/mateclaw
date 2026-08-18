package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads typed values out of a provider's {@code generateKwargs} map.
 *
 * <p>A lookup tries the camelCase key first, then a snake_case fallback, and also
 * descends into a nested {@code chatOptions} / {@code chat_options} map — so an
 * admin may specify an option under any of those shapes. Shared by the OpenAI-compatible chat model
 * builder, the reasoning-effort resolver, and the provider test-prompt path
 * ({@code ModelDiscoveryService}) so every outbound request built from
 * {@code generateKwargs} treats unrecognized keys the same way.
 */
@Slf4j
public final class ProviderGenerateKwargs {

    private ProviderGenerateKwargs() {}

    /**
     * Top-level {@code generateKwargs} keys with dedicated typed handling elsewhere
     * (both camelCase and snake_case spellings), plus the {@code chatOptions} nesting
     * wrappers themselves (their contents are already consumed via {@link #findOptionValue}).
     * Centralized here so passthrough logic and known-key extraction across callers
     * can't drift out of sync. Anything else at the top level of generateKwargs is
     * forwarded verbatim — see {@link #collectPassthroughExtraBody}.
     *
     * <p>{@code headers} / {@code customHeaders} are both reserved even though they're
     * consumed by different callers ({@code OpenAiCompatibleChatModelBuilder} and
     * {@code ModelDiscoveryService} respectively) — both are injected as real HTTP
     * headers, never as JSON body fields, so neither belongs in a passthrough body.
     */
    public static final Set<String> RESERVED_GENERATE_KWARGS_KEYS = Set.of(
            "temperature",
            "maxTokens", "max_tokens",
            "maxCompletionTokens", "max_completion_tokens",
            "topP", "top_p",
            "reasoningEffort", "reasoning_effort",
            "enableSearch", "enable_search",
            "searchStrategy", "search_strategy",
            "headers",
            "customHeaders", "custom_headers",
            "completionsPath", "completions_path",
            "modelsPath", "models_path",
            "chatOptions", "chat_options"
    );

    /**
     * Collect top-level {@code generateKwargs} entries not covered by
     * {@link #RESERVED_GENERATE_KWARGS_KEYS} so they still reach the outbound
     * request body (e.g. vLLM's {@code chat_template_kwargs} to disable Qwen
     * thinking mode). Scoped to top-level keys only — unrecognized keys nested
     * inside {@code chatOptions} are an explicit non-goal and are not forwarded.
     */
    public static Map<String, Object> collectPassthroughExtraBody(Map<String, Object> kwargs) {
        if (kwargs == null || kwargs.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> passthrough = new LinkedHashMap<>();
        kwargs.forEach((key, value) -> {
            if (key != null && !RESERVED_GENERATE_KWARGS_KEYS.contains(key)) {
                passthrough.put(key, value);
            }
        });
        return passthrough;
    }

    /**
     * Find a raw option value by key, trying the camelCase form then a
     * snake_case fallback. Returns {@code null} when neither is present.
     */
    public static Object findOptionValue(Map<String, Object> kwargs, String key) {
        Object direct = findKwarg(kwargs, key);
        if (direct != null) {
            return direct;
        }
        String snakeCase = key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        if (!snakeCase.equals(key)) {
            return findKwarg(kwargs, snakeCase);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object findKwarg(Map<String, Object> kwargs, String key) {
        if (kwargs == null || kwargs.isEmpty()) {
            return null;
        }
        if (kwargs.containsKey(key)) {
            return kwargs.get(key);
        }
        Object chatOptions = kwargs.get("chatOptions");
        if (!(chatOptions instanceof Map<?, ?>)) {
            chatOptions = kwargs.get("chat_options");
        }
        if (chatOptions instanceof Map<?, ?> optionsMap) {
            return ((Map<String, Object>) optionsMap).get(key);
        }
        return null;
    }

    /**
     * Resolve a {@code Double} option, falling back to {@code fallback} when the
     * key is absent or holds a non-numeric value.
     */
    public static Double resolveDoubleOption(String key, Double fallback, Map<String, Object> kwargs) {
        Object value = findOptionValue(kwargs, key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Invalid double generateKwargs value for {}: {}", key, text);
            }
        }
        return fallback;
    }

    /**
     * Resolve an {@code Integer} option, falling back to {@code fallback} when the
     * key is absent or holds a non-numeric value.
     */
    public static Integer resolveIntegerOption(String key, Integer fallback, Map<String, Object> kwargs) {
        Object value = findOptionValue(kwargs, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Invalid integer generateKwargs value for {}: {}", key, text);
            }
        }
        return fallback;
    }
}
