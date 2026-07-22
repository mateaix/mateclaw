package vip.mate.llm.failover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RFC-009 P3.3: per-provider health tracker for the multi-model failover chain.
 *
 * <p>Tracks consecutive failure counts per provider id. When a provider hits
 * {@link ProviderHealthProperties#getFailureThreshold} consecutive failures,
 * it enters a cooldown window of {@link ProviderHealthProperties#getCooldownMs}
 * milliseconds during which the chain walker skips it entirely. A successful
 * call resets the counter and clears the cooldown immediately.</p>
 *
 * <p>Without this guard, a provider whose API key has been revoked or whose
 * service is degraded would be re-tried on every conversation turn, eating
 * the full retry budget and adding seconds of latency before the chain
 * advances. With it, the first round trips through the broken provider; the
 * next N turns skip it instantly and try the next entry directly.</p>
 *
 * <p>State is in-memory only — process restart resets all counters. That is
 * intentional for v1: per-process tracking is enough for desktop / single-node
 * deployments, and avoids the complication of distributed coordination. A
 * future RFC may upgrade this to a {@code mate_provider_health} row.</p>
 */
@Slf4j
@Component
@Configuration
@EnableConfigurationProperties(ProviderHealthProperties.class)
public class ProviderHealthTracker {

    private final ProviderHealthProperties props;
    private final ConcurrentHashMap<String, AtomicLong> consecutiveFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cooldownUntilMs = new ConcurrentHashMap<>();

    public ProviderHealthTracker(ProviderHealthProperties props) {
        this.props = props;
    }

    /**
     * Returns true when {@code providerId} is in cooldown and should be
     * skipped by the fallback chain walker. Lazily expires stale cooldowns
     * during the lookup so we don't accumulate dead entries.
     */
    public boolean isInCooldown(String providerId) {
        if (!props.isEnabled() || providerId == null) return false;
        Long until = cooldownUntilMs.get(providerId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            // Cooldown expired — clear so the provider can be tried again.
            cooldownUntilMs.remove(providerId);
            consecutiveFailures.computeIfPresent(providerId, (k, v) -> { v.set(0); return v; });
            return false;
        }
        return true;
    }

    /**
     * Upper bound for a provider-supplied cooldown override. Guards against
     * a provider returning an absurd {@code Retry-After} (misconfigured proxy,
     * clock-skewed reset timestamp) locking a provider out for days.
     */
    static final long MAX_COOLDOWN_OVERRIDE_MS = 2 * 60 * 60 * 1000L;

    /**
     * Record a single failure against {@code providerId}. When the counter
     * reaches the configured threshold, the provider enters cooldown.
     */
    public void recordFailure(String providerId) {
        recordFailure(providerId, 0);
    }

    /**
     * Record a failure with an optional provider-supplied cooldown override
     * (milliseconds), typically parsed from a 429 response's
     * {@code Retry-After} / rate-limit reset headers.
     *
     * <p>When {@code cooldownOverrideMs > 0} the provider has stated exactly
     * when capacity returns, so the cooldown starts <b>immediately</b> —
     * waiting for {@link ProviderHealthProperties#getFailureThreshold} more
     * consecutive failures would burn extra calls against a window the
     * provider already announced. The override is clamped to
     * {@link #MAX_COOLDOWN_OVERRIDE_MS} and never <i>shortens</i> an active
     * cooldown.</p>
     */
    public void recordFailure(String providerId, long cooldownOverrideMs) {
        if (!props.isEnabled() || providerId == null) return;
        AtomicLong counter = consecutiveFailures.computeIfAbsent(providerId, k -> new AtomicLong());
        long failures = counter.incrementAndGet();
        if (cooldownOverrideMs > 0) {
            long clamped = Math.min(cooldownOverrideMs, MAX_COOLDOWN_OVERRIDE_MS);
            long cooldownEnd = System.currentTimeMillis() + clamped;
            cooldownUntilMs.merge(providerId, cooldownEnd, Math::max);
            log.warn("[ProviderHealth] provider={} entering cooldown for {}s (provider-stated retry window)",
                    providerId, clamped / 1000);
            return;
        }
        if (failures >= props.getFailureThreshold()) {
            long cooldownEnd = System.currentTimeMillis() + props.getCooldownMs();
            cooldownUntilMs.put(providerId, cooldownEnd);
            log.warn("[ProviderHealth] provider={} hit {} consecutive failures, entering cooldown for {}s",
                    providerId, failures, props.getCooldownMs() / 1000);
        }
    }

    /** Successful call: reset the failure counter and clear any active cooldown. */
    public void recordSuccess(String providerId) {
        if (!props.isEnabled() || providerId == null) return;
        AtomicLong counter = consecutiveFailures.get(providerId);
        if (counter != null) counter.set(0);
        if (cooldownUntilMs.remove(providerId) != null) {
            log.info("[ProviderHealth] provider={} recovered, cooldown cleared", providerId);
        }
    }

    /**
     * Diagnostic snapshot for admin endpoints / tests. Returns a stable view
     * with provider id → (failures, cooldown remaining ms; 0 if not in cooldown).
     */
    public Map<String, ProviderHealthSnapshot> snapshot() {
        Map<String, ProviderHealthSnapshot> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        consecutiveFailures.forEach((id, counter) -> {
            Long until = cooldownUntilMs.get(id);
            long remaining = until != null && until > now ? until - now : 0;
            out.put(id, new ProviderHealthSnapshot(counter.get(), remaining));
        });
        // Include cooldown-only entries (failure counter may have been zeroed by snapshot timing race).
        cooldownUntilMs.forEach((id, until) -> {
            if (!out.containsKey(id)) {
                long remaining = until > now ? until - now : 0;
                out.put(id, new ProviderHealthSnapshot(0, remaining));
            }
        });
        return out;
    }

    /** Test-only: drop all state. Not exposed via any public bean method. */
    void reset() {
        consecutiveFailures.clear();
        cooldownUntilMs.clear();
    }

    public record ProviderHealthSnapshot(long consecutiveFailures, long cooldownRemainingMs) {}
}
