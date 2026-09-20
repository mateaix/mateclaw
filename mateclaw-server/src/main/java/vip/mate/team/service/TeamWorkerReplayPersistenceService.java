package vip.mate.team.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.AgentService;
import vip.mate.workspace.conversation.ConversationService;

/** Atomically records a replay reply and its task-level idempotency marker. */
@Service
@RequiredArgsConstructor
public class TeamWorkerReplayPersistenceService {

    private final ConversationService conversationService;
    private final TeamTaskService taskService;

    @Transactional
    public void persist(Long taskId, String pendingId, String conversationId,
                        String reply, AgentService.ChatResult result) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        if (result == null) {
            conversationService.saveMessage(conversationId, "assistant", reply);
        } else {
            conversationService.saveMessage(conversationId, "assistant", reply,
                    null, "completed", result.promptTokens(), result.completionTokens(),
                    result.runtimeModel(), result.runtimeProvider());
        }
        if (!taskService.markToolReplayMessagePersisted(taskId, pendingId)) {
            throw new IllegalStateException("tool replay message marker could not be persisted");
        }
    }
}
