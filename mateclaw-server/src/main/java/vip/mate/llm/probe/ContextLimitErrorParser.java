package vip.mate.llm.probe;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the model's context-window size from a "prompt too long" error
 * message. Serves as the reconciliation fallback when probing is unavailable:
 * the serving stack itself states its limit in the rejection text (e.g. vLLM
 * reports {@code max_model_len}), so one failed call teaches the resolver the
 * true window for every subsequent turn.
 */
public final class ContextLimitErrorParser {

    /** Reject absurd parses — anything below one model page or above 10M tokens. */
    private static final int MIN_PLAUSIBLE = 512;
    private static final int MAX_PLAUSIBLE = 10_000_000;

    /**
     * Ordered from most specific to most generic. Each pattern anchors the
     * number on the limit-keyword side so "requested 50000 tokens, maximum
     * context length is 32768" yields 32768, not 50000.
     */
    private static final Pattern[] LIMIT_PATTERNS = {
            // vLLM: "... exceeds the max_model_len 32768" / "max_model_len=32768"
            Pattern.compile("max_model_len\\D{0,20}?(\\d{3,8})", Pattern.CASE_INSENSITIVE),
            // OpenAI-style: "This model's maximum context length is 4096 tokens"
            Pattern.compile("maximum context length is\\s*(\\d{3,8})", Pattern.CASE_INSENSITIVE),
            // vLLM alt: "maximum model length 32768"
            Pattern.compile("maximum model length\\D{0,20}?(\\d{3,8})", Pattern.CASE_INSENSITIVE),
            // Ollama-style knob in the rejection text: "num_ctx 8192"
            Pattern.compile("num_ctx\\D{0,10}?(\\d{3,8})", Pattern.CASE_INSENSITIVE),
            // Generic: "context length of only 8192" / "context length limit: 8192"
            Pattern.compile("context length (?:of only|limit)\\D{0,10}?(\\d{3,8})", Pattern.CASE_INSENSITIVE),
    };

    private ContextLimitErrorParser() {
    }

    /**
     * @return the context window the server reported in the error text, or
     *         empty when no pattern matches or the number is implausible.
     */
    public static OptionalInt extractLimit(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return OptionalInt.empty();
        }
        for (Pattern pattern : LIMIT_PATTERNS) {
            Matcher matcher = pattern.matcher(errorMessage);
            if (matcher.find()) {
                try {
                    int value = Integer.parseInt(matcher.group(1));
                    if (value >= MIN_PLAUSIBLE && value <= MAX_PLAUSIBLE) {
                        return OptionalInt.of(value);
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to the next pattern
                }
            }
        }
        return OptionalInt.empty();
    }
}
