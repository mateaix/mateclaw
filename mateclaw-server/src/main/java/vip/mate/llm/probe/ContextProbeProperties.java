package vip.mate.llm.probe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for local-model context-window probing.
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.context.probe")
public class ContextProbeProperties {

    /**
     * Master switch for probe traffic and error-text reconciliation. When
     * false, {@code resolveMaxInputTokens} honors explicit config and the
     * built-in window table only — no request ever leaves the process.
     */
    private boolean enabled = true;

    /** Per-request read timeout. Probing must never hold up chat startup. */
    private int timeoutMs = 1000;

    /**
     * How long a probe result (positive or negative) stays cached. Local
     * servers like LM Studio allow hot-swapping models, so results must not
     * be persisted — a short in-memory TTL keeps them honest.
     */
    private int cacheTtlSeconds = 600;
}
