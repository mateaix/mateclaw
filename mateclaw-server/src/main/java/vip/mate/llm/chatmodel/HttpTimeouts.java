package vip.mate.llm.chatmodel;

import java.time.Duration;

/**
 * Central resolver for the per-LLM-request HTTP read timeout, so
 * {@link vip.mate.llm.model.ModelConfigEntity#getRequestTimeoutSeconds()}
 * can override the default 180s without each chatmodel builder inventing
 * its own fallback chain.
 *
 * <p>Used by the OpenAI-compatible, Anthropic and Claude Code chat model
 * builders to apply consistent connect / read timeouts.
 *
 * <p>Connect timeout stays at the canonical 10s — long-tail thinking
 * latency manifests on the read path, not on connect.
 */
public final class HttpTimeouts {

    /** Connect timeout — never overridable; 10s is enough for any sane endpoint. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Default read timeout when no per-model override is set. Matches the
     * historical hardcoded value so unset rows behave identically to the
     * earlier baseline.
     */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(180);

    private HttpTimeouts() {}

    /**
     * Resolve the effective read timeout: the override if positive, else the
     * canonical 180s default. Null and non-positive values fall back, so
     * callers can pass {@code modelConfig.getRequestTimeoutSeconds()} directly
     * without null-checks.
     */
    public static Duration resolveReadTimeout(Integer override) {
        if (override == null || override <= 0) {
            return DEFAULT_READ_TIMEOUT;
        }
        return Duration.ofSeconds(override);
    }
}
