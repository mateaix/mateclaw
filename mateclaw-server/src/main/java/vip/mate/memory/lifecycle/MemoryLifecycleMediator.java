package vip.mate.memory.lifecycle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.memory.spi.MemoryManager;

/**
 * Mediator between the Agent entry-layer (AgentService) and MemoryManager.
 * Agent code only calls this class; it hides the details of when/how
 * providers are invoked across a turn's lifecycle.
 *
 * <p>Non-goals:
 * <ul>
 *   <li>Does NOT call MemoryRecallTracker.trackRecalls — AgentService already
 *       owns that call; duplicating here would double recall_count / daily_count
 *       and pollute Dream scoring (rfc-037 F4).</li>
 *   <li>Does NOT prefetch next-turn recall keyed on current-turn query —
 *       query-conditioned providers cannot reuse stale queries (rfc-037 F2).</li>
 * </ul>
 *
 * <p>Thread-safety: all public methods are reentrant; per-turn state lives
 * in {@link TurnContext}.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryLifecycleMediator {

    private final MemoryManager memoryManager;
    private final ApplicationEventPublisher events;

    /**
     * Called BEFORE the LLM is invoked for a turn.
     * Returns the memory-context block to inject, or "" if none.
     *
     * <p>Latency contract: synchronous. BuiltinMemoryProvider returns "" so the
     * only cost today is iteration overhead (&lt;5ms).
     */
    public String beforeLlmCall(TurnContext ctx) {
        try {
            String context = memoryManager.prefetchAll(ctx.agentId(), ctx.userQuery(), ctx.ownerKey());
            events.publishEvent(new TurnStartedEvent(ctx));
            log.debug("[Memory] beforeLlmCall: agent={}, contextLen={}", ctx.agentId(),
                    context != null ? context.length() : 0);
            return context;
        } catch (Exception e) {
            log.debug("[Memory] beforeLlmCall failed (non-fatal): {}", e.getMessage());
            return "";
        }
    }

    /**
     * Called AFTER the LLM finishes a turn successfully.
     * Non-blocking: MemoryManager.syncAll dispatches to provider.syncTurn(),
     * each provider is responsible for being async internally.
     */
    public void afterLlmCall(TurnContext ctx, String assistantReply) {
        try {
            memoryManager.syncAll(ctx.agentId(), ctx.conversationId(),
                    ctx.userQuery(), assistantReply);
            events.publishEvent(new TurnCompletedEvent(ctx, assistantReply));
            log.debug("[Memory] afterLlmCall: agent={}, conv={}, replyLen={}", ctx.agentId(),
                    ctx.conversationId(), assistantReply != null ? assistantReply.length() : 0);
        } catch (Exception e) {
            log.debug("[Memory] afterLlmCall failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Called when a conversation ends (from MemoryLifecycleEventListener).
     */
    public void onSessionEnd(Long agentId, String conversationId) {
        try {
            memoryManager.onSessionEnd(agentId, conversationId);
            log.debug("[Memory] onSessionEnd: agent={}, conv={}", agentId, conversationId);
        } catch (Exception e) {
            log.debug("[Memory] onSessionEnd dispatch failed (non-fatal): {}", e.getMessage());
        }
    }
}
