package vip.mate.llm.failover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of providers currently considered usable for new chat
 * requests. Membership is the gate the failover walker checks: a provider not
 * in this pool is skipped entirely without attempting an LLM call.
 *
 * <p>Two state transitions:</p>
 * <ul>
 *   <li><b>Add</b> — at startup ({@code ProviderInitProbe}), on user-triggered
 *       reprobe, after a {@code ModelConfigChangedEvent}, or lazily when a
 *       TTL'd removal expires (see below).</li>
 *   <li><b>Remove</b> — when a request hits a provider-wide HARD error
 *       (AUTH_ERROR / BILLING) — retrying on every subsequent call wastes
 *       the user's time. SOFT errors (RATE_LIMIT / SERVER_ERROR /
 *       EMPTY_RESPONSE) keep the provider in the pool and are handled by
 *       {@link ProviderHealthTracker}'s short cooldown instead. A rejected
 *       model id (MODEL_NOT_FOUND) is model-scoped, not provider-scoped, and
 *       never evicts the provider — its sibling models stay usable.</li>
 * </ul>
 *
 * <p><b>TTL readmission</b>: AUTH_ERROR and BILLING removals are not
 * permanent. Users top up balances, aggregator quotas refresh, and providers
 * have transient 401 flaps — none of which the process can observe. Each
 * removal carries a {@code readmitAtMs} deadline (from
 * {@link ProviderHealthProperties}); once it passes, the next
 * {@link #contains} check lazily readmits the provider. No pre-readmission
 * probe: the first real call is the probe, and a still-broken provider is
 * simply re-evicted (self-correcting). INIT_PROBE and MANUAL removals never
 * auto-readmit — the former means the configuration itself is broken, the
 * latter is explicit operator intent.</p>
 *
 * <p>State is process-local; a restart re-runs the init probe. That's
 * intentional — full distributed coordination is out of scope for v1
 * (single-node and desktop deployments are the primary targets).</p>
 */
@Slf4j
@Component
public class AvailableProviderPool {

    /** Membership: providers currently usable. Add-on-success / remove-on-HARD. */
    private final Set<String> members = ConcurrentHashMap.newKeySet();

    /**
     * Last removal reason per provider id. Cleared when the provider is
     * re-added. Useful for the UI to explain "why is this provider down?"
     * and for diagnostics.
     */
    private final Map<String, RemovalReason> removalReasons = new ConcurrentHashMap<>();

    private final ProviderHealthProperties props;

    @Autowired
    public AvailableProviderPool(ProviderHealthProperties props) {
        this.props = props != null ? props : new ProviderHealthProperties();
    }

    /** Convenience constructor with default readmission TTLs. Used by tests. */
    public AvailableProviderPool() {
        this(new ProviderHealthProperties());
    }

    /** Add (or re-add) a provider to the pool. Clears any prior removal reason. */
    public void add(String providerId) {
        if (providerId == null || providerId.isEmpty()) return;
        boolean newlyAdded = members.add(providerId);
        RemovalReason previous = removalReasons.remove(providerId);
        if (newlyAdded && previous != null) {
            log.info("[Pool] re-adding provider={} (previous removal: {})", providerId, previous);
        } else if (newlyAdded) {
            log.info("[Pool] adding provider={}", providerId);
        }
    }

    /**
     * Remove a provider from the pool with a reason. Idempotent — calling
     * twice updates the reason (so the latest cause wins and the readmission
     * TTL restarts from the latest incident) but doesn't double-log.
     */
    public void remove(String providerId, RemovalSource source, String message) {
        if (providerId == null || providerId.isEmpty()) return;
        boolean wasMember = members.remove(providerId);
        long now = Instant.now().toEpochMilli();
        long ttl = readmitDelayMs(source);
        RemovalReason reason = new RemovalReason(source, message, now, ttl > 0 ? now + ttl : 0);
        removalReasons.put(providerId, reason);
        if (wasMember) {
            log.warn("[Pool] removing provider={} due to {} ({}){}", providerId, source, message,
                    ttl > 0 ? " — auto-readmission in " + (ttl / 1000) + "s" : "");
        } else {
            log.debug("[Pool] removal reason updated for already-out provider={}: {} ({})",
                    providerId, source, message);
        }
    }

    /**
     * Membership check — the walker / primary short-circuit consults this on
     * every entry. Lazily readmits a provider whose removal TTL has expired,
     * so recovery needs no scheduler thread and no user action.
     */
    public boolean contains(String providerId) {
        if (providerId == null) return false;
        if (members.contains(providerId)) return true;
        RemovalReason reason = removalReasons.get(providerId);
        if (reason != null && reason.readmitAtMs() > 0
                && Instant.now().toEpochMilli() >= reason.readmitAtMs()) {
            log.info("[Pool] readmitting provider={} — {} removal TTL expired (removed {}s ago)",
                    providerId, reason.source(),
                    (Instant.now().toEpochMilli() - reason.removedAtMs()) / 1000);
            add(providerId);
            return true;
        }
        return false;
    }

    /** Readmission TTL for a removal source; 0 = never auto-readmit. */
    private long readmitDelayMs(RemovalSource source) {
        if (source == null) return 0;
        return switch (source) {
            case BILLING -> props.getBillingReadmitMs();
            case AUTH_ERROR -> props.getAuthReadmitMs();
            case INIT_PROBE, MANUAL -> 0;
        };
    }

    /**
     * Diagnostic snapshot for admin endpoints / tests. Returns providerId →
     * either {@code null} (in pool) or the latest {@link RemovalReason}.
     * The returned map is a stable copy; in-pool entries are present with
     * {@code null} value so callers can iterate the union of in/out.
     */
    public Map<String, RemovalReason> snapshot() {
        Map<String, RemovalReason> out = new LinkedHashMap<>();
        for (String id : members) out.put(id, null);
        removalReasons.forEach((id, reason) -> {
            if (!out.containsKey(id)) out.put(id, reason);
        });
        return out;
    }

    /** Clear everything. Intended for tests; no production code path calls this. */
    void reset() {
        members.clear();
        removalReasons.clear();
    }

    // ============================================================
    // Records
    // ============================================================

    /**
     * Why a provider was removed from the pool. {@code removedAtMs} is epoch
     * milliseconds; {@code readmitAtMs} is the epoch-millisecond deadline
     * after which {@link #contains} lazily readmits the provider ({@code 0}
     * = never auto-readmitted).
     */
    public record RemovalReason(RemovalSource source, String message, long removedAtMs, long readmitAtMs) {}

    /**
     * Categorical source of a pool removal. Covers the provider-wide HARD
     * error types from {@code NodeStreamingChatHelper.ErrorType} plus
     * {@link #INIT_PROBE} for startup probe failures. SOFT errors (RATE_LIMIT /
     * SERVER_ERROR) never appear here — they're handled by
     * {@link ProviderHealthTracker} cooldown. A rejected model id is
     * model-scoped and never removes a whole provider, so there is no
     * {@code MODEL_NOT_FOUND} source.
     */
    public enum RemovalSource {
        AUTH_ERROR,
        BILLING,
        INIT_PROBE,
        MANUAL
    }
}
