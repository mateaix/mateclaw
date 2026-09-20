package vip.mate.plugin.mem0;

import org.slf4j.Logger;
import vip.mate.plugin.api.memory.PluginMemoryProvider;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory provider that bridges MateClaw's per-turn lifecycle to a self-hosted
 * Mem0 service.
 * <p>
 * Behavior matrix:
 * <ul>
 *   <li>{@code systemPromptBlock} — no-op (returns ""), aligns with SessionSearchProvider</li>
 *   <li>{@code prefetch(agentId, query, ownerKey)} — when {@code searchEnabled}
 *       and {@code ownerKey} is non-blank, calls {@code POST /memories/search/}
 *       and returns a {@code [Mem0 Recall]} block. Failures propagate to the
 *       platform's timeout/circuit-breaker boundary.</li>
 *   <li>{@code syncTurn(agentId, conversationId, messages, ownerKey)} — when
 *       {@code syncEnabled} and {@code ownerKey} is non-blank, asynchronously
 *       pushes the turn to {@code POST /memories/} under {@code user_id =
 *       ownerKey}, the same identifier prefetch recalls by. Failures are
 *       logged and swallowed; never blocks the response path. The bounded
 *       queue drops new writes when saturated. The four-arg
 *       variant (no ownerKey) skips — writing under any other identifier
 *       would produce memories that owner-scoped recall can never surface.</li>
 *   <li>{@code getToolBeans} — empty (no agent-facing tools in v1)</li>
 * </ul>
 *
 * <p>Per-owner isolation: {@code ownerKey} (e.g. {@code "user:42"}) is passed
 * verbatim as Mem0's {@code user_id}; {@code agentId} as Mem0's {@code agent_id}.
 * When {@code ownerKey} is null/blank, both recall and sync are skipped — Mem0
 * requires {@code user_id}.
 *
 * <p>Asynchronous sync: a single-thread daemon executor with a bounded queue
 * prevents an unavailable Mem0 service from growing heap usage without limit.
 *
 * @author MateClaw Team
 */
class Mem0Provider implements PluginMemoryProvider {

    static final String ID = "mem0";

    private final Mem0Config config;
    private final Mem0Client client;
    private final Logger log;
    private final ThreadPoolExecutor async;
    private final AtomicLong droppedSyncCount = new AtomicLong();

    Mem0Provider(Mem0Config config, Mem0Client client, Logger log) {
        this.config = config;
        this.client = client;
        this.log = log;
        this.async = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.syncQueueCapacity())), r -> {
                    Thread t = new Thread(r, "mem0-sync");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        // Same as the SPI default; declared explicitly for clarity.
        return 200;
    }

    @Override
    public boolean isAvailable() {
        // Provider is "available" if at least one of recall/sync can fire.
        return config.isUsable() && (config.searchEnabled() || config.syncEnabled());
    }

    @Override
    public String systemPromptBlock(Long agentId) {
        return "";
    }

    @Override
    public String prefetch(Long agentId, String userQuery) {
        // Two-arg variant: no owner key → cannot isolate per-user → skip.
        // Mem0 requires user_id; without it the call would either fail or
        // return global memories breaking per-owner isolation.
        return "";
    }

    @Override
    public String prefetch(Long agentId, String userQuery, String ownerKey) {
        if (!config.searchEnabled()) {
            return "";
        }
        if (ownerKey == null || ownerKey.isBlank()) {
            return "";
        }
        if (userQuery == null || userQuery.isBlank()) {
            return "";
        }
        List<String> memories = client.searchMemories(
                ownerKey, agentId == null ? null : agentId.toString(), userQuery);
        if (memories.isEmpty()) {
            return "";
        }
        return formatRecallBlock(memories);
    }

    @Override
    public void syncTurn(Long agentId, String conversationId,
                         String userMessage, String assistantReply) {
        // Four-arg variant: no owner key → skip. Mem0 keys memories by user_id;
        // writing under any fallback identifier (e.g. agentId) would store
        // memories that owner-scoped prefetch can never recall.
    }

    @Override
    public void syncTurn(Long agentId, String conversationId,
                         String userMessage, String assistantReply, String ownerKey) {
        if (!config.syncEnabled()) {
            return;
        }
        if (ownerKey == null || ownerKey.isBlank()) {
            // Same guard as prefetch: Mem0 requires user_id; without the owner
            // key the write would break per-owner isolation.
            return;
        }
        if ((userMessage == null || userMessage.isBlank())
                && (assistantReply == null || assistantReply.isBlank())) {
            return;
        }
        try {
            async.execute(() -> {
                try {
                    client.addMemories(ownerKey, agentId == null ? null : agentId.toString(),
                            conversationId, userMessage, assistantReply);
                } catch (Exception e) {
                    log.debug("[Mem0] syncTurn failed for agent={} owner={}: {}",
                            agentId, ownerKey, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            long dropped = droppedSyncCount.incrementAndGet();
            log.warn("[Mem0] sync queue full or provider closed; dropped turn for agent={} owner={} (totalDropped={})",
                    agentId, ownerKey, dropped);
        }
    }

    int queuedSyncCount() {
        return async.getQueue().size();
    }

    long droppedSyncCount() {
        return droppedSyncCount.get();
    }

    boolean isClosed() {
        return async.isShutdown();
    }

    @Override
    public void close() {
        async.shutdown();
        List<Runnable> dropped = List.of();
        try {
            long drainMs = Math.min(1000L, Math.max(100L, config.timeoutMs()));
            if (!async.awaitTermination(drainMs, TimeUnit.MILLISECONDS)) {
                dropped = async.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dropped = async.shutdownNow();
        }
        if (!dropped.isEmpty()) {
            droppedSyncCount.addAndGet(dropped.size());
            log.warn("[Mem0] provider closed with {} queued sync turn(s) discarded", dropped.size());
        }
    }

    @Override
    public void onSessionEnd(Long agentId, String conversationId) {
        // No Mem0-specific session cleanup needed in v1.
    }

    /**
     * Format the recalled memories into a labeled block.
     * <p>
     * The {@code [Mem0 Recall]} label is intentional: it lets the LLM
     * distinguish this block from the local providers' output and avoid
     * treating it as authoritative PROFILE.md content.
     */
    private String formatRecallBlock(List<String> memories) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Mem0 Recall — semantic matches from external service, treat as hints]\n");
        for (int i = 0; i < memories.size(); i++) {
            sb.append(i + 1).append(". ").append(memories.get(i)).append('\n');
        }
        return sb.toString();
    }
}
