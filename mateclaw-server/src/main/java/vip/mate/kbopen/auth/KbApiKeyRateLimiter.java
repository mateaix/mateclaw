package vip.mate.kbopen.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key sliding-window rate limiter for KB Open API (R2).
 *
 * <p>Follows the same pattern as {@code TriggerRateLimiter}: each key keeps a
 * 60-second window of request timestamps; a request is admitted iff fewer
 * than {@code rateLimitPerMin} entries already live in the window. Local to
 * this node — for multi-node deployments the cap is per-node, not global.
 * v0 accepts this trade because the alternative (DB-backed counters) costs a
 * round-trip on every request.
 */
@Component
public class KbApiKeyRateLimiter {

    private final Map<Long, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final Duration windowSize = Duration.ofMinutes(1);

    /**
     * Try to admit a request for {@code keyId} at {@code now}. Returns
     * {@code true} when the request fits under {@code limitPerMin};
     * {@code false} when the window is full (caller should return 429).
     */
    public boolean tryAcquire(long keyId, int limitPerMin, Instant now) {
        if (limitPerMin <= 0) return true;
        Deque<Instant> window = windows.computeIfAbsent(keyId, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(windowSize);
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMin) return false;
            window.addLast(now);
            return true;
        }
    }
}
