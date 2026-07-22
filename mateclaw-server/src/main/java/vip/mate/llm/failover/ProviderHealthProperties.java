package vip.mate.llm.failover;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RFC-009 P3.3: thresholds for the per-provider health tracker.
 *
 * <pre>
 * mateclaw:
 *   llm:
 *     failover:
 *       health:
 *         enabled: true
 *         failure-threshold: 3      # consecutive failures before cooldown
 *         cooldown-ms: 300000       # 5 minutes
 *         billing-readmit-ms: 3600000   # auto-readmit a BILLING-evicted provider after 1h (0 = never)
 *         auth-readmit-ms: 1800000      # auto-readmit an AUTH-evicted provider after 30min (0 = never)
 * </pre>
 */
@ConfigurationProperties(prefix = "mateclaw.llm.failover.health")
public class ProviderHealthProperties {

    /** Master switch. {@code false} disables tracking entirely; the chain walker tries every provider every time. */
    private boolean enabled = true;

    /** Consecutive failures (any kind) at which a provider enters cooldown. */
    private int failureThreshold = 3;

    /** Cooldown window in milliseconds. */
    private long cooldownMs = 300_000L;

    /**
     * TTL after which a provider HARD-removed for BILLING is lazily readmitted
     * to the available pool. Users top up balances and aggregator quotas
     * refresh hourly — without this, recovery requires a manual reprobe or a
     * process restart. {@code 0} disables auto-readmission.
     */
    private long billingReadmitMs = 3_600_000L;

    /**
     * TTL after which a provider HARD-removed for AUTH_ERROR is lazily
     * readmitted. Bounds the damage of provider-side 401 flaps; a genuinely
     * bad key just gets re-evicted by its first post-readmission call.
     * {@code 0} disables auto-readmission.
     */
    private long authReadmitMs = 1_800_000L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = Math.max(1, failureThreshold);
    }

    public long getCooldownMs() { return cooldownMs; }
    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = Math.max(1000, cooldownMs);
    }

    public long getBillingReadmitMs() { return billingReadmitMs; }
    public void setBillingReadmitMs(long billingReadmitMs) {
        this.billingReadmitMs = Math.max(0, billingReadmitMs);
    }

    public long getAuthReadmitMs() { return authReadmitMs; }
    public void setAuthReadmitMs(long authReadmitMs) {
        this.authReadmitMs = Math.max(0, authReadmitMs);
    }
}
