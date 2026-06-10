package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Reads typed values out of a provider's {@code generateKwargs} map.
 *
 * <p>A lookup tries the camelCase key first, then a snake_case fallback, and also
 * descends into a nested {@code chatOptions} map — so an admin may specify an
 * option under any of those shapes. Shared by the OpenAI-compatible chat model
 * builder and the reasoning-effort resolver.
 */
@Slf4j
public final class ProviderGenerateKwargs {

    private ProviderGenerateKwargs() {}

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
