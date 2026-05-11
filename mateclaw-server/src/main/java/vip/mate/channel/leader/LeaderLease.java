package vip.mate.channel.leader;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.SimpleLock;

import java.time.Duration;
import java.util.Optional;

/**
 * A held leadership lease on a single resource (typically a channel id).
 *
 * <p>Wraps a ShedLock {@link SimpleLock} so callers don't depend on the
 * underlying lock provider. The lease must be periodically extended via
 * {@link #extend(Duration)} or it expires automatically, at which point
 * another node can claim leadership.
 *
 * <p>Threading: a single lease instance is not safe for concurrent
 * {@link #extend(Duration)} / {@link #release()} calls. The owning
 * scheduler is expected to serialize them.
 */
@Slf4j
public class LeaderLease {

    private final String name;
    private volatile SimpleLock current;
    private volatile boolean released;

    LeaderLease(String name, SimpleLock initial) {
        this.name = name;
        this.current = initial;
    }

    public String getName() {
        return name;
    }

    /**
     * Try to extend this lease for another {@code lockAtMostFor} window.
     *
     * @return true if the lease is still ours; false if it has been lost
     *         (e.g. the previous window expired before extend ran and
     *         another node acquired the lock — the caller should treat
     *         this as a leadership loss and stop the protected resource).
     */
    public boolean extend(Duration lockAtMostFor) {
        if (released) {
            return false;
        }
        try {
            Optional<SimpleLock> next = current.extend(lockAtMostFor, Duration.ZERO);
            if (next.isPresent()) {
                current = next.get();
                return true;
            }
            log.warn("[leader] Lease '{}' extend returned empty — lock lost", name);
            return false;
        } catch (Exception e) {
            log.warn("[leader] Lease '{}' extend threw: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Release this lease. Idempotent — calling release twice is safe.
     */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            current.unlock();
        } catch (Exception e) {
            log.warn("[leader] Lease '{}' unlock threw: {}", name, e.getMessage());
        }
    }
}
