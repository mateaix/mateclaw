package vip.mate.kbopen.research;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.wiki.service.WikiResearchService.ResearchResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks active Deep Research sessions started via the Open API.
 *
 * <p>Each session records the owning API key id + kbId so that status/cancel
 * endpoints can authorize access (a caller can only query/cancel their own
 * sessions). Results are stored on completion for the status endpoint to
 * return synchronously.
 *
 * <p>This is an in-memory registry (single-node). For multi-node, sessions
 * would need to live in a shared store — but research is short-lived (< 1 min
 * typical) and the SSE stream must connect to the node running the job, so
 * sticky routing is a prerequisite anyway.
 *
 * <h3>Lifecycle invariants</h3>
 * <ul>
 *   <li>{@link Status#CANCELLED} is a <b>sticky</b> terminal state: a late
 *       {@link #complete}/{@link #fail} arriving after cancel is a no-op, so
 *       the user who cancelled never sees a COMPLETED report.</li>
 *   <li>Terminal sessions are evicted by {@link #evictExpired} after
 *       {@code mate.kbopen.research.session-ttl} (default 30 min) so the map
 *       cannot grow without bound.</li>
 *   <li>{@link #startIfAllowed} enforces a per-key concurrency cap
 *       ({@code mate.kbopen.research.max-concurrent-per-key}, default 3) as a
 *       DoS / runaway-cost guard on top of the per-minute rate limiter.</li>
 * </ul>
 */
@Slf4j
@Component
public class KbResearchSessionRegistry {

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

    /** Adds {@code updatedAt} so the eviction sweep can find stale terminals. */
    public record Session(String sessionId, Long keyId, Long kbId, String topic, Status status,
                          ResearchResult result, String error, Instant updatedAt) {

        /** Convenience for {@link #register} (status=RUNNING, no result). */
        static Session running(String sessionId, Long keyId, Long kbId, String topic) {
            return new Session(sessionId, keyId, kbId, topic, Status.RUNNING, null, null, Instant.now());
        }

        private Session with(Status newStatus, ResearchResult res, String err) {
            return new Session(sessionId, keyId, kbId, topic, newStatus, res, err, Instant.now());
        }
    }

    /** Exception thrown by {@link #startIfAllowed} when the per-key cap is hit. */
    public static class TooManyConcurrentException extends RuntimeException {
        public TooManyConcurrentException(String msg) { super(msg); }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Per-key count of RUNNING sessions, kept in lock-step with the
     * {@code status==RUNNING} sessions in {@link #sessions}. Maintained
     * atomically so {@link #startIfAllowed} can enforce the cap without a
     * check-then-act race (two concurrent starts could both pass a stream-based
     * count and both put). Incremented on start, decremented on each
     * RUNNING→terminal transition (complete/fail/cancel).
     */
    private final Map<Long, AtomicInteger> runningPerKey = new ConcurrentHashMap<>();

    private final int maxConcurrentPerKey;
    private final Duration sessionTtl;

    public KbResearchSessionRegistry(
            @Value("${mate.kbopen.research.max-concurrent-per-key:3}") int maxConcurrentPerKey,
            @Value("${mate.kbopen.research.session-ttl:PT30M}") Duration sessionTtl) {
        this.maxConcurrentPerKey = maxConcurrentPerKey;
        this.sessionTtl = sessionTtl;
    }

    /**
     * Reserve a slot for a new session, enforcing the per-key concurrency cap.
     *
     * <p>Atomic: {@code incrementAndGet} + rollback on overflow, so concurrent
     * starts for the same key cannot both slip past the cap. The previous
     * stream-and-count impl had a check-then-act race.
     *
     * @throws TooManyConcurrentException if {@code keyId} already has
     *         {@code maxConcurrentPerKey} RUNNING sessions.
     */
    public void startIfAllowed(String sessionId, Long keyId, Long kbId, String topic) {
        AtomicInteger count = runningPerKey.computeIfAbsent(keyId, k -> new AtomicInteger());
        int now = count.incrementAndGet();
        if (now > maxConcurrentPerKey) {
            count.decrementAndGet(); // rollback — slot was not granted
            throw new TooManyConcurrentException(
                    "API key already has " + maxConcurrentPerKey
                            + " running research session(s); limit is " + maxConcurrentPerKey);
        }
        sessions.put(sessionId, Session.running(sessionId, keyId, kbId, topic));
    }

    public Optional<Session> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** RUNNING → COMPLETED. No-op on a session that was already CANCELLED (sticky terminal). */
    public void complete(String sessionId, ResearchResult result) {
        sessions.computeIfPresent(sessionId, (k, s) -> {
            if (s.status() == Status.CANCELLED) {
                return s; // sticky terminal — no transition, no counter change
            }
            decrementRunning(s.keyId()); // RUNNING → COMPLETED releases the slot
            return s.with(Status.COMPLETED, result, null);
        });
    }

    /** RUNNING → FAILED. No-op on a session that was already CANCELLED (sticky terminal). */
    public void fail(String sessionId, String error) {
        sessions.computeIfPresent(sessionId, (k, s) -> {
            if (s.status() == Status.CANCELLED) {
                return s;
            }
            decrementRunning(s.keyId());
            return s.with(Status.FAILED, null, error);
        });
    }

    /** RUNNING → CANCELLED. Returns false if the session is missing or already terminal. */
    public boolean cancel(String sessionId) {
        Session[] before = new Session[1];
        sessions.computeIfPresent(sessionId, (k, s) -> {
            before[0] = s;
            if (s.status() == Status.RUNNING) {
                decrementRunning(s.keyId());
                return s.with(Status.CANCELLED, null, null);
            }
            return s;
        });
        return before[0] != null && before[0].status() == Status.RUNNING;
    }

    /** Release one running-slot for {@code keyId}, floored at 0. */
    private void decrementRunning(Long keyId) {
        AtomicInteger count = runningPerKey.get(keyId);
        if (count != null) {
            // getAndDeccrement would go negative; clamp instead so repeated
            // terminal transitions (e.g. complete after cancel) can't drift.
            while (true) {
                int cur = count.get();
                if (cur <= 0) break;
                if (count.compareAndSet(cur, cur - 1)) break;
            }
        }
    }

    /**
     * Drop terminal sessions older than {@code sessionTtl}. Called periodically
     * by {@link #evictExpired}; public for testing.
     */
    public int evictExpired(Instant now) {
        int removed = 0;
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            if (s.status() != Status.RUNNING && now.isAfter(s.updatedAt().plus(sessionTtl))) {
                if (sessions.remove(e.getKey()) != null) removed++;
            }
        }
        if (removed > 0) {
            log.info("[KbResearchSessionRegistry] Evicted {} terminal session(s) older than {}", removed, sessionTtl);
        }
        return removed;
    }

    /** Scheduled sweep — runs every 5 min. */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void evictExpired() {
        evictExpired(Instant.now());
    }
}
