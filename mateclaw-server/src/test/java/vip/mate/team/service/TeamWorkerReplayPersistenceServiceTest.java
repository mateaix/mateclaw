package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.workspace.conversation.ConversationService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamWorkerReplayPersistenceServiceTest {

    @Test
    void messageAndIdempotencyMarkerMustBothSucceed() {
        ConversationService conversations = mock(ConversationService.class);
        TeamTaskService tasks = mock(TeamTaskService.class);
        TeamWorkerReplayPersistenceService service =
                new TeamWorkerReplayPersistenceService(conversations, tasks);
        AgentService.ChatResult result = AgentService.ChatResult.contentOnly("done");
        when(tasks.markToolReplayMessagePersisted(101L, "pending-42")).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.persist(101L, "pending-42", "worker-101", "done", result));

        verify(conversations).saveMessage("worker-101", "assistant", "done",
                null, "completed", 0, 0, null, null);
        verify(tasks).markToolReplayMessagePersisted(101L, "pending-42");
    }
}
