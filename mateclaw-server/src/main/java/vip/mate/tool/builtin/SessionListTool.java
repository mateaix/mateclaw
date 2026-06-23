package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.lang.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.delegation.SubagentRegistry.SubagentRecord;

import java.util.List;

/**
 * Read-only session listing tool: enumerates the live sub-agents spawned from
 * the current conversation's delegation tree.
 *
 * <p>Completes the spawn / send / list triad alongside {@link DelegateAgentTool}
 * (spawn). Where {@code delegateParallel} fans children out and blocks for their
 * combined result, this lets the parent agent inspect the tree mid-reasoning —
 * which children are still running, what phase / tool each is on, how many tool
 * calls each has made — so it can decide whether to wait, follow up, or move on
 * instead of re-dispatching roles it already spawned.
 *
 * <p>Resolves the human-facing root conversation the same way the delegation
 * relay does ({@link DelegationContext#rootConversationId()} when running inside
 * a delegated layer, otherwise the current {@link ToolExecutionContext}
 * conversation), then reads the in-memory {@link SubagentRegistry}. It never
 * mutates state, so it is safe for children to call as well.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionListTool {

    private final SubagentRegistry subagentRegistry;

    @Tool(description = """
            List the live sub-agents spawned from the current conversation, including each one's
            id, target agent, tree depth, status (running/completed/interrupted/stale/timeout),
            current phase, tool-call count, elapsed time, and goal. Read-only: use it to check on
            children you delegated before deciding to wait, follow up, or proceed — do NOT re-spawn
            a role that is already listed as running.""")
    public String listSubagents(@Nullable ToolContext ctx) {
        String rootConversationId = resolveRootConversationId();
        if (rootConversationId == null || rootConversationId.isBlank()) {
            return "No active sub-agents (no conversation context).";
        }

        List<SubagentRecord> records = subagentRegistry.snapshotTree(rootConversationId);
        if (records.isEmpty()) {
            return "No active sub-agents for this conversation.";
        }

        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("Active sub-agents (").append(records.size()).append("):\n");
        // Stable, human-readable order: shallow layers first, then by spawn time
        // so a parent reads its direct children before their descendants.
        records.stream()
                .sorted((a, b) -> {
                    int byDepth = Integer.compare(a.depth(), b.depth());
                    return byDepth != 0 ? byDepth : Long.compare(a.startedAt(), b.startedAt());
                })
                .forEach(r -> sb.append(formatRecord(r, now)).append('\n'));
        return sb.toString().stripTrailing();
    }

    /**
     * Root of the delegation tree to list: the relay-carried root when this call
     * happens inside a delegated layer, falling back to the current conversation
     * (the top-level agent's own conversation, which is the tree root for the
     * children it spawned).
     */
    private String resolveRootConversationId() {
        String root = DelegationContext.rootConversationId();
        if (root != null && !root.isBlank()) {
            return root;
        }
        try {
            return ToolExecutionContext.conversationId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String formatRecord(SubagentRecord r, long now) {
        long elapsedSec = Math.max(0, (now - r.startedAt()) / 1000);
        String goal = r.goal() == null ? "" : r.goal();
        if (goal.length() > 80) {
            goal = goal.substring(0, 80) + "…";
        }
        return "- [" + r.subagentId() + "] agent=" + r.agentId()
                + " depth=" + r.depth()
                + " status=" + r.status().get()
                + " phase=" + r.currentPhase().get()
                + " tools=" + r.toolCount().get()
                + " elapsed=" + elapsedSec + "s"
                + " goal=\"" + goal + "\"";
    }
}
