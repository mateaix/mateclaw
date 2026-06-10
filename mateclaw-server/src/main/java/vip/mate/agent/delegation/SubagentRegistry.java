package vip.mate.agent.delegation;

import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide registry of live sub-agents spawned through the delegation flow.
 *
 * <p>Holds the in-memory subagent tree so the parent transcript, the heartbeat
 * watcher, and the operator UI can observe / interrupt children that the parent
 * conversation spawned. Records use atomic accessors throughout because the
 * heartbeat thread may mutate {@code staleCount} / {@code status} concurrently
 * with the spawning thread that registered the record.
 *
 * <p>The pause flag is keyed per parent conversation so two unrelated users
 * cannot freeze each other's spawning by toggling a global switch.
 */
@Component
public class SubagentRegistry {

    /**
     * Single live sub-agent.
     *
     * <p>Mutable counters are atomics so the heartbeat scheduler and the
     * spawn / completion thread can update them without locking. Status is
     * driven by external lifecycle events; allowed values are
     * {@code running} / {@code completed} / {@code interrupted} / {@code stale}
     * / {@code timeout}.
     */
    public record SubagentRecord(
            String subagentId,
            String parentConversationId,
            String childConversationId,
            Long agentId,
            String goal,
            long startedAt,
            AtomicReference<String> status,
            AtomicInteger toolCount,
            AtomicReference<String> lastTool,
            AtomicReference<String> currentPhase,
            AtomicInteger lastSeenIter,
            AtomicReference<String> lastSeenTool,
            AtomicInteger staleCount,
            AtomicLong firstApiCallAt,
            Disposable disposable,
            // Tree identity: parentSubagentId is null for first-level children
            // (spawned by the root agent); depth is 1 for first-level, 2 for a
            // grandchild, etc. rootConversationId is the human-facing stream the
            // whole tree reports into, used for UI-facing broadcasts at any depth.
            String parentSubagentId,
            int depth,
            String rootConversationId
    ) {}

    private final ConcurrentMap<String, SubagentRecord> active = new ConcurrentHashMap<>();

    /**
     * Per-parent pause flag set: scoping prevents one user from freezing
     * another user's spawning. A parent conversation appears in this set iff
     * spawning is currently paused for it.
     */
    private final Set<String> pausedParents = ConcurrentHashMap.newKeySet();

    private final SecureRandom rng = new SecureRandom();

    /**
     * Register a freshly spawned sub-agent. Returns the assigned subagentId
     * which the caller must thread through to {@link #unregister(String)} on
     * completion (success / failure / timeout) so the registry does not leak.
     *
     * <p>ID format {@code sa-<epoch_ms>-<8 hex chars>} keeps IDs sortable by
     * spawn time while the random suffix prevents collisions when many
     * children spawn within the same millisecond.
     */
    public String register(String parentConvId, String childConvId, Long agentId, String goal, Disposable d) {
        return register(parentConvId, childConvId, agentId, goal, d, null, 1, parentConvId);
    }

    /**
     * Register a sub-agent with full tree identity. {@code parentSubagentId} is
     * null for first-level children; {@code depth} is 1-based; {@code rootConvId}
     * is the human-facing conversation the whole tree reports into.
     */
    public String register(String parentConvId, String childConvId, Long agentId, String goal,
                           Disposable d, String parentSubagentId, int depth, String rootConvId) {
        String sid = "sa-" + System.currentTimeMillis() + "-" + nextHexSuffix();
        active.put(sid, new SubagentRecord(
                sid,
                parentConvId,
                childConvId,
                agentId,
                goal,
                System.currentTimeMillis(),
                new AtomicReference<>("running"),
                new AtomicInteger(0),
                new AtomicReference<>(""),
                new AtomicReference<>("starting"),
                new AtomicInteger(0),
                new AtomicReference<>(null),
                new AtomicInteger(0),
                new AtomicLong(0),
                d,
                parentSubagentId,
                depth,
                rootConvId != null ? rootConvId : parentConvId));
        return sid;
    }

    /**
     * Mark a sub-agent as interrupted and dispose its underlying stream
     * subscription if one was registered. Returns {@code false} when the
     * subagentId is unknown (already cleaned up or never registered) so
     * callers can distinguish "not running anymore" from "interrupted".
     */
    public boolean interrupt(String subagentId) {
        if (subagentId == null) return false;
        SubagentRecord r = active.get(subagentId);
        if (r == null) return false;
        r.status().set("interrupted");
        Disposable d = r.disposable();
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        return true;
    }

    public Optional<SubagentRecord> get(String subagentId) {
        return subagentId == null ? Optional.empty() : Optional.ofNullable(active.get(subagentId));
    }

    /**
     * Snapshot of all sub-agents whose <em>immediate</em> parent matches
     * {@code parentConvId}. Filtering at the registry boundary prevents callers
     * from accidentally surfacing other tenants' subagents in API responses.
     *
     * <p>Note: this returns only direct children. To list a whole delegation
     * tree (including grandchildren whose immediate parent is a child
     * conversation), use {@link #snapshotTree(String)}.
     */
    public List<SubagentRecord> snapshot(String parentConvId) {
        if (parentConvId == null) return List.of();
        return active.values().stream()
                .filter(r -> parentConvId.equals(r.parentConversationId()))
                .toList();
    }

    /**
     * Snapshot of the entire delegation tree rooted at {@code rootConvId} — the
     * human-facing conversation. Every sub-agent at any depth carries the same
     * {@code rootConversationId}, so this returns direct children and all deeper
     * descendants. Tenant isolation must be enforced on {@code rootConvId} by
     * the caller (it is the conversation the user owns).
     */
    public List<SubagentRecord> snapshotTree(String rootConvId) {
        if (rootConvId == null) return List.of();
        return active.values().stream()
                .filter(r -> rootConvId.equals(r.rootConversationId()))
                .toList();
    }

    public void unregister(String subagentId) {
        if (subagentId == null) return;
        active.remove(subagentId);
    }

    public boolean isSpawnPaused(String parentConvId) {
        if (parentConvId == null) return false;
        return pausedParents.contains(parentConvId);
    }

    /**
     * Toggle the pause flag for one parent conversation. Returns the new
     * paused state so the caller can echo the resulting flag without an
     * extra read.
     */
    public boolean setSpawnPaused(String parentConvId, boolean paused) {
        if (parentConvId == null) return false;
        if (paused) {
            pausedParents.add(parentConvId);
        } else {
            pausedParents.remove(parentConvId);
        }
        return paused;
    }

    public Collection<SubagentRecord> allActive() {
        return active.values();
    }

    /** Lowercase 8-hex-char suffix sourced from a SecureRandom. */
    private String nextHexSuffix() {
        byte[] bytes = new byte[4];
        rng.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(8);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
