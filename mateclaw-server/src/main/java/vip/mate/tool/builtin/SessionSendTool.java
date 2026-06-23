package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

/**
 * Multi-turn "send" leg of the spawn / send / list delegation triad: continues a
 * sub-agent's existing session with a follow-up message instead of spawning a
 * fresh, context-less child.
 *
 * <p>The session handle is the child's own (persisted) {@code conversationId},
 * surfaced by {@link DelegateAgentTool#delegateToAgent} in its result. Because
 * the child conversation persists past the original delegation call, the parent
 * can later ask it to refine / expand / correct its earlier output and the child
 * still sees its own prior context.
 *
 * <p>Guards mirror the spawn path: the recursion depth cap is shared with
 * {@link DelegateAgentTool}, a child may only be continued by the conversation
 * that spawned it, and the continued run is re-entered under the standard child
 * deny set so it cannot delegate or send onward.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSendTool {

    private static final int MAX_RESULT_LENGTH = 4000;

    private final AgentService agentService;
    private final ConversationMapper conversationMapper;
    private final SubagentRegistry subagentRegistry;

    @Tool(description = """
            Send a follow-up message to a sub-agent you previously delegated to, continuing its
            existing session (so it still remembers the earlier task) rather than starting fresh.
            Pass the session_id that delegateToAgent returned. Use it to ask a child to refine,
            expand, or correct its earlier result.""")
    public String sendToSubagent(
            @ToolParam(description = "The session_id returned by a prior delegateToAgent call") String sessionId,
            @ToolParam(description = "Follow-up message / instruction for the sub-agent") String message,
            @Nullable ToolContext ctx) {

        if (sessionId == null || sessionId.isBlank()) {
            return "[Error] session_id is required.";
        }
        if (message == null || message.isBlank()) {
            return "[Error] message is required.";
        }

        // Depth guard: a send re-enters a child run one level below the caller,
        // so refuse if that would breach the shared recursion cap.
        int callerDepth = DelegationContext.currentDepth();
        if (callerDepth >= DelegateAgentTool.MAX_DELEGATION_DEPTH) {
            return "[Error] Delegation depth limit (" + DelegateAgentTool.MAX_DELEGATION_DEPTH
                    + ") reached; cannot follow up on a sub-agent from here.";
        }

        ConversationEntity child = conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, sessionId));
        if (child == null) {
            return "[Error] Unknown session_id: " + sessionId;
        }
        if (child.getParentConversationId() == null) {
            return "[Error] " + sessionId + " is not a sub-agent session.";
        }

        // Tenant / ownership: only the conversation that spawned the child may
        // continue it, so a sibling or another tenant cannot drive someone
        // else's sub-agent.
        String callerConversationId = resolveCallerConversationId();
        if (callerConversationId == null || !callerConversationId.equals(child.getParentConversationId())) {
            return "[Error] session " + sessionId + " does not belong to this conversation.";
        }
        if (child.getAgentId() == null) {
            return "[Error] session " + sessionId + " has no bound agent.";
        }

        Long agentId = child.getAgentId();
        ChatOrigin origin = ChatOrigin.from(ctx).withAgent(agentId).withConversationId(sessionId);

        // Register the continuation so an in-flight follow-up is visible to
        // SessionListTool and interruptible via the subagent control API, then
        // re-enter the delegation context one level below the caller so the
        // continued child stays gated (cannot delegate / send onward) and the
        // depth cap keeps holding for anything it tries to spawn.
        String subagentId = subagentRegistry.register(callerConversationId, sessionId, agentId, message, null);
        DelegationContext.enter(callerConversationId, DelegateAgentTool.DEFAULT_CHILD_DENIED_TOOLS,
                resolveRootConversationId(callerConversationId), null, callerDepth + 1);
        try {
            String raw = agentService.chat(agentId, message, sessionId, origin);
            return "[Sub-agent reply | session " + sessionId + "]\n\n"
                    + truncate(raw != null ? raw : "", MAX_RESULT_LENGTH);
        } catch (Exception e) {
            log.error("send_to_subagent failed: session={}, error={}", sessionId, e.getMessage());
            return "[Error] Sub-agent follow-up failed: " + e.getMessage();
        } finally {
            DelegationContext.exit();
            subagentRegistry.unregister(subagentId);
        }
    }

    private String resolveCallerConversationId() {
        try {
            String c = ToolExecutionContext.conversationId();
            if (c != null && !c.isBlank()) {
                return c;
            }
        } catch (Exception ignored) {
            // fall through to the delegation frame below
        }
        return DelegationContext.parentConversationId();
    }

    private String resolveRootConversationId(String callerConversationId) {
        String root = DelegationContext.rootConversationId();
        return (root != null && !root.isBlank()) ? root : callerConversationId;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n... [truncated, original " + text.length() + " chars]";
    }
}
