package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.lang.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.delegation.SubagentRegistry.SubagentRecord;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only session listing tool: enumerates the sub-agents the current
 * conversation has delegated to — both the ones still running and the ones that
 * have finished and can be followed up on.
 *
 * <p>Completes the spawn / send / list triad alongside {@link DelegateAgentTool}
 * (spawn) and {@link SessionSendTool} (send). It is the discovery surface for
 * send: each row carries the {@code session_id} (the child's persisted
 * conversation id) so the parent can pick a finished child and continue it,
 * which the live in-memory registry alone cannot show (it drops a child the
 * moment it completes).
 *
 * <p>Source of truth is the persisted direct children of the caller's
 * conversation (so finished sessions stay discoverable), overlaid with live
 * status from {@link SubagentRegistry} for any child still running. Resolves the
 * caller conversation the same way the delegation relay does. Read-only, so it
 * is safe for children to call.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionListTool {

    /** Cap on rows so a conversation with a long delegation history stays readable. */
    private static final int MAX_ROWS = 30;

    private final SubagentRegistry subagentRegistry;
    private final ConversationMapper conversationMapper;

    @Tool(description = """
            List the sub-agents this conversation has delegated to — both running and finished —
            with each one's session_id, target agent, status, and goal/title. Use it to discover a
            session_id to follow up on via send_to_subagent, or to check whether a child you
            delegated is still running before deciding to wait or proceed. Read-only.""")
    public String listSubagents(@Nullable ToolContext ctx) {
        String callerConversationId = resolveCallerConversationId();
        if (callerConversationId == null || callerConversationId.isBlank()) {
            return "No sub-agent sessions (no conversation context).";
        }

        // Persisted direct children — the sendable sessions, including finished ones.
        List<ConversationEntity> children = conversationMapper.selectList(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getParentConversationId, callerConversationId)
                        .eq(ConversationEntity::getDeleted, 0)
                        .orderByDesc(ConversationEntity::getLastActiveTime)
                        .last("LIMIT " + MAX_ROWS));

        // Live status overlay, keyed by the child conversation id.
        Map<String, SubagentRecord> liveByConv = new LinkedHashMap<>();
        for (SubagentRecord r : subagentRegistry.snapshot(callerConversationId)) {
            if (r.childConversationId() != null) {
                liveByConv.put(r.childConversationId(), r);
            }
        }

        if (children.isEmpty() && liveByConv.isEmpty()) {
            return "No sub-agent sessions for this conversation.";
        }

        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("Sub-agent sessions for this conversation (").append(
                Math.max(children.size(), liveByConv.size())).append("):\n");

        for (ConversationEntity child : children) {
            String convId = child.getConversationId();
            SubagentRecord live = liveByConv.remove(convId);
            sb.append(live != null ? formatLive(convId, live, now) : formatPersisted(child)).append('\n');
        }
        // Any live record whose persisted row wasn't returned (e.g. just spawned,
        // outside the LIMIT window) still gets listed so nothing in flight hides.
        for (Map.Entry<String, SubagentRecord> e : liveByConv.entrySet()) {
            sb.append(formatLive(e.getKey(), e.getValue(), now)).append('\n');
        }

        sb.append("Follow up with send_to_subagent(session_id, message).");
        return sb.toString();
    }

    /**
     * Caller conversation to scope the listing to: the relay-carried root when
     * this runs inside a delegated layer, otherwise the current conversation.
     */
    private String resolveCallerConversationId() {
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

    private String formatLive(String convId, SubagentRecord r, long now) {
        long elapsedSec = Math.max(0, (now - r.startedAt()) / 1000);
        return "- session_id=" + convId
                + " | agent=" + r.agentId()
                + " | " + r.status().get()
                + " | phase=" + r.currentPhase().get()
                + " | tools=" + r.toolCount().get()
                + " | elapsed=" + elapsedSec + "s"
                + " | goal=\"" + clip(r.goal(), 80) + "\"";
    }

    private String formatPersisted(ConversationEntity child) {
        String title = child.getTitle();
        String when = child.getLastActiveTime() != null ? child.getLastActiveTime().toString() : "";
        return "- session_id=" + child.getConversationId()
                + " | agent=" + child.getAgentId()
                + " | idle"
                + (when.isEmpty() ? "" : " | last active " + when)
                + (title == null || title.isBlank() ? "" : " | \"" + clip(title, 80) + "\"");
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
