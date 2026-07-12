package vip.mate.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks which conversations are currently in-flight (a chat turn is actively
 * running) and holds a bounded per-conversation queue of pending environment
 * notifications.
 *
 * <p>This is the agent-side counterpart to {@code ChatStreamTracker} — but
 * whereas that tracker is SSE-pipeline-specific and lives in {@code channel.web},
 * this registry covers ALL chat entry points (sync {@code chat}, streaming
 * {@code chatStream}, {@code execute}, {@code chatWithReplay}) because it is
 * wired into {@code AgentService.withLifecycleSync/Flux} which every path
 * funnels through.
 *
 * <p><b>Lifecycle:</b>
 * <ul>
 *   <li>{@link #register} is called when a turn starts (inside
 *       {@code withLifecycleSync} / {@code withLifecycleFlux}).</li>
 *   <li>{@link #unregister} is called when the turn ends (in {@code finally} /
 *       {@code doFinally}).</li>
 *   <li>Between turns the conversation is absent from the registry, so events
 *       fired between turns are silently dropped — this is intentional: the
 *       agent cache is invalidated by the existing {@code @EventListener}
 *       methods in {@code AgentService}, so the next turn rebuilds with fresh
 *       tool/skill state.</li>
 * </ul>
 *
 * <p><b>Queue bounds:</b> each conversation's notification queue is capped at
 * {@link #MAX_NOTIFICATIONS_PER_CONVERSATION}. When full, the oldest entry is
 * evicted. This prevents unbounded memory growth if events fire faster than
 * the agent consumes them (e.g. a tight MCP reconnection loop).
 *
 * <p>All operations are non-blocking and thread-safe.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class RunningConversationRegistry {

    /** Max pending notifications per conversation before oldest evicts. */
    static final int MAX_NOTIFICATIONS_PER_CONVERSATION = 10;

    private final ConcurrentMap<String, ConversationHandle> active = new ConcurrentHashMap<>();

    /**
     * Mark a conversation as actively running. Idempotent — if already
     * registered (e.g. concurrent sub-agent delegation into the same
     * conversation), only refreshes {@code lastActiveAt}.
     */
    public void register(String conversationId, Long agentId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        active.compute(conversationId, (k, existing) -> {
            Instant now = Instant.now();
            if (existing == null) {
                return new ConversationHandle(agentId, now, now, new ConcurrentLinkedQueue<>());
            }
            existing.lastActiveAt = now;
            return existing;
        });
    }

    /**
     * Mark a conversation as no longer running. Safe to call multiple times
     * and on never-registered ids. Any pending notifications are discarded.
     */
    public void unregister(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        active.remove(conversationId);
    }

    /** @return true iff a turn is currently in-flight for this conversation. */
    public boolean isActive(String conversationId) {
        return conversationId != null && active.containsKey(conversationId);
    }

    /** @return a snapshot of all currently-running conversation ids. */
    public Set<String> activeConversations() {
        return Collections.unmodifiableSet(active.keySet());
    }

    /**
     * Push a notification to a single conversation's queue. No-op if the
     * conversation is not active (event fired between turns).
     */
    public void enqueue(String conversationId, EnvironmentNotification notification) {
        if (conversationId == null || notification == null) {
            return;
        }
        ConversationHandle handle = active.get(conversationId);
        if (handle == null) {
            return;
        }
        ConcurrentLinkedQueue<EnvironmentNotification> q = handle.notifications;
        while (q.size() >= MAX_NOTIFICATIONS_PER_CONVERSATION) {
            q.poll(); // evict oldest
        }
        q.offer(notification);
    }

    /**
     * Push a notification to ALL currently-active conversations. Used by
     * {@link EnvironmentEventRouter} when an event is not conversation-scoped
     * (e.g. an MCP server disconnect affects every agent that has tools from
     * that server, and we don't have a cheap way to filter).
     */
    public void broadcast(EnvironmentNotification notification) {
        if (notification == null) {
            return;
        }
        for (String convId : active.keySet()) {
            enqueue(convId, notification);
        }
    }

    /**
     * Drain and return all pending notifications for a conversation. The
     * queue is emptied by this call — each notification is delivered at most
     * once. Returns an empty list for inactive / unknown conversations.
     */
    public List<EnvironmentNotification> drain(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        ConversationHandle handle = active.get(conversationId);
        if (handle == null) {
            return List.of();
        }
        List<EnvironmentNotification> out = new ArrayList<>();
        EnvironmentNotification n;
        while ((n = handle.notifications.poll()) != null) {
            out.add(n);
        }
        return out;
    }

    // ==================== Stale-handle cleanup ====================

    /**
     * Remove conversations whose {@code lastActiveAt} is older than
     * {@code maxAge} ago. Defensive cleanup for the case where
     * {@code unregister} was skipped due to an exception path that
     * bypassed the {@code finally}/{@code doFinally} guards.
     *
     * <p>Safe to call concurrently with {@link #register} /
     * {@link #unregister} — uses {@link ConcurrentHashMap#entrySet()}
     * iterator's weak consistency.
     *
     * @return the number of stale handles removed
     */
    public int cleanupStale(Duration maxAge) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(maxAge);
        int removed = 0;
        for (var entry : active.entrySet()) {
            ConversationHandle handle = entry.getValue();
            if (handle != null && handle.lastActiveAt != null
                    && handle.lastActiveAt.isBefore(cutoff)) {
                // Use remove(key, value) to avoid removing a handle that was
                // just refreshed by a concurrent register() call.
                if (active.remove(entry.getKey(), handle)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("[RunningConversationRegistry] Cleaned up {} stale conversation handle(s) "
                    + "(older than {})", removed, maxAge);
        }
        return removed;
    }

    /**
     * Periodic background sweep — runs every 5 minutes (1 minute initial
     * delay after startup). Removes handles inactive for more than 30
     * minutes, which almost certainly indicates a leaked registration
     * (normal turns complete in seconds to minutes).
     *
     * <p>The 30-minute threshold is intentionally generous: active
     * long-running conversations (e.g. a multi-hour research task) refresh
     * {@code lastActiveAt} on every iteration via {@link #register}, so
     * they won't be swept.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void scheduledCleanup() {
        try {
            cleanupStale(Duration.ofMinutes(30));
        } catch (Exception e) {
            log.warn("[RunningConversationRegistry] Scheduled cleanup failed: {}", e.getMessage());
        }
    }

    // ==================== Internal handle ====================

    private static final class ConversationHandle {
        final Long agentId;
        volatile Instant lastActiveAt;
        final ConcurrentLinkedQueue<EnvironmentNotification> notifications;

        ConversationHandle(Long agentId, Instant startedAt, Instant lastActiveAt,
                           ConcurrentLinkedQueue<EnvironmentNotification> notifications) {
            this.agentId = agentId;
            this.lastActiveAt = lastActiveAt;
            this.notifications = notifications;
        }
    }
}
