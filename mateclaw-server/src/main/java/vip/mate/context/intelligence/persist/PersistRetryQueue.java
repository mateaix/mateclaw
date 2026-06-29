package vip.mate.context.intelligence.persist;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.mate.context.intelligence.probe.WindowProbeSnapshot;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * DB persist failure retry queue (in-memory, single retry).
 * <p>
 * When DB persist fails, the snapshot is buffered to this queue. It is retried once on the next signal.
 * <p>
 * <b>Key design</b> (§5.6):
 * <ul>
 *   <li>Queue cap of 50 entries, drop oldest when exceeded (prevent memory leak)</li>
 *   <li>Retry only once, do not re-enqueue (avoid unbounded accumulation)</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PersistRetryQueue {

    private static final int MAX_QUEUE_SIZE = 50;

    private final ConcurrentLinkedQueue<PendingPersist> queue = new ConcurrentLinkedQueue<>();

    private final ObjectProvider<WindowStateRepository> repositoryProvider;

    public PersistRetryQueue(ObjectProvider<WindowStateRepository> repositoryProvider) {
        this.repositoryProvider = repositoryProvider;
    }

    /** Enqueue (drop oldest when cap exceeded) */
    public void offer(String key, WindowProbeSnapshot snapshot) {
        while (queue.size() >= MAX_QUEUE_SIZE) {
            queue.poll();
        }
        queue.offer(new PendingPersist(key, snapshot));
    }

    /** Retry once (dequeue one and retry, do not re-enqueue) */
    public void retryOnce() {
        PendingPersist pending = queue.poll();
        if (pending == null) {
            return;
        }
        WindowStateRepository repo = repositoryProvider.getIfAvailable();
        if (repo == null) {
            return;
        }
        try {
            repo.persist(pending.key(), pending.snapshot());
        } catch (Exception e) {
            log.debug("[ContextIntel] Retry persist failed, dropping: {}", pending.key());
        }
    }

    /** Number of pending retry entries */
    public int size() {
        return queue.size();
    }

    /** Buffered pending-persist entry */
    record PendingPersist(String key, WindowProbeSnapshot snapshot) {}
}
