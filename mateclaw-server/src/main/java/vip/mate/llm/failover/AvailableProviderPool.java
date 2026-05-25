package vip.mate.llm.failover;

import lombok.extern.slf4j.Slf4j;
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
 *       reprobe, or after a {@code ModelConfigChangedEvent}.</li>
 *   <li><b>Remove</b> — when a request hits a provider-wide HARD error
 *       (AUTH_ERROR / BILLING) — these don't self-heal, so retrying on
 *       every subsequent call wastes the user's time. SOFT errors
 *       (RATE_LIMIT / SERVER_ERROR / EMPTY_RESPONSE) keep the provider in
 *       the pool and are handled by {@link ProviderHealthTracker}'s short
 *       cooldown instead. A rejected model id (MODEL_NOT_FOUND) is
 *       model-scoped, not provider-scoped, and never evicts the provider —
 *       its sibling models stay usable.</li>
 * </ul>
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
     * twice updates the reason (so the latest cause wins) but doesn't double-log.
     */
    public void remove(String providerId, RemovalSource source, String message) {
        if (providerId == null || providerId.isEmpty()) return;
        boolean wasMember = members.remove(providerId);
        RemovalReason reason = new RemovalReason(source, message, Instant.now().toEpochMilli());
        removalReasons.put(providerId, reason);
        if (wasMember) {
            log.warn("[Pool] removing provider={} due to {} ({})", providerId, source, message);
        } else {
            log.debug("[Pool] removal reason updated for already-out provider={}: {} ({})",
                    providerId, source, message);
        }
    }

    /** Membership check — the walker / primary short-circuit consults this on every entry. */
    public boolean contains(String providerId) {
        return providerId != null && members.contains(providerId);
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

    /** Why a provider was removed from the pool. {@code removedAtMs} is epoch milliseconds. */
    public record RemovalReason(RemovalSource source, String message, long removedAtMs) {}

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
