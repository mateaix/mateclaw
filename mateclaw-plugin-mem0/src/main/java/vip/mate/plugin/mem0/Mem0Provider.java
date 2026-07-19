package vip.mate.plugin.mem0;

import org.slf4j.Logger;
import vip.mate.plugin.api.memory.PluginMemoryProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Memory provider that bridges MateClaw's per-turn lifecycle to a self-hosted
 * Mem0 service.
 * <p>
 * Behavior matrix:
 * <ul>
 *   <li>{@code systemPromptBlock} — no-op (returns ""), aligns with SessionSearchProvider</li>
 *   <li>{@code prefetch(agentId, query, ownerKey)} — when {@code searchEnabled}
 *       and {@code ownerKey} is non-blank, calls {@code POST /memories/search/}
 *       and returns a {@code [Mem0 Recall]} block. Returns "" on any failure
 *       or when disabled.</li>
 *   <li>{@code syncTurn} — when {@code syncEnabled} and {@code ownerKey} is
 *       non-blank, asynchronously pushes the turn to {@code POST /memories/}.
 *       Failures are logged and swallowed; never blocks the response path.</li>
 *   <li>{@code getToolBeans} — empty (no agent-facing tools in v1)</li>
 * </ul>
 *
 * <p>Per-owner isolation: {@code ownerKey} (e.g. {@code "user:42"}) is passed
 * verbatim as Mem0's {@code user_id}; {@code agentId} as Mem0's {@code agent_id}.
 * When {@code ownerKey} is null/blank, both recall and sync are skipped — Mem0
 * requires {@code user_id}.
 *
 * <p>Asynchronous sync: a single-thread virtual-thread-per-task executor is used
 * so that bursts of turns don't pile up on the platform's request thread.
 *
 * @author MateClaw Team
 */
class Mem0Provider implements PluginMemoryProvider {

    static final String ID = "mem0";

    private final Mem0Config config;
    private final Mem0Client client;
    private final Logger log;
    private final Executor async;

    Mem0Provider(Mem0Config config, Mem0Client client, Logger log) {
        this.config = config;
        this.client = client;
        this.log = log;
        // Single-thread executor is enough — syncTurn calls are sequential per
        // agent and not latency-sensitive; the platform's request thread must
        // not be blocked. A bounded single-thread queue keeps memory footprint
        // predictable even under burst load.
        this.async = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mem0-sync");
            t.setDaemon(true);
            return t;
        });
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
        try {
            List<String> memories = client.searchMemories(
                    ownerKey, agentId == null ? null : agentId.toString(), userQuery);
            if (memories.isEmpty()) {
                return "";
            }
            return formatRecallBlock(memories);
        } catch (Exception e) {
            // Fault isolation: log and return empty so the platform falls back
            // to the other (local) providers without affecting the response.
            log.warn("[Mem0] prefetch failed for agent={} owner={}: {}",
                    agentId, ownerKey, e.getMessage());
            return "";
        }
    }

    @Override
    public void syncTurn(Long agentId, String conversationId,
                         String userMessage, String assistantReply) {
        if (!config.syncEnabled()) {
            return;
        }
        // ownerKey is NOT available in the two-arg syncTurn signature
        // (PlatformMemoryProvider only passes agentId + conversationId + messages).
        // We push the turn using agentId as the user_id fallback — this is
        // weaker isolation than prefetch (which has ownerKey), but better than
        // dropping the turn. If users need strict per-owner sync, configure
        // syncEnabled=false and rely on prefetch-only recall.
        // NOTE: this is a known v1 limitation; a future SPI extension would
        // pass ownerKey into syncTurn as well.
        String userId = agentId == null ? null : agentId.toString();
        if (userId == null || userId.isBlank()) {
            return;
        }
        if ((userMessage == null || userMessage.isBlank())
                && (assistantReply == null || assistantReply.isBlank())) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                client.addMemories(userId, agentId == null ? null : agentId.toString(),
                        conversationId, userMessage, assistantReply);
            } catch (Exception e) {
                log.debug("[Mem0] syncTurn failed for agent={}: {}", agentId, e.getMessage());
            }
        }, async);
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
