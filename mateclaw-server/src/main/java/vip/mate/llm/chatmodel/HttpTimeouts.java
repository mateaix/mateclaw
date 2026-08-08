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

    /**
     * Default inter-frame idle timeout for streaming LLM responses
     * (the reactor {@code .timeout(Duration)} applied on the chat model's
     * delta Flux). Distinct from {@link #DEFAULT_READ_TIMEOUT}: the JDK
     * HttpClient request timeout (which is what {@code setReadTimeout}
     * ultimately maps to) only protects up to the response headers — once
     * the headers arrive it stops the clock, so a provider that accepts the
     * connection, returns 200 + a first SSE frame, then goes silent hangs
     * the body Flux forever with no exception and no failover signal
     * (issue #585). The reactor idle timeout fills that gap: it measures the
     * gap between successive stream elements, so total silence for this long
     * propagates a {@code TimeoutException} down the existing error path.
     * <p>
     * Defaults to the same 180s as the read timeout — long-thinking models
     * can legitimately sit between frames for a while, but complete silence
     * for three minutes is a dead provider, not a slow one.
     */
    public static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(180);

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

    /**
     * Resolve the effective streaming inter-frame idle timeout. Same fallback
     * semantics as {@link #resolveReadTimeout(Integer)}: a positive override
     * wins, otherwise the canonical 180s default applies. Callers can pass
     * {@code modelConfig.getRequestTimeoutSeconds()} directly so the per-model
     * knob governs both the connect-level read timeout and the body-level
     * idle timeout from a single config field.
     */
    public static Duration resolveStreamIdleTimeout(Integer override) {
        if (override == null || override <= 0) {
            return DEFAULT_STREAM_IDLE_TIMEOUT;
        }
        return Duration.ofSeconds(override);
    }
}
