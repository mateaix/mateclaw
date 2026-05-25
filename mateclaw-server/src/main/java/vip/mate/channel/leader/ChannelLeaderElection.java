package vip.mate.channel.leader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Distributed leader election for channel adapters whose upstream
 * service rejects multiple concurrent connections from the same bot
 * credentials.
 *
 * <p>Typical examples are WebSocket-mode IM channels: Feishu's Lark
 * SDK enforces a per-app connection cap (~2) and QQ's bot gateway
 * rejects duplicate {@code IDENTIFY} sessions. Without coordination,
 * every node of a multi-instance deployment trips that cap on startup
 * and reconnects in a tight loop.
 *
 * <p>Backed by ShedLock's {@link LockProvider} (already wired for cron
 * coordination), so single-node deployments incur no extra
 * infrastructure — the lock is acquired trivially on the only node.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #tryAcquire(String)} returns the lease, or empty if
 *       another node already holds it.</li>
 *   <li>The owning node must call {@link LeaderLease#extend(Duration)}
 *       on a fixed cadence (heartbeat) shorter than the
 *       {@code lockAtMostFor} window, or the lease expires and another
 *       node may claim it.</li>
 *   <li>If a node dies without releasing, the lease auto-expires after
 *       {@code lockAtMostFor}, after which any waiting follower can
 *       become leader on its next retry tick.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelLeaderElection {

    /**
     * How long the lock is held without a renewal. A failed node's lease
     * stays locked for this long before another node can take over —
     * so longer values increase failover latency, shorter values increase
     * the risk of false handover during a GC pause or DB hiccup.
     */
    public static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(60);

    private final LockProvider lockProvider;

    /**
     * Attempt to acquire leadership for the given key.
     *
     * @param key a stable identifier for the resource (e.g.
     *            {@code "feishu:42"}). Used verbatim as the underlying
     *            lock name (prefixed by this class to avoid collisions
     *            with other lock users).
     * @return an empty optional if another node already holds the lease,
     *         otherwise a {@link LeaderLease} that the caller is
     *         responsible for periodically extending and finally
     *         releasing.
     */
    public Optional<LeaderLease> tryAcquire(String key) {
        String lockName = "channel-leader:" + key;
        LockConfiguration config = new LockConfiguration(
                Instant.now(),
                lockName,
                LOCK_AT_MOST_FOR,
                Duration.ZERO);
        Optional<SimpleLock> lock = lockProvider.lock(config);
        if (lock.isEmpty()) {
            log.debug("[leader] Lock '{}' is held by another node", lockName);
            return Optional.empty();
        }
        log.info("[leader] Acquired lease '{}'", lockName);
        return Optional.of(new LeaderLease(lockName, lock.get()));
    }
}
